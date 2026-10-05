/*
 * tv-uvc-streamer JNI 垫片
 *
 * 流程：Java 传入 UsbDeviceConnection 的 fd
 *   → uvc_init 建上下文
 *   → uvc_wrap(fd) 包装既有 fd（libusb_wrap_sys_device）
 *   → 协商 MJPEG 格式并启动流
 *   → 帧回调 AttachCurrentThread 回传 Java
 */
#include <jni.h>
#include <android/log.h>
#include <libusb.h>          /* 需先于 libuvc.h：提供 LIBUSB_API_VERSION（uvc_wrap 声明守卫依赖） */
#include <libuvc/libuvc.h>

#define LOG_TAG "tv-uvc-streamer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static uvc_context_t *g_ctx = NULL;
static uvc_device_handle_t *g_devh = NULL;
static jclass g_captureClass = NULL; /* UvcCapture 全局引用：回调线程无应用类加载器，必须缓存 */
static volatile int g_streaming = 0;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static void frame_cb(uvc_frame_t *frame, void *user) {
    (void) user;
    if (!g_streaming || frame == NULL || frame->data_bytes <= 2) {
        return;
    }
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
            return;
        }
        attached = 1;
    }
    if (g_captureClass != NULL) {
        jbyteArray arr = (*env)->NewByteArray(env, (jsize) frame->data_bytes);
        if (arr != NULL) {
            (*env)->SetByteArrayRegion(env, arr, 0, (jsize) frame->data_bytes,
                                       (const jbyte *) frame->data);
            jmethodID mid = (*env)->GetStaticMethodID(env, g_captureClass, "onFrame", "([B)V");
            if (mid != NULL) {
                (*env)->CallStaticVoidMethod(env, g_captureClass, mid, arr);
            }
            (*env)->DeleteLocalRef(env, arr);
        }
    } else {
        LOGE("frame dropped: callback class not cached");
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_linuxsuren_tvuvc_UvcCapture_nativeStart(JNIEnv *env, jclass clazz,
                                                 jint fd, jint width, jint height, jint fps) {
    (void) clazz;
    if (g_devh != NULL) {
        return JNI_TRUE; /* 已在运行 */
    }
    /* 在 Java 线程缓存回调类（AttachCurrentThread 的线程无法 FindClass 应用类） */
    if (g_captureClass == NULL) {
        jclass local = (*env)->FindClass(env, "com/linuxsuren/tvuvc/UvcCapture");
        if (local == NULL) {
            LOGE("FindClass UvcCapture failed");
            return JNI_FALSE;
        }
        g_captureClass = (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
    }
    uvc_error_t rc = uvc_init(&g_ctx, NULL);
    if (rc != UVC_SUCCESS) {
        LOGE("uvc_init failed: %d", rc);
        g_ctx = NULL;
        return JNI_FALSE;
    }
    rc = uvc_wrap(fd, g_ctx, &g_devh);
    if (rc != UVC_SUCCESS) {
        LOGE("uvc_wrap(fd=%d) failed: %d", fd, rc);
        uvc_exit(g_ctx);
        g_ctx = NULL;
        return JNI_FALSE;
    }
    LOGI("uvc_wrap ok, negotiating MJPEG %dx%d@%d", width, height, fps);

    uvc_stream_ctrl_t ctrl;
    rc = uvc_get_stream_ctrl_format_size(g_devh, &ctrl, UVC_FRAME_FORMAT_MJPEG,
                                         width, height, fps);
    if (rc != UVC_SUCCESS) {
        LOGE("negotiate format failed: %d", rc);
        uvc_close(g_devh);
        g_devh = NULL;
        uvc_exit(g_ctx);
        g_ctx = NULL;
        return JNI_FALSE;
    }
    g_streaming = 1;
    rc = uvc_start_streaming(g_devh, &ctrl, frame_cb, NULL, 0);
    if (rc != UVC_SUCCESS) {
        LOGE("uvc_start_streaming failed: %d", rc);
        g_streaming = 0;
        uvc_close(g_devh);
        g_devh = NULL;
        uvc_exit(g_ctx);
        g_ctx = NULL;
        return JNI_FALSE;
    }
    LOGI("streaming started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_linuxsuren_tvuvc_UvcCapture_nativeStop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    g_streaming = 0;
    if (g_devh != NULL) {
        uvc_stop_streaming(g_devh);
        uvc_close(g_devh);
        g_devh = NULL;
    }
    if (g_ctx != NULL) {
        uvc_exit(g_ctx);
        g_ctx = NULL;
    }
    LOGI("streaming stopped");
}
