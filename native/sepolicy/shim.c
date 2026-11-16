/*
 * Container policy injection for the file daemon.
 *
 * The daemon reads the running kernel policy, derives a container domain from
 * its own domain (whatever the root solution put it in: ksu, magisk, su),
 * copies that domain's allow rules minus a blocklist, copies its place in the
 * type-name sets of every constraint, adds the transition into the new domain,
 * and writes the image back. Container processes are then placed in the domain
 * at exec time by writing /proc/self/attr/exec.
 *
 * The check is not "is the domain already there": the domain is shared, while
 * the transition, entry point and inherited rules depend on the daemon that
 * launched it. Every call therefore parses the image and ensures the rules for
 * this caller and this rootfs, and only loads the image when that changed a
 * rule. An early exit on the type name would leave a second root solution (or
 * a second rootfs label) without an entry point, so exec fails with EACCES.
 *
 * All policy access goes through the pinned libsepol this file is linked with
 * (native/build-libsepol.sh); nothing here talks to a device library.
 *
 * SELinux has no deny rules: "removing" a permission means not carrying the
 * rule over when the source domain is copied. A permission that is never
 * inserted is never granted.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <sepol/policydb.h>
#include <sepol/policydb/avtab.h>
#include <sepol/policydb/constraint.h>
#include <sepol/policydb/ebitmap.h>
#include <sepol/policydb/hashtab.h>
#include <sepol/policydb/policydb.h>
#include <sepol/policydb/symtab.h>

/* The one domain name this injection owns. The raw-image scan below and the
 * daemon's exec hook both use the same literal, so it must not change without
 * invalidating existing policies. */
#define FC_DOMAIN "file_container"
#define FC_DOMAIN_LEN (sizeof(FC_DOMAIN) - 1)

#define FC_STATUS_APPLIED 0
#define FC_STATUS_PRESENT 1
#define FC_STATUS_DISABLED 2
#define FC_STATUS_ERROR (-1)

/* Marker for "policy file does not exist": SELinux is off, not an error. */
#define FC_NO_POLICY (-2)

/* Level for the container context, chosen by the last successful
 * `file_container_ensure` (the app's when known, else the rootfs's).
 * `file_container_context` applies it, so every anonymous inode a container
 * process creates (memfd, pipe, socketpair) carries the same MCS categories as
 * the app that receives it — the receiver's `mlsconstrain` then passes on the
 * `l1 eq l2` term instead of needing MLS trust. */
static char *FC_EXEC_LEVEL = NULL;

#define FC_POLICY_PATH "/sys/fs/selinux/policy"
#define FC_POLICYVERS_PATH "/sys/fs/selinux/policyvers"
#define FC_LOAD_PATH "/sys/fs/selinux/load"
#define FC_CURRENT_PATH "/proc/self/attr/current"

/* Attributes are serialized from version 24 on, and the extended ioctl
 * allowlists that terminals and device nodes depend on need version 30.
 * Android policies are at least 30, so require it and never produce a
 * half-copied domain; older kernels get no injection at all. */
#define FC_MIN_POLICYVERS POLICYDB_VERSION_XPERMS_IOCTL

#define FC_MAX_POLICY_BYTES (128u * 1024u * 1024u)

#define FC_API __attribute__((visibility("default")))

extern int policydb_validate(sepol_handle_t *handle, const policydb_t *p);

typedef struct class_filter {
    int drop;
    int strip_process;  /* remove the process perms that leave the domain */
    int strip_caps;     /* remove kernel-level capability classes */
    uint32_t strip;     /* mask applied to every copied rule of the class */
    uint32_t write_strip; /* mask removed for restricted target types */
} class_filter_t;

static void set_error(char **err, const char *fmt, ...)
{
    va_list ap;
    char *msg = NULL;

    if (!err)
        return;
    va_start(ap, fmt);
    if (vasprintf(&msg, fmt, ap) < 0)
        msg = NULL;
    va_end(ap);
    *err = msg;
}

/* selinuxfs reports st_size == 0, so read to EOF instead of trusting stat. */
static int read_file(const char *path, char **out, size_t *out_len, char **err)
{
    int fd;
    char *buf = NULL;
    size_t cap = 0, len = 0;

    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (errno == ENOENT)
            return FC_NO_POLICY;
        set_error(err, "open %s: %s", path, strerror(errno));
        return -1;
    }

    for (;;) {
        if (len == cap) {
            size_t next = cap ? cap * 2 : 65536;
            char *grown;

            if (next > FC_MAX_POLICY_BYTES) {
                set_error(err, "%s is larger than %u bytes", path,
                          FC_MAX_POLICY_BYTES);
                goto fail;
            }
            grown = realloc(buf, next);
            if (!grown) {
                set_error(err, "out of memory reading %s", path);
                goto fail;
            }
            buf = grown;
            cap = next;
        }
        ssize_t n = read(fd, buf + len, cap - len);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            set_error(err, "read %s: %s", path, strerror(errno));
            goto fail;
        }
        if (n == 0)
            break;
        len += (size_t)n;
    }
    /* selinuxfs returns text; keep a terminator so the parsers below never
     * walk past the buffer when the file itself has none (policyvers). */
    if (len == cap) {
        char *grown = realloc(buf, cap + 1);

        if (!grown) {
            set_error(err, "out of memory reading %s", path);
            goto fail;
        }
        buf = grown;
        cap += 1;
    }
    buf[len] = '\0';
    close(fd);
    *out = buf;
    *out_len = len;
    return 0;

fail:
    close(fd);
    free(buf);
    return -1;
}

static int write_policy(const void *data, size_t len, char **err)
{
    int fd = open(FC_LOAD_PATH, O_WRONLY | O_CLOEXEC);

    if (fd < 0) {
        set_error(err, "open %s: %s", FC_LOAD_PATH, strerror(errno));
        return -1;
    }
    /* selinuxfs parses one complete image per write; a partial write is a
     * failed load, not something to retry. */
    ssize_t n = write(fd, data, len);
    if (n < 0) {
        set_error(err, "write %s: %s", FC_LOAD_PATH, strerror(errno));
        close(fd);
        return -1;
    }
    if ((size_t)n != len) {
        set_error(err, "short write to %s: %zd of %zu bytes", FC_LOAD_PATH, n,
                  len);
        close(fd);
        return -1;
    }
    if (close(fd) != 0) {
        set_error(err, "close %s: %s", FC_LOAD_PATH, strerror(errno));
        return -1;
    }
    return 0;
}

static int read_policyvers(int *vers, char **err)
{
    char *text = NULL;
    size_t len = 0;
    int rc = read_file(FC_POLICYVERS_PATH, &text, &len, err);

    if (rc != 0)
        return rc;
    errno = 0;
    char *end = NULL;
    long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || value <= 0 || value > 255) {
        set_error(err, "invalid %s", FC_POLICYVERS_PATH);
        free(text);
        return -1;
    }
    while (end && (*end == '\n' || *end == ' ' || *end == '\t'))
        end++;
    if (end && *end != '\0') {
        set_error(err, "invalid %s", FC_POLICYVERS_PATH);
        free(text);
        return -1;
    }
    free(text);
    *vers = (int)value;
    return 0;
}

/* Split u:r:type[:level]. Pointers refer into `buf`, which is modified. */
static int split_context(char *buf, char **user, char **role, char **type,
                         char **rest)
{
    char *first = strchr(buf, ':');
    char *second;
    char *third;

    if (!first)
        return -1;
    second = strchr(first + 1, ':');
    if (!second)
        return -1;
    third = strchr(second + 1, ':');
    *first = '\0';
    *second = '\0';
    *user = buf;
    *role = first + 1;
    *type = second + 1;
    if (third) {
        *third = '\0';
        *rest = third + 1;
    } else {
        *rest = NULL;
    }
    return 0;
}

static int current_type(char **type, char **role, char **user, char **rest,
                        char **err)
{
    char *buf = NULL;
    size_t len = 0;
    int rc = read_file(FC_CURRENT_PATH, &buf, &len, err);

    if (rc != 0)
        return rc;
    if (len == 0 || !memchr(buf, '\0', len)) {
        set_error(err, "empty %s", FC_CURRENT_PATH);
        free(buf);
        return -1;
    }
    if (split_context(buf, user, role, type, rest) != 0) {
        set_error(err, "malformed context: %s", buf);
        free(buf);
        return -1;
    }
    return 0;
}

static void *xrealloc(void *ptr, size_t size)
{
    return realloc(ptr, size);
}

static uint32_t perm_mask(policydb_t *p, uint32_t cls, const char *const *names,
                          size_t name_count)
{
    class_datum_t *cd;
    uint32_t mask = 0;

    if (cls == 0 || cls > p->p_classes.nprim)
        return 0;
    cd = p->class_val_to_struct[cls - 1];
    if (!cd)
        return 0;
    for (size_t i = 0; i < name_count; i++) {
        perm_datum_t *pd =
            hashtab_search(cd->permissions.table, (hashtab_key_t)names[i]);
        if (!pd && cd->comdatum)
            pd = hashtab_search(cd->comdatum->permissions.table,
                                (hashtab_key_t)names[i]);
        if (pd && pd->s.value > 0 && pd->s.value <= 32)
            mask |= UINT32_C(1) << (pd->s.value - 1);
    }
    return mask;
}

static uint32_t class_id(policydb_t *p, const char *name)
{
    class_datum_t *cd = hashtab_search(p->p_classes.table, (hashtab_key_t)name);

    return cd ? cd->s.value : 0;
}

static int name_in(const char *name, const char *const *list, size_t count)
{
    for (size_t i = 0; i < count; i++) {
        if (strcmp(name, list[i]) == 0)
            return 1;
    }
    return 0;
}

static int is_restricted_target(const char *name)
{
    static const char *const targets[] = {
        "sysfs_t",   "proc_sys_t", "proc_t",    "debugfs_t",
        "tracefs_t", "configfs_t", "bpf_t",     "security_t",
    };

    return name_in(name, targets, sizeof(targets) / sizeof(targets[0]));
}

static int is_dropped_target(const char *name)
{
    static const char *const targets[] = {
        "unlabeled_t",
    };

    return name_in(name, targets, sizeof(targets) / sizeof(targets[0]));
}

static int is_dropped_class(const char *name)
{
    static const char *const classes[] = {
        "process2",     "cap_userns",  "cap2_userns",
        "tun_socket",   "netlink_selinux_socket",
        "netlink_audit_socket",        "netlink_kernel_socket",
    };

    return name_in(name, classes, sizeof(classes) / sizeof(classes[0]));
}

static int add_type(policydb_t *p, const char *name, uint32_t *id_out,
                    int *changed, char **err)
{
    type_datum_t *td;
    char *key;
    uint32_t id;
    char **names;
    type_datum_t **types;
    ebitmap_t *type_attr;
    ebitmap_t *attr_type;

    if (hashtab_search(p->p_types.table, (hashtab_key_t)name)) {
        type_datum_t *existing =
            hashtab_search(p->p_types.table, (hashtab_key_t)name);
        *id_out = existing->s.value;
        return 0;
    }

    id = p->p_types.nprim + 1;
    td = calloc(1, sizeof(*td));
    key = strdup(name);
    if (!td || !key) {
        free(td);
        free(key);
        set_error(err, "out of memory adding type %s", name);
        return -1;
    }
    ebitmap_init(&td->types);
    td->s.value = id;
    td->primary = id;
    td->flavor = TYPE_TYPE;

    names = xrealloc(p->sym_val_to_name[SYM_TYPES], id * sizeof(*names));
    if (!names)
        goto oom;
    p->sym_val_to_name[SYM_TYPES] = names;

    types = xrealloc(p->type_val_to_struct, id * sizeof(*types));
    if (!types)
        goto oom;
    p->type_val_to_struct = types;

    type_attr = xrealloc(p->type_attr_map, id * sizeof(*type_attr));
    if (!type_attr)
        goto oom;
    p->type_attr_map = type_attr;

    attr_type = xrealloc(p->attr_type_map, id * sizeof(*attr_type));
    if (!attr_type)
        goto oom;
    p->attr_type_map = attr_type;

    ebitmap_init(&p->type_attr_map[id - 1]);
    ebitmap_init(&p->attr_type_map[id - 1]);
    if (ebitmap_set_bit(&p->type_attr_map[id - 1], id - 1, 1) != 0)
        goto oom;

    if (hashtab_insert(p->p_types.table, key, td) != 0) {
        free(key);
        free(td);
        set_error(err, "failed to insert type %s", name);
        return -1;
    }
    p->p_types.nprim = id;
    p->sym_val_to_name[SYM_TYPES][id - 1] = key;
    p->type_val_to_struct[id - 1] = td;
    *id_out = id;
    *changed = 1;
    return 0;

oom:
    free(key);
    free(td);
    set_error(err, "out of memory growing the type table");
    return -1;
}

static int add_membership(policydb_t *p, uint32_t type, const char *attr_name,
                          int *changed, char **err)
{
    type_datum_t *attr =
        hashtab_search(p->p_types.table, (hashtab_key_t)attr_name);
    int added = 0;

    if (!attr)
        return 0;
    if (attr->flavor != TYPE_ATTRIB)
        return 0;
    if (attr->s.value == 0 || attr->s.value > p->p_types.nprim)
        return 0;
    if (!ebitmap_get_bit(&p->type_attr_map[type - 1], attr->s.value - 1)) {
        if (ebitmap_set_bit(&p->type_attr_map[type - 1], attr->s.value - 1,
                            1) != 0)
            goto fail;
        added = 1;
    }
    if (!ebitmap_get_bit(&p->attr_type_map[attr->s.value - 1], type - 1)) {
        if (ebitmap_set_bit(&p->attr_type_map[attr->s.value - 1], type - 1,
                            1) != 0)
            goto fail;
        added = 1;
    }
    if (added)
        *changed = 1;
    return 0;

fail:
    set_error(err, "failed to add %s to %s", FC_DOMAIN, attr_name);
    return -1;
}

static int add_role_type(policydb_t *p, const char *role_name, uint32_t type,
                         int *changed, char **err)
{
    role_datum_t *role =
        hashtab_search(p->p_roles.table, (hashtab_key_t)role_name);

    if (!role) {
        set_error(err, "role %s not found", role_name);
        return -1;
    }
    if (ebitmap_get_bit(&role->types.types, type - 1))
        return 0;
    if (ebitmap_set_bit(&role->types.types, type - 1, 1) != 0) {
        set_error(err, "failed to authorize %s for role %s", FC_DOMAIN,
                  role_name);
        return -1;
    }
    *changed = 1;
    return 0;
}

/* Copy the daemon type's attribute memberships: mlstrustedsubject (needed to
 * reach /data files that carry MCS categories), domain, and any attribute the
 * root solution put on its own domain. Without this the new type would be a
 * plain domain and MLS constraints would deny the rootfs it is supposed to run.
 */
static int copy_attributes(policydb_t *p, uint32_t base, uint32_t type,
                           int *changed, char **err)
{
    ebitmap_t *source = &p->type_attr_map[base - 1];
    ebitmap_node_t *node;
    uint32_t bit;

    ebitmap_for_each_positive_bit(source, node, bit) {
        uint32_t value = bit + 1;
        type_datum_t *attr;
        int added = 0;

        if (value > p->p_types.nprim)
            continue;
        attr = p->type_val_to_struct[value - 1];
        if (!attr || attr->flavor != TYPE_ATTRIB)
            continue;
        if (!ebitmap_get_bit(&p->type_attr_map[type - 1], bit)) {
            if (ebitmap_set_bit(&p->type_attr_map[type - 1], bit, 1) != 0) {
                set_error(err, "failed to copy attribute %s",
                          p->p_type_val_to_name[value - 1]);
                return -1;
            }
            added = 1;
        }
        if (!ebitmap_get_bit(&p->attr_type_map[value - 1], type - 1)) {
            if (ebitmap_set_bit(&p->attr_type_map[value - 1], type - 1, 1) !=
                0) {
                set_error(err, "failed to copy attribute %s",
                          p->p_type_val_to_name[value - 1]);
                return -1;
            }
            added = 1;
        }
        if (added)
            *changed = 1;
    }
    return 0;
}

static int add_allow(policydb_t *p, uint32_t src, uint32_t tgt, uint32_t cls,
                     uint32_t perms, int *changed)
{
    avtab_key_t key;
    avtab_datum_t *existing;
    avtab_datum_t datum;

    if (perms == 0)
        return 0;
    memset(&key, 0, sizeof(key));
    key.source_type = (uint16_t)src;
    key.target_type = (uint16_t)tgt;
    key.target_class = (uint16_t)cls;
    key.specified = AVTAB_ALLOWED;

    existing = avtab_search(&p->te_avtab, &key);
    if (existing) {
        uint32_t merged = existing->data | perms;

        if (merged != existing->data) {
            existing->data = merged;
            *changed = 1;
        }
        return 0;
    }
    memset(&datum, 0, sizeof(datum));
    datum.data = perms;
    if (avtab_insert(&p->te_avtab, &key, &datum) != 0)
        return -1;
    *changed = 1;
    return 0;
}

static int add_allow_name(policydb_t *p, uint32_t src, uint32_t tgt,
                          const char *cls_name, const char *const *perms,
                          size_t perm_count, int *changed)
{
    uint32_t cls = class_id(p, cls_name);
    uint32_t mask;

    if (cls == 0)
        return 0;
    mask = perm_mask(p, cls, perms, perm_count);
    return add_allow(p, src, tgt, cls, mask, changed);
}

/* Add or replace one `type_transition` rule. The kernel reads the transition
 * datum as the new type id (security_compute_sid: `newcontext.type =
 * avnode->datum.u.data`), so the value is a type value, not a permission mask. */
static int install_transition_rule(policydb_t *p, uint32_t src, uint32_t tgt,
                                   uint32_t cls, uint32_t new_type,
                                   int *changed)
{
    avtab_key_t key;
    avtab_datum_t datum;
    avtab_ptr_t node;

    memset(&key, 0, sizeof(key));
    key.source_type = (uint16_t)src;
    key.target_type = (uint16_t)tgt;
    key.target_class = (uint16_t)cls;
    key.specified = AVTAB_TRANSITION;

    for (node = avtab_search_node(&p->te_avtab, &key); node;
         node = avtab_search_node_next(node, key.specified)) {
        if (node->key.specified != AVTAB_TRANSITION)
            continue;
        if (node->datum.data == new_type)
            return 0;
        node->datum.data = new_type;
        *changed = 1;
        return 0;
    }
    memset(&datum, 0, sizeof(datum));
    datum.data = new_type;
    if (avtab_insert(&p->te_avtab, &key, &datum) != 0)
        return -1;
    *changed = 1;
    return 0;
}

/* Give the container the `type_transition` Android gives every app domain.
 *
 * A `memfd_create()` inode is labeled by the tmpfs mount unless a transition
 * rule says otherwise: app domains carry `type_transition <domain>
 * tmpfs:file appdomain_tmpfs`, which is the label an app is allowed to read and
 * map. A domain created at runtime has no such rule, so its pools fall back to
 * plain `tmpfs` and the app refuses them. Installing the same transition makes
 * container pools indistinguishable from app pools, with no extra access for
 * the app. Returns 1 when the transition is in place, 0 when this policy has no
 * `appdomain_tmpfs` and the caller must fall back to granting `tmpfs` access. */
static int install_shm_transition(policydb_t *p, uint32_t new_id, int *changed,
                                  char **err)
{
    type_datum_t *tmpfs = hashtab_search(p->p_types.table, "tmpfs");
    type_datum_t *app_tmpfs =
        hashtab_search(p->p_types.table, "appdomain_tmpfs");
    uint32_t cls = class_id(p, "file");

    if (!tmpfs || !app_tmpfs || app_tmpfs->flavor != TYPE_TYPE || cls == 0)
        return 0;
    if (install_transition_rule(p, new_id, tmpfs->s.value, cls,
                                app_tmpfs->s.value, changed) != 0) {
        set_error(err, "failed to add the %s tmpfs transition", FC_DOMAIN);
        return -1;
    }
    return 1;
}

static int add_allow_type_name(policydb_t *p, uint32_t src,
                               const char *tgt_name, const char *cls_name,
                               const char *const *perms, size_t perm_count,
                               int *changed)
{
    type_datum_t *td =
        hashtab_search(p->p_types.table, (hashtab_key_t)tgt_name);

    if (!td)
        return 0;
    return add_allow_name(p, src, td->s.value, cls_name, perms, perm_count,
                          changed);
}

/* Give the derived domain the base domain's place in every constraint.
 *
 * A constraint stores a *pre-expanded* set of type IDs: `t1 ==
 * mlstrustedsubject` is compiled into the attribute's current members, and the
 * kernel tests `ebitmap_get_bit(&names, type - 1)` (security/selinux/ss/
 * services.c, constraint_expr_eval). Joining the attribute afterwards changes
 * nothing for that bitmap, so a type created at runtime is never in it. A root
 * solution's own domain is patched into these sets by its policy loader for
 * exactly this reason.
 *
 * Without it every class whose constraint lists the base denies the container:
 * the rootfs carries the app's MCS categories while the container context is
 * plain s0, so even `chdir("/")` fails with EACCES and `/bin/login` reports
 * "can't open '/'". Copying the memberships keeps the derived domain under the
 * same constraints the base domain is under — no broader, no narrower. */
static int copy_constraint_memberships(policydb_t *p, uint32_t base,
                                       uint32_t new_id, int *changed,
                                       char **err)
{
    for (uint32_t cls = 1; cls <= p->p_classes.nprim; cls++) {
        class_datum_t *cd = p->class_val_to_struct[cls - 1];
        constraint_node_t *node;

        if (!cd)
            continue;
        for (node = cd->constraints; node; node = node->next) {
            constraint_expr_t *expr;

            for (expr = node->expr; expr; expr = expr->next) {
                if (expr->expr_type != CEXPR_NAMES)
                    continue;
                if (!(expr->attr & CEXPR_TYPE))
                    continue;
                if (!ebitmap_get_bit(&expr->names, base - 1))
                    continue;
                if (ebitmap_get_bit(&expr->names, new_id - 1))
                    continue;
                if (ebitmap_set_bit(&expr->names, new_id - 1, 1) != 0) {
                    set_error(err, "failed to copy a %s constraint",
                              p->p_class_val_to_name[cls - 1]);
                    return -1;
                }
                *changed = 1;
            }
        }
    }
    return 0;
}

static int build_filters(policydb_t *p, class_filter_t **out, char **err)
{
    static const char *const process_blocked[] = {
        "transition", "dyntransition", "setcurrent", "setexec",
        "setfscreate", "setkeycreate", "setsockcreate", "noatsecure",
    };
    static const char *const capability_blocked[] = {
        "sys_module", "sys_rawio", "sys_boot", "sys_tty_config", "net_admin",
    };
    static const char *const capability2_blocked[] = {
        "mac_admin",
        "mac_override",
        "checkpoint_restore",
    };
    static const char *const write_perms[] = {
        "write",        "append",       "create",   "setattr",
        "relabelfrom",  "relabelto",    "swapon",   "quotaon",
        "add_name",     "remove_name",  "reparent",
        "rmdir",        "rename",
    };
    class_filter_t *filters;
    uint32_t n = p->p_classes.nprim + 1;

    filters = calloc(n, sizeof(*filters));
    if (!filters) {
        set_error(err, "out of memory building class filters");
        return -1;
    }
    for (uint32_t cls = 1; cls <= p->p_classes.nprim; cls++) {
        const char *name = p->p_class_val_to_name[cls - 1];
        class_filter_t *f = &filters[cls];

        if (!name)
            continue;
        f->drop = is_dropped_class(name);
        f->strip_process = strcmp(name, "process") == 0;
        f->strip_caps = strcmp(name, "capability") == 0 ||
                        strcmp(name, "capability2") == 0;
        if (f->strip_process)
            f->strip = perm_mask(p, cls, process_blocked,
                                 sizeof(process_blocked) /
                                     sizeof(process_blocked[0]));
        else if (strcmp(name, "capability") == 0)
            f->strip = perm_mask(p, cls, capability_blocked,
                                 sizeof(capability_blocked) /
                                     sizeof(capability_blocked[0]));
        else if (strcmp(name, "capability2") == 0)
            f->strip = perm_mask(p, cls, capability2_blocked,
                                 sizeof(capability2_blocked) /
                                     sizeof(capability2_blocked[0]));
        else if (strcmp(name, "security") == 0) {
            /* Read-only selinuxfs: keep read_policy, drop load_policy and every
             * set or get permission the base domain may carry. */
            static const char *const read_only[] = { "read_policy" };
            f->strip = ~perm_mask(p, cls, read_only,
                                  sizeof(read_only) / sizeof(read_only[0]));
        } else if (strcmp(name, "system") == 0) {
            /* dmesg read only. module_load/module_request and the syslog
             * modifiers stay off. */
            static const char *const syslog_read[] = { "syslog_read" };
            f->strip = ~perm_mask(p, cls, syslog_read,
                                  sizeof(syslog_read) / sizeof(syslog_read[0]));
        }
        f->write_strip = perm_mask(p, cls, write_perms,
                                   sizeof(write_perms) / sizeof(write_perms[0]));
    }
    *out = filters;
    return 0;
}

/* Extended permissions (ioctl/netlink allowlists) may repeat the same key with
 * different drivers, so the entry is matched by driver and its perms are OR'd.
 * Re-running the injection must not append a second node: the kernel policy
 * grows on every load and the duplicate rule is never merged back. */
static int add_xperm(policydb_t *p, uint32_t src, uint32_t tgt, uint32_t cls,
                     const avtab_extended_perms_t *xperms, uint32_t data,
                     int *changed)
{
    avtab_key_t key;
    avtab_datum_t datum;
    avtab_ptr_t node;

    memset(&key, 0, sizeof(key));
    key.source_type = (uint16_t)src;
    key.target_type = (uint16_t)tgt;
    key.target_class = (uint16_t)cls;
    key.specified = AVTAB_XPERMS_ALLOWED;

    for (node = avtab_search_node(&p->te_avtab, &key); node;
         node = avtab_search_node_next(node, key.specified)) {
        avtab_extended_perms_t *existing = node->datum.xperms;
        int differs = 0;

        if (!existing || existing->driver != xperms->driver ||
            existing->specified != xperms->specified)
            continue;
        for (size_t i = 0; i < sizeof(existing->perms) /
                                    sizeof(existing->perms[0]);
             i++) {
            if ((existing->perms[i] | xperms->perms[i]) != existing->perms[i]) {
                differs = 1;
                break;
            }
        }
        if (!differs) {
            if ((node->datum.data | data) == node->datum.data)
                return 0;
            node->datum.data |= data;
            *changed = 1;
            return 0;
        }
        for (size_t i = 0;
             i < sizeof(existing->perms) / sizeof(existing->perms[0]); i++)
            existing->perms[i] |= xperms->perms[i];
        node->datum.data |= data;
        *changed = 1;
        return 0;
    }

    memset(&datum, 0, sizeof(datum));
    datum.data = data;
    datum.xperms = (avtab_extended_perms_t *)xperms;
    if (avtab_insert(&p->te_avtab, &key, &datum) != 0)
        return -1;
    *changed = 1;
    return 0;
}

static int copy_rules(policydb_t *p, uint32_t base, uint32_t new_id,
                      const class_filter_t *filters, int *changed, char **err)
{
    for (uint32_t slot = 0; slot < p->te_avtab.nslot; slot++) {
        for (avtab_ptr_t node = p->te_avtab.htable[slot]; node;
             node = node->next) {
            uint32_t cls = node->key.target_class;
            uint32_t tgt = node->key.target_type;
            uint32_t perms;
            const class_filter_t *f;
            const char *target_name;
            int xperms;

            if (node->key.source_type != (uint16_t)base)
                continue;
            xperms = (node->key.specified & AVTAB_XPERMS_ALLOWED) != 0;
            if (!xperms && !(node->key.specified & AVTAB_ALLOWED))
                continue; /* transitions and audit rules are not copied */
            if (cls == 0 || cls > p->p_classes.nprim)
                continue;
            f = &filters[cls];
            if (f->drop)
                continue;

            if (tgt == base)
                tgt = new_id;

            target_name = tgt <= p->p_types.nprim ? p->p_type_val_to_name[tgt - 1]
                                                  : NULL;
            if (target_name && is_dropped_target(target_name))
                continue;

            if (xperms) {
                /* ioctl/netlink allowlists: copy as-is. Dropping them would
                 * deny every ioctl on the terminal and device nodes. */
                if (target_name && is_restricted_target(target_name))
                    continue;
                if (add_xperm(p, new_id, tgt, cls, node->datum.xperms,
                              node->datum.data, changed) != 0) {
                    set_error(err, "failed to copy extended rule %s:%s %s",
                              FC_DOMAIN, p->p_class_val_to_name[cls - 1],
                              target_name ? target_name : "?");
                    return -1;
                }
                continue;
            }

            perms = node->datum.data;
            if (f->strip)
                perms &= ~f->strip;
            if (perms == 0)
                continue;
            if (target_name && is_restricted_target(target_name))
                perms &= ~f->write_strip;
            if (perms == 0)
                continue;

            if (add_allow(p, new_id, tgt, cls, perms, changed) != 0) {
                set_error(err, "failed to copy rule %s:%s %s", FC_DOMAIN,
                          p->p_class_val_to_name[cls - 1],
                          target_name ? target_name : "?");
                return -1;
            }
        }
    }
    return 0;
}

/* Mounting inside the private mount namespace. The base domain only carries
 * rules for the filesystems it mounts itself, so the common container
 * filesystems are granted explicitly. Content access stays gated by the
 * file-type rules copied from the base. */
static int install_mounts(policydb_t *p, uint32_t type, int *changed,
                          char **err)
{
    static const char *const fs_perms[] = {
        "mount", "remount", "unmount",
    };
    static const char *const fs_types[] = {
        "tmpfs", "proc", "sysfs", "devpts", "cgroup",
        "cgroup2", "mqueue", "ramfs", "overlay",
    };
    static const char *const mounton[] = {
        "mounton",
    };
    static const char *const mounton_types[] = {
        "sysfs_t", "proc_t", "tmpfs_t", "devpts_t",
    };

    for (size_t i = 0; i < sizeof(fs_types) / sizeof(fs_types[0]); i++) {
        if (add_allow_type_name(p, type, fs_types[i], "filesystem", fs_perms,
                                sizeof(fs_perms) / sizeof(fs_perms[0]),
                                changed) != 0) {
            set_error(err, "failed to grant mount on %s", fs_types[i]);
            return -1;
        }
    }
    for (size_t i = 0;
         i < sizeof(mounton_types) / sizeof(mounton_types[0]); i++) {
        if (add_allow_type_name(p, type, mounton_types[i], "dir", mounton,
                                sizeof(mounton) / sizeof(mounton[0]),
                                changed) != 0) {
            set_error(err, "failed to grant mounton on %s", mounton_types[i]);
            return -1;
        }
    }
    return 0;
}

static int install_transition(policydb_t *p, uint32_t base, uint32_t new_id,
                              const char *root_label, int *changed,
                              char **err)
{
    static const char *const transition[] = { "transition" };
    static const char *const nnp[] = { "nnp_transition" };
    static const char *const nosuid[] = { "nosuid_transition" };
    static const char *const file_perms[] = {
        "read", "open", "execute", "execute_no_trans", "entrypoint", "getattr",
        "map",
    };
    static const char *const lnk_perms[] = {
        "read", "getattr",
    };
    static const char *const dir_perms[] = {
        "search", "read", "open", "getattr",
    };
    static const char *const ptrace_perms[] = {
        "read", "write", "trace",
    };
    static const char *const process_ptrace[] = {
        "ptrace",
    };
    static const char *const ro_file[] = {
        "read", "open", "getattr",
    };
    static const char *const ro_dir[] = {
        "search", "read", "getattr",
    };
    static const char *const read_policy[] = {
        "read_policy",
    };
    static const char *const syslog_read[] = {
        "syslog_read",
    };
    type_datum_t *root_td;
    uint32_t root;

    if (add_allow_name(p, base, new_id, "process", transition,
                       sizeof(transition) / sizeof(transition[0]),
                       changed) != 0) {
        set_error(err, "failed to add the container transition");
        return -1;
    }
    /* no_new_privs and nosuid mounts add their own transition checks on newer
     * kernels; grant them when the policy has the class. */
    add_allow_name(p, base, new_id, "process2", nnp,
                   sizeof(nnp) / sizeof(nnp[0]), changed);
    add_allow_name(p, base, new_id, "process2", nosuid,
                   sizeof(nosuid) / sizeof(nosuid[0]), changed);

    /* Same-domain interaction. Every container process carries this one type,
     * so these rules are mutual: any container process may signal, inspect and
     * trace any other. The base copy above normally already covers this
     * through the domain attributes; the explicit rules keep it true for a
     * base whose self access is narrower than its attribute set. */
    if (add_allow_name(p, new_id, new_id, "ptrace", ptrace_perms,
                       sizeof(ptrace_perms) / sizeof(ptrace_perms[0]),
                       changed) != 0 ||
        add_allow_name(p, new_id, new_id, "process", process_ptrace,
                       sizeof(process_ptrace) / sizeof(process_ptrace[0]),
                       changed) != 0) {
        set_error(err, "failed to add same-domain access");
        return -1;
    }

    /* Read-only selinuxfs: the container may inspect the policy and the
     * enforcement state, never load or change it. The security class copy
     * above keeps only read_policy; these rules grant the file access that
     * read_policy alone does not cover. */
    if (add_allow_type_name(p, new_id, "security_t", "file", ro_file,
                            sizeof(ro_file) / sizeof(ro_file[0]),
                            changed) != 0 ||
        add_allow_type_name(p, new_id, "security_t", "dir", ro_dir,
                            sizeof(ro_dir) / sizeof(ro_dir[0]), changed) != 0 ||
        add_allow_type_name(p, new_id, "security", "security", read_policy,
                            sizeof(read_policy) / sizeof(read_policy[0]),
                            changed) != 0) {
        set_error(err, "failed to add read-only selinuxfs access");
        return -1;
    }

    /* dmesg: the capability is not stripped; this is the class half of the
     * check (target is the kernel SID, not a "system" type). */
    if (add_allow_type_name(p, new_id, "kernel", "system", syslog_read,
                            sizeof(syslog_read) / sizeof(syslog_read[0]),
                            changed) != 0) {
        set_error(err, "failed to add syslog read access");
        return -1;
    }

    if (!root_label)
        return 0;
    root_td = hashtab_search(p->p_types.table, (hashtab_key_t)root_label);
    if (!root_td) {
        set_error(err, "root label %s not found in the policy", root_label);
        return -1;
    }
    root = root_td->s.value;
    /* The entry point itself, plus the symlink and directory lookups every
     * /bin/login style path needs (a busybox link is the same label).
     * `entrypoint` covers the first exec, which transitions from the daemon's
     * domain into this one; every later exec (`login` starting the user's
     * shell, a shell starting a command) is a same-domain exec and the kernel
     * checks `execute_no_trans` instead of `entrypoint`. */
    if (add_allow_name(p, new_id, root, "file", file_perms,
                       sizeof(file_perms) / sizeof(file_perms[0]),
                       changed) != 0 ||
        add_allow_name(p, new_id, root, "lnk_file", lnk_perms,
                       sizeof(lnk_perms) / sizeof(lnk_perms[0]),
                       changed) != 0) {
        set_error(err, "failed to add entrypoint access for %s", root_label);
        return -1;
    }
    add_allow_name(p, new_id, root, "dir", dir_perms,
                   sizeof(dir_perms) / sizeof(dir_perms[0]), changed);
    return 0;
}

/* Kernel image layout: u32 magic, u32 string length, string, u32 version. */
static int image_version(const unsigned char *data, size_t len, int *vers)
{
    if (len < 12)
        return -1;
    uint32_t str_len;
    memcpy(&str_len, data + 4, 4);
    size_t off = 8 + (size_t)str_len;
    if (off + 4 > len)
        return -1;
    uint32_t value;
    memcpy(&value, data + off, 4);
    if (value == 0 || value > 255)
        return -1;
    *vers = (int)value;
    return 0;
}

FC_API int file_container_ensure(const char *root_context,
                                 const char *app_context, char **err)
{
    char *image = NULL;
    size_t image_len = 0;
    sepol_policydb_t *db = NULL;
    policydb_t *p;
    char *user = NULL;
    char *role = NULL;
    char *base_name = NULL;
    char *rest = NULL;
    char *root_copy = NULL;
    char *app_copy = NULL;
    char *root_user = NULL;
    char *root_role = NULL;
    char *root_type = NULL;
    char *root_level = NULL;
    char *app_user = NULL;
    char *app_role = NULL;
    char *app_type = NULL;
    char *app_level = NULL;
    const char *level;
    type_datum_t *app_datum = NULL;
    type_datum_t *base_datum;
    type_datum_t *existing;
    uint32_t base;
    uint32_t new_id;
    class_filter_t *filters = NULL;
    int rc;
    int kernel_vers = 0;
    int image_vers = 0;
    int changed = 0;
    int result = FC_STATUS_ERROR;

    if (err)
        *err = NULL;

    /* Both arguments are full SELinux contexts. The rootfs context supplies
     * its type (the entry point label) and its level; the app context supplies
     * the type that must be able to receive container-created descriptors. */
    if (root_context) {
        root_copy = strdup(root_context);
        if (!root_copy || split_context(root_copy, &root_user, &root_role,
                                        &root_type, &root_level) != 0) {
            set_error(err, "malformed rootfs context: %s", root_context);
            free(root_copy);
            return FC_STATUS_ERROR;
        }
    }
    if (app_context) {
        app_copy = strdup(app_context);
        if (!app_copy || split_context(app_copy, &app_user, &app_role,
                                       &app_type, &app_level) != 0) {
            set_error(err, "malformed app context: %s", app_context);
            free(root_copy);
            free(app_copy);
            return FC_STATUS_ERROR;
        }
    }
    /* The receiver's categories are what the created inodes must carry; the
     * rootfs's own level is the second choice, the daemon's the last. */
    level = app_level ? app_level : root_level;

    rc = read_file(FC_POLICY_PATH, &image, &image_len, err);
    if (rc == FC_NO_POLICY || rc == -ENOENT) {
        set_error(err, "SELinux policy is not available");
        free(root_copy);
        free(app_copy);
        return FC_STATUS_DISABLED;
    }
    if (rc != 0) {
        free(root_copy);
        free(app_copy);
        return FC_STATUS_ERROR;
    }

    rc = read_policyvers(&kernel_vers, err);
    if (rc != 0) {
        free(image);
        free(root_copy);
        free(app_copy);
        return FC_STATUS_ERROR;
    }
    if (kernel_vers < FC_MIN_POLICYVERS) {
        set_error(err, "kernel supports policy version %d, need at least %d",
                  kernel_vers, FC_MIN_POLICYVERS);
        free(image);
        free(root_copy);
        free(app_copy);
        return FC_STATUS_DISABLED;
    }

    if (sepol_policydb_create(&db) != 0) {
        set_error(err, "failed to create a policy database");
        free(image);
        free(root_copy);
        free(app_copy);
        return FC_STATUS_ERROR;
    }
    if (sepol_policydb_from_image(NULL, image, image_len, db) != 0) {
        set_error(err, "failed to parse the running policy");
        goto out;
    }
    p = &db->p;
    if (p->policy_type != POLICY_KERN) {
        set_error(err, "running policy is not a kernel policy");
        goto out;
    }
    if (image_version((const unsigned char *)image, image_len, &image_vers) !=
            0 ||
        (int)p->policyvers != image_vers) {
        set_error(err, "policy image version does not match the parsed policy");
        goto out;
    }
    if (image_vers < FC_MIN_POLICYVERS) {
        set_error(err, "policy version %d predates type attributes",
                  image_vers);
        result = FC_STATUS_DISABLED;
        goto out;
    }
    if (image_vers > kernel_vers) {
        set_error(err, "policy version %d exceeds the kernel maximum %d",
                  image_vers, kernel_vers);
        goto out;
    }

    rc = current_type(&base_name, &role, &user, &rest, err);
    if (rc != 0) {
        goto out;
    }
    base_datum = hashtab_search(p->p_types.table, (hashtab_key_t)base_name);
    if (!base_datum || base_datum->flavor == TYPE_ATTRIB) {
        set_error(err, "daemon domain %s is not a type", base_name);
        goto out;
    }
    base = base_datum->s.value;

    /* The domain is shared by every container and the rules it needs depend on
     * the caller, not on the policy version: each launch re-derives them for
     * the domain it runs from and the rootfs it was given (a different root
     * solution or a second rootfs label must still reach its entry point).
     * Only a real change is written back. */
    existing = hashtab_search(p->p_types.table, (hashtab_key_t)FC_DOMAIN);
    if (existing && existing->flavor != TYPE_TYPE) {
        set_error(err, "%s is not a type", FC_DOMAIN);
        goto out;
    }
    if (!existing) {
        if (add_type(p, FC_DOMAIN, &new_id, &changed, err) != 0)
            goto out;
    } else {
        new_id = existing->s.value;
    }

    if (copy_attributes(p, base, new_id, &changed, err) != 0)
        goto out;
    if (copy_constraint_memberships(p, base, new_id, &changed, err) != 0)
        goto out;
    if (add_membership(p, new_id, "domain", &changed, err) != 0)
        goto out;
    if (add_role_type(p, role, new_id, &changed, err) != 0)
        goto out;
    if (build_filters(p, &filters, err) != 0)
        goto out;
    if (copy_rules(p, base, new_id, filters, &changed, err) != 0)
        goto out;
    if (install_transition(p, base, new_id, root_type, &changed, err) != 0)
        goto out;
    if (install_mounts(p, new_id, &changed, err) != 0)
        goto out;

    /* The app must be able to receive what a container process creates.
     *
     * `security_file_receive` runs two checks on a received descriptor:
     * `fd { use }` against the label saved when the file was created (the
     * creator's context, so `file_container`) and the inode permissions
     * against the inode's label. The inode keeps the label Android's app
     * transition gives it (`appdomain_tmpfs`), which the app already reads and
     * maps; only the `fd use` half needs a rule of its own. Without both the
     * kernel silently drops the descriptor — the payload arrives and the client
     * only sees `wl_shm#4.create_pool` fail. */
    if (app_type) {
        static const char *const shm_file_perms[] = {
            "read", "write", "append", "getattr", "map", "lock",
        };
        static const char *const fd_use[] = { "use" };

        app_datum = hashtab_search(p->p_types.table, (hashtab_key_t)app_type);
        if (app_datum && app_datum->flavor == TYPE_TYPE) {
            int labeled = install_shm_transition(p, new_id, &changed, err);

            if (labeled < 0)
                goto out;
            if (add_allow_name(p, app_datum->s.value, new_id, "fd", fd_use,
                               sizeof(fd_use) / sizeof(fd_use[0]),
                               &changed) != 0) {
                set_error(err, "failed to add %s descriptor access", app_type);
                goto out;
            }
            /* No appdomain_tmpfs in this policy: keep the inode reachable by
             * granting the mount label instead. */
            if (!labeled &&
                add_allow_type_name(p, app_datum->s.value, "tmpfs", "file",
                                    shm_file_perms,
                                    sizeof(shm_file_perms) /
                                        sizeof(shm_file_perms[0]),
                                    &changed) != 0) {
                set_error(err, "failed to add %s tmpfs access", app_type);
                goto out;
            }
        }
    }

    if (!changed) {
        result = FC_STATUS_PRESENT;
        goto out;
    }

    if (policydb_validate(NULL, p) != 0) {
        set_error(err, "injected policy failed validation");
        goto out;
    }

    /* Same version in, same version out. */
    p->policyvers = (unsigned)image_vers;
    void *out_image = NULL;
    size_t out_len = 0;
    if (sepol_policydb_to_image(NULL, db, &out_image, &out_len) != 0 ||
        !out_image) {
        set_error(err, "failed to serialize the injected policy");
        goto out;
    }
    if ((int)p->policyvers != image_vers ||
        image_version(out_image, out_len, &rc) != 0 || rc != image_vers) {
        set_error(err, "serialized policy changed version");
        free(out_image);
        goto out;
    }
    rc = write_policy(out_image, out_len, err);
    free(out_image);
    if (rc != 0)
        goto out;

    result = FC_STATUS_APPLIED;

out:
    if (result == FC_STATUS_APPLIED || result == FC_STATUS_PRESENT) {
        const char *chosen = level ? level : rest;

        free(FC_EXEC_LEVEL);
        FC_EXEC_LEVEL = chosen ? strdup(chosen) : NULL;
    }
    free(filters);
    if (db)
        sepol_policydb_free(db);
    free(image);
    free(root_copy);
    free(app_copy);
    return result;
}

FC_API int file_container_context(char *buf, size_t buflen)
{
    char *context = NULL;
    size_t len = 0;
    char *user = NULL;
    char *role = NULL;
    char *type = NULL;
    char *rest = NULL;
    int n;

    if (!buf || buflen == 0)
        return -1;
    if (read_file(FC_CURRENT_PATH, &context, &len, NULL) != 0)
        return -1;
    if (len == 0 || !memchr(context, '\0', len) ||
        split_context(context, &user, &role, &type, &rest) != 0) {
        free(context);
        return -1;
    }
    if (FC_EXEC_LEVEL)
        rest = FC_EXEC_LEVEL;
    if (rest)
        n = snprintf(buf, buflen, "%s:%s:%s:%s", user, role, FC_DOMAIN, rest);
    else
        n = snprintf(buf, buflen, "%s:%s:%s", user, role, FC_DOMAIN);
    free(context);
    if (n < 0 || (size_t)n >= buflen)
        return -1;
    return 0;
}

FC_API void file_container_string_free(char *text)
{
    free(text);
}
