#ifndef VIRGL_HOST_H
#define VIRGL_HOST_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct virgl_host virgl_host;

#if defined(__GNUC__)
#define VIRGL_HOST_API __attribute__((visibility("default")))
#else
#define VIRGL_HOST_API
#endif

VIRGL_HOST_API virgl_host *virgl_host_create(void);

/*
 * Bind the vtest listener at socket_path.  Returns 0 on success; the renderer
 * itself is created lazily when the first client connects.  All connections
 * share one renderer instance (vtest multi-client mode).
 */
VIRGL_HOST_API int virgl_host_start(virgl_host *host, const char *socket_path);

/*
 * Start in forwarded-client mode: the host binds no listener.  Connected
 * client descriptors arrive through virgl_host_add_client() instead, which
 * lets a cheap Kotlin listener accept connections while the renderer library
 * is still loading.
 */
VIRGL_HOST_API int virgl_host_start_clients(virgl_host *host);

/*
 * Queue a connected client socket for the server loop.  Ownership of fd
 * transfers to the host when this returns 0; on failure the caller keeps it.
 * Safe to call from any thread after virgl_host_start_clients() succeeded.
 */
VIRGL_HOST_API int virgl_host_add_client(virgl_host *host, int fd);

/*
 * Serve every queued connection on the calling thread until
 * virgl_host_stop() is called.  This blocks for the lifetime of the service.
 */
VIRGL_HOST_API int virgl_host_run(virgl_host *host);

VIRGL_HOST_API void virgl_host_stop(virgl_host *host);

/* Call only after virgl_host_run() has returned. */
VIRGL_HOST_API void virgl_host_destroy(virgl_host *host);
VIRGL_HOST_API const char *virgl_host_error(const virgl_host *host);

#ifdef __cplusplus
}
#endif

#endif
