package com.linuxsuren.tvuvc;

/**
 * UVC 摄像头采集封装。
 * 通过 Android UsbManager 拿到的设备 fd，经 JNI 交给 libuvc 采集 MJPEG 帧。
 */
public final class UvcCapture {

    static {
        System.loadLibrary("uvcrelay");
    }

    private UvcCapture() {
    }

    /**
     * 启动采集。
     *
     * @param fd     UsbDeviceConnection.getFileDescriptor() 返回的设备 fd
     * @param width  期望宽度（如 1280）
     * @param height 期望高度（如 720）
     * @param fps    期望帧率（如 25）
     * @return 是否成功启动
     */
    public static native boolean nativeStart(int fd, int width, int height, int fps);

    /** 停止采集并释放资源。 */
    public static native void nativeStop();

    /** 原生层帧回调入口，勿手动调用。 */
    @SuppressWarnings("unused")
    private static void onFrame(byte[] data) {
        MjpegServer.publish(data);
    }
}
