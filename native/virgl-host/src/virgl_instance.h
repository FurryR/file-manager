#ifndef VIRGL_INSTANCE_H
#define VIRGL_INSTANCE_H

#include <stdatomic.h>

/*
 * Per-instance renderer state.  The refactored virglrenderer components store
 * their mutable data in these slots instead of file-scope globals.  Bind an
 * instance to a worker thread before entering the renderer and keep a client's
 * commands on that thread.
 */
struct virgl_instance {
   void *state_data;
   void *vrend_state_data;
   void *context_table;
   void *resource_table;
   void *pipe_callbacks_data;
   void *vtest_renderer;
   void *vtest_server;
   void *winsys;
   void *fence;
   void *blitter;
   atomic_bool stop_requested;
};

struct virgl_instance *virgl_instance_current(void);
void virgl_instance_set_current(struct virgl_instance *instance);

void virgl_renderer_instance_fini(void);
void vrend_renderer_instance_fini(void);
void virgl_context_table_instance_fini(void);
void virgl_resource_table_instance_fini(void);
void virgl_fence_instance_fini(void);
void vtest_renderer_instance_fini(void);
void vtest_server_instance_fini(void);
void vrend_winsys_instance_fini(void);

#endif
