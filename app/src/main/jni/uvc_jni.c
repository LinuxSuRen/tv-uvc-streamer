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
#include <pthread.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <libusb.h>          /* 需先于 libuvc.h：提供 LIBUSB_API_VERSION（uvc_wrap 声明守卫依赖） */
#include <libuvc/libuvc.h>

#ifndef USBDEVFS_RESET
#define USBDEVFS_RESET _IO('U', 20)
#endif

#define LOG_TAG "tv-uvc-streamer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static uvc_context_t *g_ctx = NULL;
static uvc_device_handle_t *g_devh = NULL;
static jclass g_captureClass = NULL; /* UvcCapture 全局引用：回调线程无应用类加载器，必须缓存 */
static volatile int g_streaming = 0;
/* 串行化启动/停止：多个调用方（界面/保活任务）可能并发拉起，
 * 并发 uvc_init/uvc_exit 同一全局上下文会在 libusb 内部触发互斥锁销毁断言（实测） */
static pthread_mutex_t g_state_mutex = PTHREAD_MUTEX_INITIALIZER;

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

/* 打开并启动一次流；失败时调用方负责 close_all 清理 */
static uvc_error_t open_and_stream(int fd, int width, int height, int fps) {
    uvc_error_t rc = uvc_wrap(fd, g_ctx, &g_devh);
    if (rc != UVC_SUCCESS) {
        LOGE("uvc_wrap(fd=%d) failed: %d", fd, rc);
        return rc;
    }
    LOGI("uvc_wrap ok, negotiating MJPEG %dx%d@%d", width, height, fps);

    uvc_stream_ctrl_t ctrl;
    rc = uvc_get_stream_ctrl_format_size(g_devh, &ctrl, UVC_FRAME_FORMAT_MJPEG,
                                         width, height, fps);
    if (rc != UVC_SUCCESS) {
        LOGE("negotiate format failed: %d", rc);
        return rc;
    }
    g_streaming = 1;
    rc = uvc_start_streaming(g_devh, &ctrl, frame_cb, NULL, 0);
    if (rc != UVC_SUCCESS) {
        g_streaming = 0;
        LOGE("uvc_start_streaming failed: %d", rc);
        return rc;
    }
    LOGI("streaming started");
    return UVC_SUCCESS;
}

static void close_all(void) {
    if (g_devh != NULL) {
        uvc_stop_streaming(g_devh);
        uvc_close(g_devh);
        g_devh = NULL;
    }
    if (g_ctx != NULL) {
        uvc_exit(g_ctx);
        g_ctx = NULL;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_linuxsuren_tvuvc_UvcCapture_nativeStart(JNIEnv *env, jclass clazz,
                                                 jint fd, jint width, jint height, jint fps) {
    (void) clazz;
    jboolean result = JNI_FALSE;
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
    pthread_mutex_lock(&g_state_mutex);
    if (g_devh != NULL) {
        result = JNI_TRUE; /* 已在运行 */
    } else if (uvc_init(&g_ctx, NULL) != UVC_SUCCESS) {
        LOGE("uvc_init failed");
        g_ctx = NULL;
    } else {
        uvc_error_t rc = open_and_stream(fd, width, height, fps);
        if (rc != UVC_SUCCESS) {
            /* 自愈：进程异常退出后摄像头可能残留占用，复位 USB 口后重试一次 */
            LOGI("first attempt failed (%d), resetting USB device and retrying", rc);
            close_all();
            if (ioctl(fd, USBDEVFS_RESET) != 0) {
                LOGE("USBDEVFS_RESET failed");
            }
            usleep(500 * 1000);
            if (uvc_init(&g_ctx, NULL) == UVC_SUCCESS) {
                rc = open_and_stream(fd, width, height, fps);
                if (rc != UVC_SUCCESS) {
                    close_all();
                } else {
                    result = JNI_TRUE;
                }
            } else {
                g_ctx = NULL;
            }
        } else {
            result = JNI_TRUE;
        }
    }
    pthread_mutex_unlock(&g_state_mutex);
    return result;
}

JNIEXPORT void JNICALL
Java_com_linuxsuren_tvuvc_UvcCapture_nativeStop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_state_mutex);
    g_streaming = 0;
    close_all();
    pthread_mutex_unlock(&g_state_mutex);
    LOGI("streaming stopped");
}
