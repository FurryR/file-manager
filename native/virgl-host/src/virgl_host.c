#include "virgl_host.h"

#include <errno.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "vtest.h"

enum host_state { HOST_IDLE, HOST_RUNNING, HOST_DONE };

struct virgl_host {
   atomic_int state;
   atomic_int result;
   char error[256];
};

virgl_host *virgl_host_create(void)
{
   virgl_host *host = calloc(1, sizeof(*host));
   if (!host)
      return NULL;
   atomic_init(&host->state, HOST_IDLE);
   atomic_init(&host->result, 0);
   return host;
}

int virgl_host_start(virgl_host *host, const char *socket_path)
{
   int expected = HOST_IDLE;
   int result;

   if (!host || !socket_path)
      return -EINVAL;

   if (!atomic_compare_exchange_strong(&host->state, &expected, HOST_RUNNING))
      return -EBUSY;

   host->error[0] = '\0';
   result = vtest_server_start_global(socket_path, 1);
   if (result < 0) {
      snprintf(host->error, sizeof(host->error), "vtest start failed: %d", result);
      atomic_store(&host->result, result);
      atomic_store(&host->state, HOST_DONE);
   }
   return result;
}

int virgl_host_start_clients(virgl_host *host)
{
   int expected = HOST_IDLE;
   int result;

   if (!host)
      return -EINVAL;

   if (!atomic_compare_exchange_strong(&host->state, &expected, HOST_RUNNING))
      return -EBUSY;

   host->error[0] = '\0';
   result = vtest_server_start_global_clients(1);
   if (result < 0) {
      snprintf(host->error, sizeof(host->error), "vtest start failed: %d", result);
      atomic_store(&host->result, result);
      atomic_store(&host->state, HOST_DONE);
   }
   return result;
}

int virgl_host_add_client(virgl_host *host, int fd)
{
   if (!host || fd < 0)
      return -EINVAL;

   if (atomic_load(&host->state) != HOST_RUNNING)
      return -EINVAL;

   return vtest_server_add_client_fd(fd);
}

int virgl_host_run(virgl_host *host)
{
   int result;

   if (!host)
      return -EINVAL;

   if (atomic_load(&host->state) != HOST_RUNNING)
      return -EINVAL;

   result = vtest_server_loop_global();

   atomic_store(&host->result, result);
   atomic_store(&host->state, HOST_DONE);

   if (result < 0)
      snprintf(host->error, sizeof(host->error), "vtest server failed: %d", result);
   return result;
}

void virgl_host_stop(virgl_host *host)
{
   if (!host)
      return;

   vtest_server_stop_global();
}

void virgl_host_destroy(virgl_host *host)
{
   if (!host)
      return;

   vtest_server_stop_global();
   free(host);
}

const char *virgl_host_error(const virgl_host *host)
{
   return host && host->error[0] ? host->error : NULL;
}
