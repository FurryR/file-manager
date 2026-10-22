#!/usr/bin/env python3
"""Expose a no-fork, multi-client vtest server for the Android host library.

Upstream virglrenderer keeps its renderer state in process globals.  The
Android host only ever runs one renderer, so this script leaves that state
alone and adds just enough code to drive the existing multi-client server from
a single thread: an explicit start, a loop, and a stop pipe.
"""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/virgl-multi-dev").resolve()


def replace(rel, before, after, count=1):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    found = text.count(before)
    if found != count:
        raise SystemExit(f"{rel}: expected {count} occurrence(s), found {found}: {before[:80]!r}")
    path.write_text(text.replace(before, after), encoding="utf-8", newline="\n")


# ── vtest_server.c ────────────────────────────────────────────────────
replace("vtest/vtest_server.c",
        "#include <stdio.h>\n#include <signal.h>",
        "#include <stdio.h>\n#include <signal.h>\n#include <stdatomic.h>\n#include <errno.h>\n#include <pthread.h>")
replace("vtest/vtest_server.c", """   int ctx_flags;

   struct list_head new_clients;""", """   int ctx_flags;

   int stop_pipe[2];
   int client_pipe[2];
   atomic_bool stop_requested;

   struct list_head new_clients;""")
replace("vtest/vtest_server.c", """   .ctx_flags = 0,
};""", """   .ctx_flags = 0,

   .stop_pipe = { -1, -1 },
   .client_pipe = { -1, -1 },
};""")

with (ROOT / "vtest/vtest_server.c").open("a", encoding="utf-8") as out:
    out.write(r'''

static bool vtest_client_has_data(const struct vtest_client *client)
{
   char byte;
   ssize_t n;

   if (client->in_fd < 0)
      return false;

   n = recv(client->in_fd, &byte, 1, MSG_PEEK | MSG_DONTWAIT);
   return n > 0;
}

static void vtest_server_abort_start(void)
{
   if (server.socket != -1) {
      close(server.socket);
      server.socket = -1;
   }
   if (server.stop_pipe[0] >= 0) {
      close(server.stop_pipe[0]);
      server.stop_pipe[0] = -1;
   }
   if (server.stop_pipe[1] >= 0) {
      close(server.stop_pipe[1]);
      server.stop_pipe[1] = -1;
   }
   if (server.client_pipe[0] >= 0) {
      close(server.client_pipe[0]);
      server.client_pipe[0] = -1;
   }
   if (server.client_pipe[1] >= 0) {
      close(server.client_pipe[1]);
      server.client_pipe[1] = -1;
   }
   if (server.socket_name) {
      unlink(server.socket_name);
      free((void *)server.socket_name);
      server.socket_name = NULL;
   }
}

static int vtest_server_prepare(int multi_clients)
{
   int result;

   if (server.socket != -1 || server.stop_pipe[0] != -1)
      return -EBUSY;

   /* The static initializer points at a literal; only ever free strdup'ed
    * names.  Forwarded-client mode never owns a socket path. */
   server.socket_name = NULL;
   atomic_store(&server.stop_requested, false);

   server.do_fork = false;
   server.loop = true;
   server.multi_clients = multi_clients != 0;
   server.use_gles = true;
   server.use_egl_surfaceless = true;
   server.ctx_flags = VIRGL_RENDERER_USE_EGL |
                      VIRGL_RENDERER_USE_SURFACELESS |
                      VIRGL_RENDERER_USE_GLES;

   list_inithead(&server.new_clients);
   list_inithead(&server.active_clients);
   list_inithead(&server.inactive_clients);

   if (pipe(server.stop_pipe) != 0) {
      result = -errno;
      server.stop_pipe[0] = server.stop_pipe[1] = -1;
      return result;
   }
   fcntl(server.stop_pipe[0], F_SETFD, FD_CLOEXEC);
   fcntl(server.stop_pipe[1], F_SETFD, FD_CLOEXEC);
   fcntl(server.stop_pipe[0], F_SETFL,
         fcntl(server.stop_pipe[0], F_GETFL) | O_NONBLOCK);
   fcntl(server.stop_pipe[1], F_SETFL,
         fcntl(server.stop_pipe[1], F_GETFL) | O_NONBLOCK);

   if (pipe(server.client_pipe) != 0) {
      result = -errno;
      server.client_pipe[0] = server.client_pipe[1] = -1;
      vtest_server_abort_start();
      return result;
   }
   fcntl(server.client_pipe[0], F_SETFD, FD_CLOEXEC);
   fcntl(server.client_pipe[1], F_SETFD, FD_CLOEXEC);
   fcntl(server.client_pipe[0], F_SETFL,
         fcntl(server.client_pipe[0], F_GETFL) | O_NONBLOCK);
   fcntl(server.client_pipe[1], F_SETFL,
         fcntl(server.client_pipe[1], F_GETFL) | O_NONBLOCK);

   return 0;
}

int vtest_server_start_global(const char *socket_path, int multi_clients)
{
   struct sockaddr_un un;
   int result;

   if (!socket_path || !socket_path[0])
      return -EINVAL;

   result = vtest_server_prepare(multi_clients);
   if (result)
      return result;

   server.socket_name = strdup(socket_path);
   if (!server.socket_name) {
      result = -ENOMEM;
      goto out;
   }

   server.socket = socket(PF_UNIX, SOCK_STREAM, 0);
   if (server.socket < 0) {
      result = -errno;
      goto out;
   }
   fcntl(server.socket, F_SETFD, FD_CLOEXEC);

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
   if (bind(server.socket, (struct sockaddr *)&un, sizeof(un)) != 0) {
      result = -errno;
      goto out;
   }
   if (listen(server.socket, 16) != 0) {
      result = -errno;
      goto out;
   }

   return 0;

out:
   vtest_server_abort_start();
   return result;
}

int vtest_server_start_global_clients(int multi_clients)
{
   return vtest_server_prepare(multi_clients);
}

static pthread_mutex_t vtest_client_pipe_lock = PTHREAD_MUTEX_INITIALIZER;

/*
 * Hand a connected client fd to the server thread.  Kotlin and the native
 * server share one process fd table, so ownership transfers directly: on
 * success the caller must not close fd; on failure the caller keeps it.
 */
int vtest_server_add_client_fd(int fd)
{
   int value;
   int result = 0;

   if (fd < 0)
      return -EINVAL;

   pthread_mutex_lock(&vtest_client_pipe_lock);
   if (server.client_pipe[1] < 0) {
      pthread_mutex_unlock(&vtest_client_pipe_lock);
      return -EINVAL;
   }

   value = fd;
   if (write(server.client_pipe[1], &value, sizeof(value)) != sizeof(value))
      result = -errno;

   pthread_mutex_unlock(&vtest_client_pipe_lock);
   return result;
}

int vtest_server_loop_global(void)
{
   bool renderer_up = false;
   int result = 0;

   if (server.stop_pipe[0] < 0)
      return -EINVAL;

   while (!atomic_load(&server.stop_requested)) {
      struct vtest_client *client;
      fd_set read_fds;
      int max_fd = -1;
      int ret;

      FD_ZERO(&read_fds);

      LIST_FOR_EACH_ENTRY(client, &server.active_clients, head) {
         FD_SET(client->in_fd, &read_fds);
         max_fd = MAX2(client->in_fd, max_fd);
         if (client->context_poll_fd >= 0) {
            FD_SET(client->context_poll_fd, &read_fds);
            max_fd = MAX2(client->context_poll_fd, max_fd);
         }
      }

      if (server.socket >= 0 && (max_fd < 0 || server.multi_clients)) {
         FD_SET(server.socket, &read_fds);
         max_fd = MAX2(server.socket, max_fd);
      }
      FD_SET(server.stop_pipe[0], &read_fds);
      max_fd = MAX2(server.stop_pipe[0], max_fd);
      if (server.client_pipe[0] >= 0) {
         FD_SET(server.client_pipe[0], &read_fds);
         max_fd = MAX2(server.client_pipe[0], max_fd);
      }

      ret = select(max_fd + 1, &read_fds, NULL, NULL, NULL);
      if (ret < 0) {
         if (errno == EINTR)
            continue;
         result = -errno;
         break;
      }

      if (FD_ISSET(server.stop_pipe[0], &read_fds))
         break;

      if (server.client_pipe[0] >= 0 &&
          FD_ISSET(server.client_pipe[0], &read_fds)) {
         int pending[16];
         ssize_t bytes;

         while ((bytes = read(server.client_pipe[0], pending,
                              sizeof(pending))) > 0) {
            size_t count = (size_t)bytes / sizeof(pending[0]);
            size_t i;

            for (i = 0; i < count; i++) {
               fcntl(pending[i], F_SETFD, FD_CLOEXEC);
               if (vtest_server_add_client(pending[i], pending[i]) != 0)
                  close(pending[i]);
            }
            if ((size_t)bytes < sizeof(pending))
               break;
         }
      }

      if (server.socket >= 0 && FD_ISSET(server.socket, &read_fds)) {
         int fd = accept(server.socket, NULL, NULL);
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

      LIST_FOR_EACH_ENTRY(client, &server.active_clients, head) {
         if (FD_ISSET(client->in_fd, &read_fds))
            client->in_fd_ready = true;
         if (client->context_poll_fd >= 0) {
            if (FD_ISSET(client->context_poll_fd, &read_fds))
               client->context_need_poll = true;
         } else if (client->context) {
            client->context_need_poll = true;
         }
      }

      if (!renderer_up) {
         bool start_renderer = false;

         LIST_FOR_EACH_ENTRY(client, &server.active_clients, head) {
            if (client->in_fd_ready && vtest_client_has_data(client)) {
               start_renderer = true;
               break;
            }
         }

         if (start_renderer) {
            ret = vtest_init_renderer(server.multi_clients, server.ctx_flags,
                                      server.render_device);
            if (ret) {
               result = ret;
               break;
            }
            renderer_up = true;
         }
      }

      vtest_server_dispatch_clients();
      vtest_server_activate_clients();

      vtest_server_tidy_clients();

      if (renderer_up && list_is_empty(&server.active_clients)) {
         vtest_cleanup_renderer();
         renderer_up = false;
      }
   }

   vtest_server_inactivate_clients();
   vtest_server_tidy_clients();
   if (renderer_up)
      vtest_cleanup_renderer();
   if (server.socket != -1) {
      close(server.socket);
      server.socket = -1;
   }
   if (server.socket_name) {
      if (unlink(server.socket_name) != 0 && errno != ENOENT && result == 0)
         result = -errno;
      free((void *)server.socket_name);
      server.socket_name = NULL;
   }
   if (server.stop_pipe[0] >= 0) {
      close(server.stop_pipe[0]);
      server.stop_pipe[0] = -1;
   }
   if (server.stop_pipe[1] >= 0) {
      close(server.stop_pipe[1]);
      server.stop_pipe[1] = -1;
   }
   pthread_mutex_lock(&vtest_client_pipe_lock);
   if (server.client_pipe[0] >= 0) {
      int pending[16];
      ssize_t bytes;

      while ((bytes = read(server.client_pipe[0], pending,
                           sizeof(pending))) > 0) {
         size_t count = (size_t)bytes / sizeof(pending[0]);
         size_t i;

         for (i = 0; i < count; i++)
            close(pending[i]);
      }
      close(server.client_pipe[0]);
      server.client_pipe[0] = -1;
   }
   if (server.client_pipe[1] >= 0) {
      close(server.client_pipe[1]);
      server.client_pipe[1] = -1;
   }
   pthread_mutex_unlock(&vtest_client_pipe_lock);
   return result;
}

void vtest_server_stop_global(void)
{
   atomic_store(&server.stop_requested, true);
   if (server.stop_pipe[1] >= 0) {
      char byte = 1;
      ssize_t ignored = write(server.stop_pipe[1], &byte, 1);
      (void)ignored;
   }
}
''')

# ── Meson: build the host library alongside vtest ─────────────────────
replace("meson.build", "if not with_host_windows\n   subdir('vtest')\nendif",
        "if not with_host_windows\n   subdir('vtest')\n   subdir('virgl-host')\nendif")

# ── vtest.h ───────────────────────────────────────────────────────────
replace("vtest/vtest.h", "void vtest_set_max_length(uint32_t length);",
        """void vtest_set_max_length(uint32_t length);

int vtest_server_start_global(const char *socket_path, int multi_clients);
int vtest_server_start_global_clients(int multi_clients);
int vtest_server_add_client_fd(int fd);
int vtest_server_loop_global(void);
void vtest_server_stop_global(void);""")
