/*
 * Embedded PulseAudio 17.0 host. The native protocol, mixer, resampler and
 * Android devices live in one library; Kotlin owns the only socket inode,
 * AudioTrack, AudioRecord and microphone foreground-service policy.
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#include <config.h>
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <unistd.h>

#include <pulse/mainloop.h>
#include <pulse/rtclock.h>
#include <pulse/timeval.h>
#include <pulsecore/core.h>
#include <pulsecore/core-scache.h>
#include <pulsecore/log.h>
#include <pulsecore/module.h>
#include <pulsecore/protocol-native.h>
#include <pulsecore/rtpoll.h>
#include <pulsecore/thread.h>
#include <pulsecore/thread-mq.h>

#define TAG "PulseHost"
#define BLOCK_USEC (10 * PA_USEC_PER_MSEC)
#define SINK_BYTES 3840 /* 10 ms, 48 kHz stereo float32 */
#define MIC_BYTES 960   /* 10 ms, 48 kHz mono s16le */
#define MIC_RING_BYTES (MIC_BYTES * 20)
#define MAX_PENDING_CLIENTS 64

struct client_batch {
    struct client_batch *next;
    size_t count;
    int fds[];
};

struct host {
    JavaVM *vm;
    jobject callback;
    jmethodID on_sink, on_idle, on_mic;
    uint8_t *sink_buffer, *mic_buffer;
    size_t sink_capacity, mic_capacity;
    bool mic_enabled;
    int buffer_ms;
    int clients_fd, stop_fd;
    pthread_t engine;
    pthread_mutex_t startup_lock, mic_lock, clients_lock;
    pthread_cond_t startup_cond;
    bool startup_done, ready;
    char error[256];

    pa_mainloop *mainloop;
    pa_core *core;
    pa_native_protocol *protocol;
    pa_native_options *options;
    pa_io_event *clients_event, *stop_event;
    struct client_batch *clients_head, *clients_tail;
    size_t pending_clients;
    pa_module module;
    pa_sink *sink;
    pa_source *source;
    pa_thread *io_thread;
    pa_thread_mq mq;
    bool mq_ready;
    pa_rtpoll *rtpoll;
    pa_usec_t sink_latency;
    bool mic_running;
    uint8_t mic_ring[MIC_RING_BYTES];
    size_t mic_head, mic_size;
};

static char last_error[256]; /* JNI lifecycle calls are serialized by the service. */

static JNIEnv *attach(struct host *h, bool *attached) {
    JNIEnv *env = NULL;
    *attached = false;
    if ((*h->vm)->GetEnv(h->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*h->vm)->AttachCurrentThread(h->vm, &env, NULL) != JNI_OK) return NULL;
        *attached = true;
    }
    return env;
}

static void check_java(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Android audio callback failed");
    }
}

static int sink_message(pa_msgobject *object, int code, void *data, int64_t offset, pa_memchunk *chunk) {
    struct host *h = PA_SINK(object)->userdata;
    if (code == PA_SINK_MESSAGE_GET_LATENCY) {
        *(int64_t *)data = h->sink_latency;
        return 0;
    }
    return pa_sink_process_msg(object, code, data, offset, chunk);
}

static int source_message(pa_msgobject *object, int code, void *data, int64_t offset, pa_memchunk *chunk) {
    if (code == PA_SOURCE_MESSAGE_GET_LATENCY) {
        *(int64_t *)data = BLOCK_USEC;
        return 0;
    }
    return pa_source_process_msg(object, code, data, offset, chunk);
}

static int sink_state(pa_sink *sink, pa_sink_state_t state, pa_suspend_cause_t cause) {
    struct host *h = sink->userdata;
    (void)cause;
    if (state == PA_SINK_IDLE && h->on_idle) {
        bool attached;
        JNIEnv *env = attach(h, &attached);
        if (env) {
            (*env)->CallVoidMethod(env, h->callback, h->on_idle);
            check_java(env);
        }
        if (attached) (*h->vm)->DetachCurrentThread(h->vm);
    }
    return 0;
}

/* Main-loop thread only. JNI callback posts policy changes to Android's main looper. */
static int source_state(pa_source *source, pa_source_state_t state, pa_suspend_cause_t cause) {
    struct host *h = source->userdata;
    bool running = state == PA_SOURCE_RUNNING;
    (void)cause;
    if (h->mic_running != running) {
        h->mic_running = running;
        pthread_mutex_lock(&h->mic_lock);
        h->mic_head = h->mic_size = 0;
        pthread_mutex_unlock(&h->mic_lock);
        bool attached;
        JNIEnv *env = attach(h, &attached);
        if (env) {
            (*env)->CallVoidMethod(env, h->callback, h->on_mic, (jboolean)running);
            check_java(env);
        }
        if (attached) (*h->vm)->DetachCurrentThread(h->vm);
    }
    return 0;
}

static void emit_sink(struct host *h, JNIEnv *env) {
    pa_memchunk chunk;
    /* render() may return a short chunk at an input boundary. Advancing a
     * whole 10 ms for that chunk steadily drains AudioTrack's buffer. */
    pa_sink_render_full(h->sink, SINK_BYTES, &chunk);
    void *pcm = pa_memblock_acquire(chunk.memblock);
    size_t length = PA_MIN(chunk.length, h->sink_capacity);
    memcpy(h->sink_buffer, (uint8_t *)pcm + chunk.index, length);
    pa_memblock_release(chunk.memblock);
    pa_memblock_unref(chunk.memblock);
    if (env) {
        (*env)->CallVoidMethod(env, h->callback, h->on_sink, (jint)length);
        check_java(env);
    }
}

static void emit_source(struct host *h) {
    pa_memchunk chunk = { .memblock = pa_memblock_new(h->core->mempool, MIC_BYTES),
                          .index = 0, .length = MIC_BYTES };
    uint8_t *pcm = pa_memblock_acquire(chunk.memblock);
    memset(pcm, 0, MIC_BYTES);
    pthread_mutex_lock(&h->mic_lock);
    size_t count = PA_MIN(h->mic_size, MIC_BYTES);
    for (size_t i = 0; i < count; i++) pcm[i] = h->mic_ring[(h->mic_head + i) % MIC_RING_BYTES];
    h->mic_head = (h->mic_head + count) % MIC_RING_BYTES;
    h->mic_size -= count;
    pthread_mutex_unlock(&h->mic_lock);
    pa_memblock_release(chunk.memblock);
    pa_source_post(h->source, &chunk);
    pa_memblock_unref(chunk.memblock);
}

static void io_main(void *userdata) {
    struct host *h = userdata;
    bool attached;
    JNIEnv *env = attach(h, &attached);
    pa_thread_mq_install(&h->mq);
    pa_usec_t deadline = pa_rtclock_now();
    for (;;) {
        bool playback = h->sink->thread_info.state == PA_SINK_RUNNING ||
                        h->sink->monitor_source->thread_info.state == PA_SOURCE_RUNNING;
        bool capture = h->source && h->source->thread_info.state == PA_SOURCE_RUNNING;
        if (h->sink->thread_info.rewind_requested) pa_sink_process_rewind(h->sink, 0);
        pa_usec_t now = pa_rtclock_now();
        if (playback || capture) {
            if (now >= deadline) {
                /* Advance the sample clock, not the observed wake-up time.
                 * now + block accumulates every scheduler delay and feeds
                 * fewer than 48000 frames/second, causing periodic underruns. */
                if (now > deadline + h->sink_latency) deadline = now;
                deadline += BLOCK_USEC;
                if (capture) emit_source(h);
                if (playback) emit_sink(h, env);
            }
            /* The blocking AudioTrack write may already consume this interval.
             * An absolute timer avoids adding another sleep after that write. */
            pa_rtpoll_set_timer_absolute(h->rtpoll, deadline);
        } else {
            deadline = now;
            pa_rtpoll_set_timer_disabled(h->rtpoll);
        }
        int result = pa_rtpoll_run(h->rtpoll);
        if (result == 0) break;
        if (result < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "audio poll failed");
            /* Keep servicing synchronous teardown messages until shutdown. */
            pa_asyncmsgq_wait_for(h->mq.inq, PA_MESSAGE_SHUTDOWN);
            break;
        }
    }
    if (attached) (*h->vm)->DetachCurrentThread(h->vm);
}

static void connect_clients(pa_mainloop_api *api, pa_io_event *event, int fd,
                          pa_io_event_flags_t flags, void *userdata) {
    struct host *h = userdata;
    (void)event;
    (void)flags;
    uint64_t wake;
    while (read(fd, &wake, sizeof(wake)) < 0 && errno == EINTR) {}
    pthread_mutex_lock(&h->clients_lock);
    struct client_batch *batch = h->clients_head;
    h->clients_head = h->clients_tail = NULL;
    h->pending_clients = 0;
    pthread_mutex_unlock(&h->clients_lock);
    while (batch) {
        struct client_batch *next = batch->next;
        for (size_t i = 0; i < batch->count; i++) {
            int client = batch->fds[i];
            int mode = fcntl(client, F_GETFL);
            if (mode < 0 || fcntl(client, F_SETFL, mode | O_NONBLOCK) < 0) {
                close(client);
                continue;
            }
            pa_iochannel *io = pa_iochannel_new(api, client, client);
            if (io) pa_native_protocol_connect(h->protocol, io, h->options);
            else close(client);
        }
        free(batch);
        batch = next;
    }
}

static void stop_core(pa_mainloop_api *api, pa_io_event *event, int fd,
                      pa_io_event_flags_t flags, void *userdata) {
    (void)event; (void)fd; (void)flags; (void)userdata;
    api->quit(api, 0);
}

static bool create_devices(struct host *h) {
    h->rtpoll = pa_rtpoll_new();
    if (pa_thread_mq_init(&h->mq, h->core->mainloop, h->rtpoll) < 0) return false;
    h->mq_ready = true;
    pa_sample_spec spec = { PA_SAMPLE_FLOAT32LE, 48000, 2 };
    pa_channel_map map;
    pa_channel_map_init_stereo(&map);
    pa_sink_new_data sink;
    pa_sink_new_data_init(&sink);
    sink.driver = "android";
    sink.module = &h->module;
    pa_sink_new_data_set_name(&sink, "android_output");
    pa_sink_new_data_set_sample_spec(&sink, &spec);
    pa_sink_new_data_set_channel_map(&sink, &map);
    pa_proplist_sets(sink.proplist, PA_PROP_DEVICE_DESCRIPTION, "Android Output");
    h->sink = pa_sink_new(h->core, &sink, PA_SINK_LATENCY);
    pa_sink_new_data_done(&sink);
    if (!h->sink) return false;
    h->sink->userdata = h;
    h->sink->parent.process_msg = sink_message;
    h->sink->set_state_in_main_thread = sink_state;
    pa_sink_set_asyncmsgq(h->sink, h->mq.inq);
    pa_sink_set_rtpoll(h->sink, h->rtpoll);
    pa_sink_set_max_rewind(h->sink, 0);
    pa_sink_set_max_request(h->sink, SINK_BYTES);
    pa_sink_set_fixed_latency(h->sink, h->sink_latency);

    if (h->mic_enabled) {
        spec = (pa_sample_spec){ PA_SAMPLE_S16LE, 48000, 1 };
        pa_channel_map_init_mono(&map);
        pa_source_new_data source;
        pa_source_new_data_init(&source);
        source.driver = "android";
        source.module = &h->module;
        pa_source_new_data_set_name(&source, "android_input");
        pa_source_new_data_set_sample_spec(&source, &spec);
        pa_source_new_data_set_channel_map(&source, &map);
        pa_proplist_sets(source.proplist, PA_PROP_DEVICE_DESCRIPTION, "Android Microphone");
        h->source = pa_source_new(h->core, &source, PA_SOURCE_LATENCY);
        pa_source_new_data_done(&source);
        if (!h->source) return false;
        h->source->userdata = h;
        h->source->parent.process_msg = source_message;
        h->source->set_state_in_main_thread = source_state;
        pa_source_set_asyncmsgq(h->source, h->mq.inq);
        pa_source_set_rtpoll(h->source, h->rtpoll);
        pa_source_set_fixed_latency(h->source, BLOCK_USEC);
    }
    h->io_thread = pa_thread_new("pulse-audio", io_main, h);
    if (!h->io_thread) return false;
    pa_sink_put(h->sink);
    if (h->source) {
        pa_source_put(h->source);
        pa_core_set_configured_default_source(h->core, h->source->name);
    }
    pa_core_set_configured_default_sink(h->core, h->sink->name);
    return true;
}

static void cleanup(struct host *h) {
    if (h->clients_event) h->core->mainloop->io_free(h->clients_event);
    if (h->stop_event) h->core->mainloop->io_free(h->stop_event);
    if (h->protocol) pa_native_protocol_unref(h->protocol);
    if (h->options) pa_native_options_unref(h->options);
    if (h->source) pa_source_unlink(h->source);
    if (h->sink) pa_sink_unlink(h->sink);
    if (h->io_thread) {
        pa_asyncmsgq_send(h->mq.inq, NULL, PA_MESSAGE_SHUTDOWN, NULL, 0, NULL);
        pa_thread_free(h->io_thread);
    }
    if (h->mq_ready) pa_thread_mq_done(&h->mq);
    if (h->source) pa_source_unref(h->source);
    if (h->sink) pa_sink_unref(h->sink);
    if (h->rtpoll) pa_rtpoll_free(h->rtpoll);
    if (h->core) {
        pa_scache_free_all(h->core);
        pa_core_unref(h->core);
    }
    if (h->mainloop) pa_mainloop_free(h->mainloop);
}

static void *engine_main(void *userdata) {
    struct host *h = userdata;
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &signals, NULL);
    pa_log_set_level(PA_LOG_WARN);
    h->mainloop = pa_mainloop_new();
    if (!h->mainloop) goto failed;
    h->core = pa_core_new(pa_mainloop_get_api(h->mainloop), false, false, 0);
    if (!h->core) goto failed;
    h->core->disallow_module_loading = true;
    h->core->disallow_exit = true;
    h->core->flat_volumes = false;
    h->core->resample_method = PA_RESAMPLER_FFMPEG;
    h->module.core = h->core;
    h->module.name = "android";
    h->module.index = PA_INVALID_INDEX;
    if (!create_devices(h)) goto failed;
    h->protocol = pa_native_protocol_get(h->core);
    h->options = pa_native_options_new();
    h->options->module = &h->module;
    h->options->auth_anonymous = true;
    h->options->srbchannel = false;
    pa_mainloop_api *api = h->core->mainloop;
    h->clients_event = api->io_new(api, h->clients_fd, PA_IO_EVENT_INPUT, connect_clients, h);
    h->stop_event = api->io_new(api, h->stop_fd, PA_IO_EVENT_INPUT, stop_core, h);
    if (!h->clients_event || !h->stop_event) goto failed;
    h->core->state = PA_CORE_RUNNING;
    pthread_mutex_lock(&h->startup_lock);
    h->ready = h->startup_done = true;
    pthread_cond_signal(&h->startup_cond);
    pthread_mutex_unlock(&h->startup_lock);
    __android_log_print(ANDROID_LOG_INFO, TAG, "PulseAudio ready: android_output%s", h->source ? " + android_input" : "");
    pa_mainloop_run(h->mainloop, NULL);
    cleanup(h);
    return NULL;
failed:
    snprintf(h->error, sizeof(h->error), "PulseAudio core/device initialization failed");
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", h->error);
    cleanup(h);
    pthread_mutex_lock(&h->startup_lock);
    h->startup_done = true;
    pthread_cond_signal(&h->startup_cond);
    pthread_mutex_unlock(&h->startup_lock);
    return NULL;
}

static void free_host(JNIEnv *env, struct host *h) {
    struct client_batch *batch = h->clients_head;
    while (batch) {
        struct client_batch *next = batch->next;
        for (size_t i = 0; i < batch->count; i++) close(batch->fds[i]);
        free(batch);
        batch = next;
    }
    if (h->clients_fd >= 0) close(h->clients_fd);
    if (h->stop_fd >= 0) close(h->stop_fd);
    if (h->callback) (*env)->DeleteGlobalRef(env, h->callback);
    pthread_cond_destroy(&h->startup_cond);
    pthread_mutex_destroy(&h->startup_lock);
    pthread_mutex_destroy(&h->mic_lock);
    pthread_mutex_destroy(&h->clients_lock);
    free(h);
}

JNIEXPORT jlong JNICALL Java_io_furryr_file_pulse_PulseNative_create(
        JNIEnv *env, jobject self, jobject callback, jobject sink_buffer,
        jobject mic_buffer, jboolean mic_enabled, jint buffer_ms) {
    (void)self;
    struct host *h = calloc(1, sizeof(*h));
    if (!h) return 0;
    last_error[0] = 0;
    h->clients_fd = h->stop_fd = -1;
    pthread_mutex_init(&h->startup_lock, NULL);
    pthread_mutex_init(&h->mic_lock, NULL);
    pthread_mutex_init(&h->clients_lock, NULL);
    pthread_cond_init(&h->startup_cond, NULL);
    (*env)->GetJavaVM(env, &h->vm);
    h->callback = (*env)->NewGlobalRef(env, callback);
    jclass type = (*env)->GetObjectClass(env, callback);
    h->on_sink = (*env)->GetMethodID(env, type, "onSinkData", "(I)V");
    h->on_idle = (*env)->GetMethodID(env, type, "onSinkIdle", "()V");
    h->on_mic = (*env)->GetMethodID(env, type, "onMicState", "(Z)V");
    (*env)->DeleteLocalRef(env, type);
    h->sink_buffer = (*env)->GetDirectBufferAddress(env, sink_buffer);
    h->mic_buffer = (*env)->GetDirectBufferAddress(env, mic_buffer);
    jlong sink_capacity = (*env)->GetDirectBufferCapacity(env, sink_buffer);
    jlong mic_capacity = (*env)->GetDirectBufferCapacity(env, mic_buffer);
    if (!h->callback || !h->on_sink || !h->on_idle || !h->on_mic || !h->sink_buffer || !h->mic_buffer ||
        sink_capacity < SINK_BYTES || mic_capacity < MIC_BYTES) goto failed;
    h->sink_capacity = (size_t)sink_capacity;
    h->mic_capacity = (size_t)mic_capacity;
    h->mic_enabled = mic_enabled;
    h->buffer_ms = PA_CLAMP(buffer_ms, 10, 1000);
    h->sink_latency = h->buffer_ms * PA_USEC_PER_MSEC;
    h->clients_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    h->stop_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (h->clients_fd < 0 || h->stop_fd < 0) goto failed;
    if (pthread_create(&h->engine, NULL, engine_main, h) != 0) goto failed;
    pthread_mutex_lock(&h->startup_lock);
    while (!h->startup_done) pthread_cond_wait(&h->startup_cond, &h->startup_lock);
    pthread_mutex_unlock(&h->startup_lock);
    if (h->ready) return (jlong)(intptr_t)h;
    pthread_join(h->engine, NULL);
failed:
    snprintf(last_error, sizeof(last_error), "%s", h->error[0] ? h->error : "PulseAudio JNI/descriptor setup failed");
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", last_error);
    free_host(env, h);
    return 0;
}

JNIEXPORT jboolean JNICALL Java_io_furryr_file_pulse_PulseNative_addClients(
        JNIEnv *env, jobject self, jlong handle, jintArray clients) {
    (void)self;
    struct host *h = (struct host *)(intptr_t)handle;
    if (!h || !clients) return JNI_FALSE;
    jsize count = (*env)->GetArrayLength(env, clients);
    if (count == 0) return JNI_TRUE;
    if (count > MAX_PENDING_CLIENTS) return JNI_FALSE;
    jint fds[MAX_PENDING_CLIENTS];
    (*env)->GetIntArrayRegion(env, clients, 0, count, fds);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    struct client_batch *batch = calloc(1, sizeof(*batch) + count * sizeof(int));
    if (!batch) return JNI_FALSE;
    for (jsize i = 0; i < count; i++) {
        int client = fcntl(fds[i], F_DUPFD_CLOEXEC, 0);
        if (client < 0) goto failed;
        batch->fds[batch->count++] = client;
    }
    pthread_mutex_lock(&h->clients_lock);
    if (h->pending_clients + batch->count > MAX_PENDING_CLIENTS) {
        pthread_mutex_unlock(&h->clients_lock);
        goto failed;
    }
    /* Wake while holding the lock: the main loop cannot consume an unlinked batch. */
    uint64_t wake = 1;
    ssize_t result;
    do { result = write(h->clients_fd, &wake, sizeof(wake)); } while (result < 0 && errno == EINTR);
    if (result < 0 && errno != EAGAIN) {
        pthread_mutex_unlock(&h->clients_lock);
        goto failed;
    }
    if (h->clients_tail) h->clients_tail->next = batch;
    else h->clients_head = batch;
    h->clients_tail = batch;
    h->pending_clients += batch->count;
    pthread_mutex_unlock(&h->clients_lock);
    return JNI_TRUE;
failed:
    for (size_t i = 0; i < batch->count; i++) close(batch->fds[i]);
    free(batch);
    __android_log_print(ANDROID_LOG_WARN, TAG, "cannot queue PulseAudio client batch");
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_furryr_file_pulse_PulseNative_postMicPcm(
        JNIEnv *env, jobject self, jlong handle, jint length) {
    (void)env; (void)self;
    struct host *h = (struct host *)(intptr_t)handle;
    if (!h || !h->mic_enabled || length <= 0 || (size_t)length > h->mic_capacity) return 0;
    size_t size = (size_t)length & ~(size_t)1;
    pthread_mutex_lock(&h->mic_lock);
    if (h->mic_size + size > MIC_RING_BYTES) {
        size_t drop = h->mic_size + size - MIC_RING_BYTES;
        h->mic_head = (h->mic_head + drop) % MIC_RING_BYTES;
        h->mic_size -= drop;
    }
    for (size_t i = 0; i < size; i++)
        h->mic_ring[(h->mic_head + h->mic_size + i) % MIC_RING_BYTES] = h->mic_buffer[i];
    h->mic_size += size;
    pthread_mutex_unlock(&h->mic_lock);
    return (jint)size;
}

JNIEXPORT void JNICALL Java_io_furryr_file_pulse_PulseNative_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    struct host *h = (struct host *)(intptr_t)handle;
    if (!h) return;
    uint64_t wake = 1;
    ssize_t result;
    do { result = write(h->stop_fd, &wake, sizeof(wake)); } while (result < 0 && errno == EINTR);
    pthread_join(h->engine, NULL);
    free_host(env, h);
}

JNIEXPORT jstring JNICALL Java_io_furryr_file_pulse_PulseNative_error(JNIEnv *env, jobject self) {
    (void)self;
    return last_error[0] ? (*env)->NewStringUTF(env, last_error) : NULL;
}
