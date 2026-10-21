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
 * Serve one accepted vtest connection on the calling thread.  This blocks
 * until the peer disconnects or virgl_host_stop() is called.  The host takes
 * ownership of fd and closes it before returning.
 */
VIRGL_HOST_API int virgl_host_serve(virgl_host *host, int fd);

VIRGL_HOST_API void virgl_host_stop(virgl_host *host);
VIRGL_HOST_API void virgl_host_destroy(virgl_host *host);
VIRGL_HOST_API const char *virgl_host_error(const virgl_host *host);

#ifdef __cplusplus
}
#endif

#endif
