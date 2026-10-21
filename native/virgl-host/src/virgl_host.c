#include "virgl_host.h"

#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

#include "vtest.h"
#include "virgl_instance.h"

enum host_state { HOST_IDLE, HOST_RUNNING, HOST_DONE };

struct virgl_host {
   struct virgl_instance instance;
   atomic_int state;
   atomic_int result;
   atomic_int client_fd;
   pthread_mutex_t client_lock;
   char error[256];
};

static void host_close_client(virgl_host *host, int fd)
{
   pthread_mutex_lock(&host->client_lock);
   if (atomic_load(&host->client_fd) == fd)
      atomic_store(&host->client_fd, -1);
   close(fd);
   pthread_mutex_unlock(&host->client_lock);
}

virgl_host *virgl_host_create(void)
{
   virgl_host *host = calloc(1, sizeof(*host));
   if (!host)
      return NULL;
   atomic_init(&host->state, HOST_IDLE);
   atomic_init(&host->result, 0);
   atomic_init(&host->client_fd, -1);
   atomic_init(&host->instance.stop_requested, false);
   if (pthread_mutex_init(&host->client_lock, NULL) != 0) {
      free(host);
      return NULL;
   }
   return host;
}

int virgl_host_serve(virgl_host *host, int fd)
{
   int expected = HOST_IDLE;
   int result;

   if (!host || fd < 0) {
      if (fd >= 0)
         close(fd);
      return -EINVAL;
   }

   if (!atomic_compare_exchange_strong(&host->state, &expected, HOST_RUNNING)) {
      close(fd);
      return -EBUSY;
   }

   host->error[0] = '\0';
   pthread_mutex_lock(&host->client_lock);
   atomic_store(&host->client_fd, fd);
   pthread_mutex_unlock(&host->client_lock);

   virgl_instance_set_current(&host->instance);
   if (atomic_load(&host->instance.stop_requested)) {
      result = -ECANCELED;
   } else {
      result = vtest_serve_fd_instance(&host->instance, fd);
   }

   host_close_client(host, fd);
   atomic_store(&host->result, result);
   atomic_store(&host->state, HOST_DONE);

   if (result < 0 && result != -ECANCELED) {
      snprintf(host->error, sizeof(host->error), "vtest session failed: %d", result);
   }
   return result;
}

void virgl_host_stop(virgl_host *host)
{
   int fd;

   if (!host)
      return;

   atomic_store(&host->instance.stop_requested, true);
   pthread_mutex_lock(&host->client_lock);
   fd = atomic_load(&host->client_fd);
   if (fd >= 0)
      shutdown(fd, SHUT_RDWR);
   pthread_mutex_unlock(&host->client_lock);
}

void virgl_host_destroy(virgl_host *host)
{
   if (!host)
      return;

   virgl_host_stop(host);
   virgl_instance_set_current(&host->instance);

   vtest_server_instance_fini();
   vtest_renderer_instance_fini();
   vrend_winsys_instance_fini();
   virgl_fence_instance_fini();
   virgl_context_table_instance_fini();
   virgl_resource_table_instance_fini();
   vrend_renderer_instance_fini();
   virgl_renderer_instance_fini();

   pthread_mutex_destroy(&host->client_lock);
   free(host);
}

const char *virgl_host_error(const virgl_host *host)
{
   return host && host->error[0] ? host->error : NULL;
}
