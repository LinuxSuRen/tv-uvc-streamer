# tv-uvc-streamer

Android TV 上的 UVC USB 摄像头转流网关。插入 UVC 摄像头，在电视上出 MJPEG 流，供局域网内任意设备（OpenCV / ffmpeg / 浏览器）拉流处理。

## 背景

小米电视 4（Android 6.0 / armeabi-v7a）场景：

- 内核 `uvcvideo` 驱动可识别 USB 摄像头，但系统未创建 `/dev/video*` 节点，shell/ssh 用户无法直接访问
- 系统 Camera API 不支持 USB 摄像头
- 本应用通过 `UsbManager` 授权获取设备 fd，经 libusb/libuvc 用户态采集（自动 detach 内核驱动），绕开以上全部限制

## 架构

```
USB 摄像头 (UVC/MJPEG)
  └─ UsbManager.openDevice() → fd（电视上确认一次授权，可勾选"默认打开"）
  └─ JNI: uvc_wrap(fd) → libuvc 采集 MJPEG（默认 1280x720@25）
  └─ 内置 HTTP 服务（端口 8090）
       ├─ /             状态页
       ├─ /stream       multipart/x-mixed-replace MJPEG 流
       └─ /snapshot.jpg 最新单帧
```

## 构建

依赖：JDK 17+、Android SDK（platforms;android-34、build-tools;34.0.0、cmake;3.22.1）、NDK r27c。

```bash
gradle :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 电视上插入 USB 摄像头，弹出授权对话框时选择允许（建议勾选默认打开）
2. 屏幕显示流地址后，在局域网任意设备访问：
   - `http://<电视IP>:8090/stream`（浏览器直接看）
   - `ffmpeg -f mjpeg -i http://<电视IP>:8090/stream ...`
   - `cv2.VideoCapture("http://<电视IP>:8090/stream")`

## 说明

- 原生依赖以源码形式入库：`app/src/main/jni/third_party/libusb`（LGPL-2.1）与 `libuvc`（BSD-2）
- 仅透传 MJPEG，不做解码；分辨率协商失败会提示（可改 `MainActivity` 中的宽高帧率常量）
- 仅供局域网开发用途，HTTP 服务无鉴权

## 已验证设备

- 小米电视 4（MiTV4-ANSM0，Android 6.0.1，armeabi-v7a）+ HIK 720P Camera (2bdf:0284)
