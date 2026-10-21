virgl_host = shared_library(
   'virglhost',
   'virgl_host.c',
   'virgl_jni.c',
   include_directories: [inc_configuration, include_directories('../vtest')],
   link_with: [libvtest],
   dependencies: [libvirglrenderer_dep, gallium_dep, thread_dep],
   install: true,
)
