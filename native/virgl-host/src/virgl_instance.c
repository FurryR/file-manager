#include "virgl_instance.h"

static _Thread_local struct virgl_instance *current_instance;
static struct virgl_instance fallback_instance;

struct virgl_instance *virgl_instance_current(void)
{
   return current_instance ? current_instance : &fallback_instance;
}

void virgl_instance_set_current(struct virgl_instance *instance)
{
   current_instance = instance;
}
