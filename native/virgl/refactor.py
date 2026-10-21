#!/usr/bin/env python3
"""Apply the per-instance refactor to a virglrenderer 1.3.0 source tree.

This is a development aid; the real build script will call it after applying
the Android compatibility patches.
"""
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/virgl-instance-dev").resolve()


def replace(rel, before, after, count=1):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    found = text.count(before)
    if found != count:
        raise SystemExit(f"{rel}: expected {count} occurrence(s), found {found}: {before[:80]!r}")
    path.write_text(text.replace(before, after), encoding="utf-8", newline="\n")


# ── virglrenderer.c ───────────────────────────────────────────────────
replace("src/virglrenderer.c", "#include <stdio.h>\n#include <time.h>",
        "#include <stdio.h>\n#include <stdlib.h>\n#include <time.h>")
replace("src/virglrenderer.c", '#include "virgl_util.h"',
        '#include "virgl_util.h"\n#include "virgl_instance.h"')
replace("src/virglrenderer.c", "static struct global_state state;", """static struct global_state *state_ptr(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->state_data)
      instance->state_data = calloc(1, sizeof(struct global_state));
   return instance->state_data;
}
#define state (*state_ptr())""")
replace("src/virglrenderer.c", "   memset(&state, 0, sizeof(state));",
        "   memset(state_ptr(), 0, sizeof(*state_ptr()));")
replace("src/virglrenderer.c", "void virgl_renderer_cleanup(UNUSED void *cookie)", """void
virgl_renderer_instance_fini(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   free(instance->state_data);
   instance->state_data = NULL;
}

void virgl_renderer_cleanup(UNUSED void *cookie)""")

# ── virgl_context.c ───────────────────────────────────────────────────
replace("src/virgl_context.c", '#include "virgl_util.h"',
        '#include "virgl_util.h"\n#include "virgl_instance.h"')
replace("src/virgl_context.c", "static struct util_hash_table *virgl_context_table;", """static struct util_hash_table **context_table_slot(void)
{
   return (struct util_hash_table **)&virgl_instance_current()->context_table;
}
#define virgl_context_table (*context_table_slot())

void
virgl_context_table_instance_fini(void)
{
   struct util_hash_table **slot = context_table_slot();
   if (*slot) {
      util_hash_table_destroy(*slot);
      *slot = NULL;
   }
}""")

# ── virgl_resource.c ──────────────────────────────────────────────────
replace("src/virgl_resource.c", "#include <string.h>\n#include <unistd.h>",
        "#include <stdlib.h>\n#include <string.h>\n#include <unistd.h>")
replace("src/virgl_resource.c", '#include "virgl_context.h"',
        '#include "virgl_context.h"\n#include "virgl_instance.h"')
replace("src/virgl_resource.c", """static struct util_hash_table *virgl_resource_table;
static struct virgl_resource_pipe_callbacks pipe_callbacks;""", """static struct util_hash_table **resource_table_slot(void)
{
   return (struct util_hash_table **)&virgl_instance_current()->resource_table;
}
#define virgl_resource_table (*resource_table_slot())

static struct virgl_resource_pipe_callbacks *pipe_callbacks_ptr(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->pipe_callbacks_data)
      instance->pipe_callbacks_data = calloc(1, sizeof(struct virgl_resource_pipe_callbacks));
   return instance->pipe_callbacks_data;
}
#define pipe_callbacks (*pipe_callbacks_ptr())

void
virgl_resource_table_instance_fini(void)
{
   struct util_hash_table **slot = resource_table_slot();
   struct virgl_instance *instance = virgl_instance_current();
   if (*slot) {
      util_hash_table_destroy(*slot);
      *slot = NULL;
   }
   free(instance->pipe_callbacks_data);
   instance->pipe_callbacks_data = NULL;
}""")

# ── virgl_fence.c ─────────────────────────────────────────────────────
replace("src/virgl_fence.c", '#include "virgl_util.h"',
        '#include "virgl_util.h"\n#include "virgl_instance.h"')
replace("src/virgl_fence.c", """static struct virgl_fence last_signalled_fence = { .fd = -1 };
static struct hash_table_u64 *virgl_fence_table;
static mtx_t virgl_fence_table_lock;""", """struct virgl_fence_state {
   struct virgl_fence last_signalled_fence;
   struct hash_table_u64 *table;
   mtx_t lock;
};

static struct virgl_fence_state *fence_state(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->fence) {
      struct virgl_fence_state *state = calloc(1, sizeof(*state));
      state->last_signalled_fence.fd = -1;
      instance->fence = state;
   }
   return instance->fence;
}

#define last_signalled_fence (fence_state()->last_signalled_fence)
#define virgl_fence_table (fence_state()->table)
#define virgl_fence_table_lock (fence_state()->lock)

void
virgl_fence_instance_fini(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->fence)
      return;
   if (fence_state()->table)
      virgl_fence_table_cleanup();
   free(instance->fence);
   instance->fence = NULL;
}""")

# ── vrend_blitter.c ───────────────────────────────────────────────────
replace("src/vrend/vrend_blitter.c", '#include "vrend_blitter.h"',
        '#include "vrend_blitter.h"\n#include "../virgl_instance.h"\n\n#include <stdlib.h>')
replace("src/vrend/vrend_blitter.c", "static struct vrend_blitter_ctx vrend_blit_ctx;", """static struct vrend_blitter_ctx *vrend_blit_ctx_ptr(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->blitter)
      instance->blitter = calloc(1, sizeof(struct vrend_blitter_ctx));
   return instance->blitter;
}
#define vrend_blit_ctx (*vrend_blit_ctx_ptr())""")
replace("src/vrend/vrend_blitter.c", "   vrend_blit_ctx.blit_programs = _mesa_hash_table_u64_create(NULL);",
        "   blit_ctx->blit_programs = _mesa_hash_table_u64_create(NULL);")
replace("src/vrend/vrend_blitter.c", "   struct vrend_blitter_ctx *blit_ctx = &vrend_blit_ctx;",
        "   struct vrend_blitter_ctx *blit_ctx = vrend_blit_ctx_ptr();")
replace("src/vrend/vrend_blitter.c", """void vrend_blitter_fini(void)
{
   vrend_blit_ctx.initialised = false;
   if (vrend_blit_ctx.blit_programs)
      _mesa_hash_table_u64_destroy(vrend_blit_ctx.blit_programs, delete_program_cb);
   vrend_clicbs->destroy_gl_context(vrend_blit_ctx.gl_context);
   memset(&vrend_blit_ctx, 0, sizeof(vrend_blit_ctx));
}""", """void vrend_blitter_fini(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   struct vrend_blitter_ctx *blit_ctx = instance->blitter;

   if (!blit_ctx)
      return;

   blit_ctx->initialised = false;
   if (blit_ctx->blit_programs)
      _mesa_hash_table_u64_destroy(blit_ctx->blit_programs, delete_program_cb);
   if (blit_ctx->gl_context)
      vrend_clicbs->destroy_gl_context(blit_ctx->gl_context);
   free(blit_ctx);
   instance->blitter = NULL;
}""")

# ── vrend_renderer.c ──────────────────────────────────────────────────
replace("src/vrend/vrend_renderer.c", "struct global_renderer_state {", """struct vrend_format_table;

struct global_renderer_state {""")
replace("src/vrend/vrend_renderer.c", """   bool d3d_share_texture : 1;
   bool gbm_layout_feat : 1;
};""", """   bool d3d_share_texture : 1;
   bool gbm_layout_feat : 1;

   struct vrend_format_table *format_table;
};""")
replace("src/vrend/vrend_renderer.c", "static struct global_renderer_state vrend_state;", """static struct global_renderer_state *vrend_state_ptr(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->vrend_state_data)
      instance->vrend_state_data = calloc(1, sizeof(struct global_renderer_state));
   return instance->vrend_state_data;
}
#define vrend_state (*vrend_state_ptr())""")
replace("src/vrend/vrend_renderer.c", "static struct vrend_format_table tex_conv_table[VIRGL_FORMAT_MAX_EXTENDED] =  {0};", """static struct vrend_format_table *tex_conv_table_ptr(void)
{
   struct global_renderer_state *state = vrend_state_ptr();
   if (!state->format_table)
      state->format_table = calloc(VIRGL_FORMAT_MAX_EXTENDED,
                                     sizeof(struct vrend_format_table));
   return state->format_table;
}
#define tex_conv_table tex_conv_table_ptr()""")
replace("src/vrend/vrend_renderer.c", "if (unlikely(format >= ARRAY_SIZE(tex_conv_table))) {",
        "if (unlikely(format >= VIRGL_FORMAT_MAX_EXTENDED)) {")
replace("src/vrend/vrend_renderer.c", "int vrend_renderer_init(const struct vrend_if_cbs *cbs, uint32_t flags)", """void
vrend_renderer_instance_fini(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (instance->vrend_state_data) {
      free(((struct global_renderer_state *)instance->vrend_state_data)->format_table);
      free(instance->vrend_state_data);
      instance->vrend_state_data = NULL;
   }
}

int vrend_renderer_init(const struct vrend_if_cbs *cbs, uint32_t flags)""")
replace("src/vrend/vrend_renderer.c", '#include "vrend_renderer.h"',
        '#include "vrend_renderer.h"\n#include "../virgl_instance.h"')
replace("src/vrend/vrend_renderer.c", '#include "vrend_winsys.h"',
        '#include "vrend_winsys.h"\n#define egl vrend_winsys_get_egl()\n#define gbm vrend_winsys_get_gbm()')

# The threaded fence waiter is spawned by the renderer, so it must inherit the
# instance explicitly; _Thread_local storage does not cross pthread_create.
replace("src/vrend/vrend_renderer.c",
        "static int thread_sync(UNUSED void *arg)\n{\n   virgl_gl_context gl_context = vrend_state.sync_context;",
        """static int thread_sync(void *arg)
{
   struct virgl_instance *instance = arg;
   virgl_gl_context gl_context;

   virgl_instance_set_current(instance);
   gl_context = vrend_state.sync_context;""")
replace("src/vrend/vrend_renderer.c",
        "vrend_state.sync_thread = u_thread_create(thread_sync, NULL);",
        "vrend_state.sync_thread = u_thread_create(thread_sync, virgl_instance_current());")

# ── vtest_renderer.c ──────────────────────────────────────────────────
replace("vtest/vtest_renderer.c", '#include "vtest.h"',
        '#include "vtest.h"\n#include "../src/virgl_instance.h"')
replace("vtest/vtest_renderer.c", """static struct vtest_renderer renderer = {
   .max_length = UINT_MAX,
   .next_context_id = 1,
   .next_resource_id = 1,
   .next_sync_id = 1,
};""", """static struct vtest_renderer *vtest_renderer_ptr(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->vtest_renderer) {
      struct vtest_renderer *renderer = calloc(1, sizeof(*renderer));
      renderer->max_length = UINT_MAX;
      renderer->next_context_id = 1;
      renderer->next_resource_id = 1;
      renderer->next_sync_id = 1;
      instance->vtest_renderer = renderer;
   }
   return instance->vtest_renderer;
}
#define g_renderer (*vtest_renderer_ptr())

void
vtest_renderer_instance_fini(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   free(instance->vtest_renderer);
   instance->vtest_renderer = NULL;
}""")
replace("vtest/vtest_renderer.c", "virgl_renderer_init(&renderer,", "virgl_renderer_init(vtest_renderer_ptr(),")
replace("vtest/vtest_renderer.c", "virgl_renderer_cleanup(&renderer)", "virgl_renderer_cleanup(vtest_renderer_ptr())")
replace("vtest/vtest_renderer.c", "vtest_create_implicit_fence(&renderer)", "vtest_create_implicit_fence(vtest_renderer_ptr())")
text = (ROOT / "vtest/vtest_renderer.c").read_text(encoding="utf-8")
text = re.sub(r"\brenderer\.", "g_renderer.", text)
text = text.replace("failed to initialise g_renderer.", "failed to initialise renderer.")
(ROOT / "vtest/vtest_renderer.c").write_text(text, encoding="utf-8", newline="\n")

# ── vtest_server.c ────────────────────────────────────────────────────
replace("vtest/vtest_server.c", '#include "vtest_server.h"',
        '#include "vtest_server.h"\n#include "../src/virgl_instance.h"')
replace("vtest/vtest_server.c", """   int ctx_flags;

   struct list_head new_clients;""", """   int ctx_flags;

   int stop_pipe[2];

   struct list_head new_clients;""")
replace("vtest/vtest_server.c", """struct vtest_server server = {
   .socket_name = VTEST_DEFAULT_SOCKET_NAME,
   .socket = -1,

   .read_file = NULL,

   .render_device = 0,

   .main_server = true,
   .do_fork = true,
   .loop = true,
   .multi_clients = false,

   .ctx_flags = 0,
};""", """static struct vtest_server *vtest_server_ptr(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->vtest_server) {
      struct vtest_server *server = calloc(1, sizeof(*server));
      server->socket_name = VTEST_DEFAULT_SOCKET_NAME;
      server->socket = -1;
      server->stop_pipe[0] = server->stop_pipe[1] = -1;
      server->main_server = true;
      server->do_fork = false;
      server->loop = true;
      server->multi_clients = true;
      instance->vtest_server = server;
   }
   return instance->vtest_server;
}
#define g_server (*vtest_server_ptr())

void
vtest_server_instance_fini(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   free(instance->vtest_server);
   instance->vtest_server = NULL;
}""")
text = (ROOT / "vtest/vtest_server.c").read_text(encoding="utf-8")
text = re.sub(r"\bserver\.", "g_server.", text)
(ROOT / "vtest/vtest_server.c").write_text(text, encoding="utf-8", newline="\n")

# ── vrend_winsys.c ────────────────────────────────────────────────────
replace("src/vrend/vrend_winsys.c", '#include "vrend_winsys.h"',
        '#include "vrend_winsys.h"\n#include "../virgl_instance.h"')
replace("src/vrend/vrend_winsys.c", """static int use_context = CONTEXT_NONE;""", """struct vrend_winsys_state {
   int use_context;
   struct virgl_egl *egl;
   struct virgl_gbm *gbm;
   struct virgl_glx *glx_info;
};

static struct vrend_winsys_state *winsys_ptr(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   if (!instance->winsys) {
      struct vrend_winsys_state *winsys = calloc(1, sizeof(*winsys));
      winsys->use_context = CONTEXT_NONE;
      instance->winsys = winsys;
   }
   return instance->winsys;
}

struct virgl_egl *vrend_winsys_get_egl(void)
{
   return winsys_ptr()->egl;
}

struct virgl_gbm *vrend_winsys_get_gbm(void)
{
   return winsys_ptr()->gbm;
}

#define use_context (winsys_ptr()->use_context)
#define egl (winsys_ptr()->egl)
#define gbm (winsys_ptr()->gbm)
#define glx_info (winsys_ptr()->glx_info)

void
vrend_winsys_instance_fini(void)
{
   struct virgl_instance *instance = virgl_instance_current();
   free(instance->winsys);
   instance->winsys = NULL;
}""")
# Remove the old global declarations that are now fields/macros.
replace("src/vrend/vrend_winsys.c", "#ifdef HAVE_EPOXY_EGL_H\nstruct virgl_egl *egl = NULL;\n#endif\n\n#ifdef ENABLE_GBM\nstruct virgl_gbm *gbm = NULL;\n#endif\n\n#ifdef HAVE_EPOXY_GLX_H\nstatic struct virgl_glx *glx_info = NULL;\n#endif\n\n", "")
# The Android EGL display is process-wide.  Instance initialization and
# teardown touch it, so serialize those operations across worker threads.
replace("src/vrend/vrend_winsys.c", "#include <stddef.h>",
        "#include <stddef.h>\n#include <pthread.h>\n\nstatic pthread_mutex_t vrend_winsys_lock = PTHREAD_MUTEX_INITIALIZER;")
replace("src/vrend/vrend_winsys.c", "int vrend_winsys_init(uint32_t flags, int preferred_fd)\n{",
        "static int vrend_winsys_init_unlocked(uint32_t flags, int preferred_fd)\n{")
replace("src/vrend/vrend_winsys.c", "\nvoid vrend_winsys_cleanup(void)\n{", """
int vrend_winsys_init(uint32_t flags, int preferred_fd)
{
   int ret;

   pthread_mutex_lock(&vrend_winsys_lock);
   ret = vrend_winsys_init_unlocked(flags, preferred_fd);
   pthread_mutex_unlock(&vrend_winsys_lock);
   return ret;
}

static void vrend_winsys_cleanup_unlocked(void)
{""")
replace("src/vrend/vrend_winsys.c", "\nint vrend_winsys_init_external(void *egl_display)\n{", """
void vrend_winsys_cleanup(void)
{
   pthread_mutex_lock(&vrend_winsys_lock);
   vrend_winsys_cleanup_unlocked();
   pthread_mutex_unlock(&vrend_winsys_lock);
}

static int vrend_winsys_init_external_unlocked(void *egl_display)
{""")
replace("src/vrend/vrend_winsys.c", "\nvirgl_renderer_gl_context vrend_winsys_create_context", """
int vrend_winsys_init_external(void *egl_display)
{
   int ret;

   pthread_mutex_lock(&vrend_winsys_lock);
   ret = vrend_winsys_init_external_unlocked(egl_display);
   pthread_mutex_unlock(&vrend_winsys_lock);
   return ret;
}

virgl_renderer_gl_context vrend_winsys_create_context""")

replace("src/vrend/vrend_winsys.h", "extern struct virgl_egl *egl;\nextern struct virgl_gbm *gbm;",
        "struct virgl_egl *vrend_winsys_get_egl(void);\nstruct virgl_gbm *vrend_winsys_get_gbm(void);")

# ── EGL: do not terminate the process-wide default display ────────────
replace("src/vrend/vrend_winsys_egl.c",
        "   eglTerminate(egl->egl_display);",
        "   /* The default display is shared by every instance in this process. */")

# ── Meson: build the instance shim and the host library ───────────────
replace("src/meson.build", "   'virgl_util.c',\n", "   'virgl_util.c',\n   'virgl_instance.c',\n")
replace("meson.build", "if not with_host_windows\n   subdir('vtest')\nendif",
        "if not with_host_windows\n   subdir('vtest')\n   subdir('virgl-host')\nendif")

print("refactor applied to", ROOT)

# ── vtest server: single-instance, no-fork run loop ──────────────────
replace("vtest/vtest.h", "void vtest_set_max_length(uint32_t length);",
"""void vtest_set_max_length(uint32_t length);

struct virgl_instance;
int vtest_server_run_instance(struct virgl_instance *instance,
                              const char *socket_path,
                              int multi_clients);
int vtest_serve_fd_instance(struct virgl_instance *instance, int fd);
void vtest_server_stop_instance(struct virgl_instance *instance);""")
with (ROOT / "vtest/vtest_server.c").open("a", encoding="utf-8") as out:
    out.write(r'''

int vtest_server_run_instance(struct virgl_instance *instance,
                              const char *socket_path,
                              int multi_clients)
{
   struct vtest_server *s = vtest_server_ptr();
   struct sockaddr_un un;
   bool renderer_up = false;
   int result = 0;

   virgl_instance_set_current(instance);

   if (!socket_path || !socket_path[0] || s->socket != -1)
      return -EINVAL;

   s->socket_name = socket_path;
   s->do_fork = false;
   s->loop = true;
   s->multi_clients = multi_clients != 0;
   s->use_gles = true;
   s->use_egl_surfaceless = true;
   s->ctx_flags = VIRGL_RENDERER_USE_EGL |
                  VIRGL_RENDERER_USE_SURFACELESS |
                  VIRGL_RENDERER_USE_GLES;

   list_inithead(&s->new_clients);
   list_inithead(&s->active_clients);
   list_inithead(&s->inactive_clients);

   if (pipe(s->stop_pipe) != 0) {
      result = -errno;
      s->stop_pipe[0] = s->stop_pipe[1] = -1;
      return result;
   }
   fcntl(s->stop_pipe[0], F_SETFD, FD_CLOEXEC);
   fcntl(s->stop_pipe[1], F_SETFD, FD_CLOEXEC);
   fcntl(s->stop_pipe[0], F_SETFL, fcntl(s->stop_pipe[0], F_GETFL) | O_NONBLOCK);

   s->socket = socket(PF_UNIX, SOCK_STREAM, 0);
   if (s->socket < 0) {
      result = -errno;
      goto out;
   }
   fcntl(s->socket, F_SETFD, FD_CLOEXEC);

   memset(&un, 0, sizeof(un));
   un.sun_family = AF_UNIX;
   if (strlen(socket_path) >= sizeof(un.sun_path)) {
      result = -ENAMETOOLONG;
      goto out;
   }
   snprintf(un.sun_path, sizeof(un.sun_path), "%s", socket_path);
   if (unlink(un.sun_path) != 0 && errno != ENOENT) {
      result = -errno;
      goto out;
   }
   if (bind(s->socket, (struct sockaddr *)&un, sizeof(un)) != 0) {
      result = -errno;
      goto out;
   }
   if (listen(s->socket, 16) != 0) {
      result = -errno;
      goto out;
   }

   for (;;) {
      struct vtest_client *client;
      fd_set read_fds;
      int max_fd = -1;
      bool is_empty;

      FD_ZERO(&read_fds);

      LIST_FOR_EACH_ENTRY(client, &s->active_clients, head) {
         FD_SET(client->in_fd, &read_fds);
         max_fd = MAX2(client->in_fd, max_fd);
         if (client->context_poll_fd >= 0) {
            FD_SET(client->context_poll_fd, &read_fds);
            max_fd = MAX2(client->context_poll_fd, max_fd);
         }
      }

      if (s->socket >= 0 && (max_fd < 0 || s->multi_clients)) {
         FD_SET(s->socket, &read_fds);
         max_fd = MAX2(s->socket, max_fd);
      }
      FD_SET(s->stop_pipe[0], &read_fds);
      max_fd = MAX2(s->stop_pipe[0], max_fd);

      int ret = select(max_fd + 1, &read_fds, NULL, NULL, NULL);
      if (ret < 0) {
         if (errno == EINTR)
            continue;
         result = -errno;
         break;
      }

      if (FD_ISSET(s->stop_pipe[0], &read_fds))
         break;

      if (s->socket >= 0 && FD_ISSET(s->socket, &read_fds)) {
         int fd = accept(s->socket, NULL, NULL);
         if (fd < 0) {
            if (errno == EINTR || errno == EAGAIN)
               continue;
            result = -errno;
            break;
         }
         fcntl(fd, F_SETFD, FD_CLOEXEC);
         if (vtest_server_add_client(fd, fd) != 0) {
            close(fd);
            result = -ENOMEM;
            break;
         }
      }

      LIST_FOR_EACH_ENTRY(client, &s->active_clients, head) {
         if (FD_ISSET(client->in_fd, &read_fds))
            client->in_fd_ready = true;
         if (client->context_poll_fd >= 0) {
            if (FD_ISSET(client->context_poll_fd, &read_fds))
               client->context_need_poll = true;
         } else if (client->context) {
            client->context_need_poll = true;
         }
      }

      vtest_server_dispatch_clients();
      vtest_server_activate_clients();

      is_empty = list_is_empty(&s->active_clients);
      if (!renderer_up && !is_empty) {
         ret = vtest_init_renderer(s->multi_clients, s->ctx_flags,
                                   s->render_device);
         if (ret) {
            result = ret;
            break;
         }
         renderer_up = true;
      }

      vtest_server_tidy_clients();

      if (renderer_up && list_is_empty(&s->active_clients)) {
         vtest_cleanup_renderer();
         renderer_up = false;
         if (!s->loop)
            break;
      }
   }

out:
   vtest_server_inactivate_clients();
   vtest_server_tidy_clients();
   if (renderer_up)
      vtest_cleanup_renderer();
   if (s->socket != -1) {
      close(s->socket);
      s->socket = -1;
   }
   if (unlink(socket_path) != 0 && errno != ENOENT && result == 0)
      result = -errno;
   if (s->stop_pipe[0] >= 0) {
      close(s->stop_pipe[0]);
      s->stop_pipe[0] = -1;
   }
   if (s->stop_pipe[1] >= 0) {
      close(s->stop_pipe[1]);
      s->stop_pipe[1] = -1;
   }
   return result;
}

int vtest_serve_fd_instance(struct virgl_instance *instance, int fd)
{
   struct vtest_server *s = vtest_server_ptr();
   struct vtest_client *client = NULL;
   bool renderer_up = false;
   int result = 0;

   if (!instance || fd < 0)
      return -EINVAL;

   virgl_instance_set_current(instance);

   if (s->socket != -1)
      return -EBUSY;

   s->do_fork = false;
   s->loop = false;
   s->multi_clients = false;
   s->use_gles = true;
   s->use_egl_surfaceless = true;
   s->ctx_flags = VIRGL_RENDERER_USE_EGL |
                  VIRGL_RENDERER_USE_SURFACELESS |
                  VIRGL_RENDERER_USE_GLES;

   list_inithead(&s->new_clients);
   list_inithead(&s->active_clients);
   list_inithead(&s->inactive_clients);

   if (vtest_server_add_client(fd, fd) != 0) {
      result = -ENOMEM;
      goto out;
   }
   vtest_server_activate_clients();
   client = LIST_ENTRY(struct vtest_client, s->active_clients.next, head);

   if (atomic_load(&instance->stop_requested)) {
      result = -ECANCELED;
      goto out;
   }

   result = vtest_init_renderer(false, s->ctx_flags, s->render_device);
   if (result)
      goto out;
   renderer_up = true;

   while (!atomic_load(&instance->stop_requested)) {
      fd_set read_fds;
      int max_fd = client->in_fd;
      int ret;

      FD_ZERO(&read_fds);
      FD_SET(client->in_fd, &read_fds);
      if (client->context_poll_fd >= 0) {
         FD_SET(client->context_poll_fd, &read_fds);
         max_fd = MAX2(client->context_poll_fd, max_fd);
      }

      ret = select(max_fd + 1, &read_fds, NULL, NULL, NULL);
      if (ret < 0) {
         if (errno == EINTR)
            continue;
         result = -errno;
         break;
      }

      if (client->context_poll_fd >= 0) {
         if (FD_ISSET(client->context_poll_fd, &read_fds))
            client->context_need_poll = true;
      } else if (client->context) {
         client->context_need_poll = true;
      }

      if (client->context_need_poll) {
         vtest_poll_context(client->context);
         client->context_need_poll = false;
      }

      if (FD_ISSET(client->in_fd, &read_fds)) {
         ret = vtest_client_dispatch_commands(client);
         if (ret) {
            fprintf(ret == VTEST_CLIENT_DISCONNECTED ? stdout : stderr,
                    "client: %s\n", vtest_client_result_string(ret));
            break;
         }
      }
   }

   if (result == 0 && atomic_load(&instance->stop_requested))
      result = -ECANCELED;

out:
   vtest_server_inactivate_clients();
   /* The host owns this fd and closes it after this function returns. */
   LIST_FOR_EACH_ENTRY(client, &s->inactive_clients, head) {
      client->in_fd = -1;
      client->out_fd = -1;
   }
   vtest_server_tidy_clients();
   if (renderer_up)
      vtest_cleanup_renderer();

   return result;
}

void vtest_server_stop_instance(struct virgl_instance *instance)
{
   if (instance)
      atomic_store(&instance->stop_requested, true);
}
''')
