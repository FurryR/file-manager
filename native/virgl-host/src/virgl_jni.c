#include <jni.h>

#include <errno.h>
#include <stdint.h>
#include <unistd.h>

#include "virgl_host.h"

static virgl_host *host_from_handle(jlong handle)
{
   return (virgl_host *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL Java_io_furryr_file_virgl_VirglNative_create(JNIEnv *, jobject);
JNIEXPORT jint JNICALL Java_io_furryr_file_virgl_VirglNative_serve(JNIEnv *, jobject, jlong, jint);
JNIEXPORT void JNICALL Java_io_furryr_file_virgl_VirglNative_stop(JNIEnv *, jobject, jlong);
JNIEXPORT void JNICALL Java_io_furryr_file_virgl_VirglNative_destroy(JNIEnv *, jobject, jlong);
JNIEXPORT jstring JNICALL Java_io_furryr_file_virgl_VirglNative_error(JNIEnv *, jobject, jlong);

JNIEXPORT jlong JNICALL
Java_io_furryr_file_virgl_VirglNative_create(JNIEnv *env, jobject thiz)
{
   (void)env;
   (void)thiz;
   return (jlong)(intptr_t)virgl_host_create();
}

JNIEXPORT jint JNICALL
Java_io_furryr_file_virgl_VirglNative_serve(JNIEnv *env, jobject thiz,
                                            jlong handle, jint fd)
{
   (void)env;
   (void)thiz;
   if (handle == 0) {
      if (fd >= 0)
         close((int)fd);
      return -EINVAL;
   }
   return virgl_host_serve(host_from_handle(handle), (int)fd);
}

JNIEXPORT void JNICALL
Java_io_furryr_file_virgl_VirglNative_stop(JNIEnv *env, jobject thiz, jlong handle)
{
   (void)env;
   (void)thiz;
   if (handle != 0)
      virgl_host_stop(host_from_handle(handle));
}

JNIEXPORT void JNICALL
Java_io_furryr_file_virgl_VirglNative_destroy(JNIEnv *env, jobject thiz, jlong handle)
{
   (void)env;
   (void)thiz;
   if (handle != 0)
      virgl_host_destroy(host_from_handle(handle));
}

JNIEXPORT jstring JNICALL
Java_io_furryr_file_virgl_VirglNative_error(JNIEnv *env, jobject thiz, jlong handle)
{
   (void)thiz;
   if (handle == 0)
      return NULL;
   const char *error = virgl_host_error(host_from_handle(handle));
   return error ? (*env)->NewStringUTF(env, error) : NULL;
}
