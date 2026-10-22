#include <jni.h>

#include <errno.h>
#include <stdint.h>

#include "virgl_host.h"

static virgl_host *host_from_handle(jlong handle)
{
   return (virgl_host *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL Java_io_furryr_file_virgl_VirglNative_create(JNIEnv *, jobject);
JNIEXPORT jint JNICALL Java_io_furryr_file_virgl_VirglNative_start(JNIEnv *, jobject, jlong, jstring);
JNIEXPORT jint JNICALL Java_io_furryr_file_virgl_VirglNative_startClients(JNIEnv *, jobject, jlong);
JNIEXPORT jint JNICALL Java_io_furryr_file_virgl_VirglNative_addClient(JNIEnv *, jobject, jlong, jint);
JNIEXPORT jint JNICALL Java_io_furryr_file_virgl_VirglNative_run(JNIEnv *, jobject, jlong);
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
Java_io_furryr_file_virgl_VirglNative_start(JNIEnv *env, jobject thiz,
                                            jlong handle, jstring socket_path)
{
   const char *path;
   int result;

   (void)thiz;
   if (handle == 0 || socket_path == NULL)
      return -EINVAL;

   path = (*env)->GetStringUTFChars(env, socket_path, NULL);
   if (!path)
      return -ENOMEM;

   result = virgl_host_start(host_from_handle(handle), path);
   (*env)->ReleaseStringUTFChars(env, socket_path, path);
   return result;
}

JNIEXPORT jint JNICALL
Java_io_furryr_file_virgl_VirglNative_startClients(JNIEnv *env, jobject thiz, jlong handle)
{
   (void)env;
   (void)thiz;
   if (handle == 0)
      return -EINVAL;
   return virgl_host_start_clients(host_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_io_furryr_file_virgl_VirglNative_addClient(JNIEnv *env, jobject thiz,
                                                jlong handle, jint fd)
{
   (void)env;
   (void)thiz;
   if (handle == 0)
      return -EINVAL;
   return virgl_host_add_client(host_from_handle(handle), fd);
}

JNIEXPORT jint JNICALL
Java_io_furryr_file_virgl_VirglNative_run(JNIEnv *env, jobject thiz, jlong handle)
{
   (void)env;
   (void)thiz;
   if (handle == 0)
      return -EINVAL;
   return virgl_host_run(host_from_handle(handle));
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
