# tv-uvc-streamer

Android TV 上的 UVC USB 摄像头转流网关。插入 UVC 摄像头，在电视上同时输出 HTTP MJPEG 与 RTSP 流，并可通过 ONVIF 自动发现，供局域网内任意设备（OpenCV / ffmpeg / 浏览器 / NVR / Frigate）拉流处理。

## 背景

小米电视 4（Android 6.0 / armeabi-v7a）场景：

- 内核 `uvcvideo` 驱动可识别 USB 摄像头，但系统未创建 `/dev/video*` 节点，shell/ssh 用户无法直接访问
- 系统 Camera API 不支持 USB 摄像头
- 本应用通过 `UsbManager` 授权获取设备 fd，经 libusb/libuvc 用户态采集（自动 detach 内核驱动），绕开以上全部限制

## 架构

```
USB 摄像头 (UVC/MJPEG)
  └─ StreamService（纯后台 Service，授权后由 Activity 拉起并立即 finish）
      ├─ JNI: uvc_wrap(fd) → libuvc 采集 MJPEG（默认 1280x720@25）
      ├─ HTTP :8090  /  状态页 | /snapshot.jpg | /stream (multipart MJPEG)
      ├─ RTSP :8554  /  cam（RFC 2435 MJPEG-over-RTP）
      └─ ONVIF :8000  /  WS-Discovery 自动发现 + GetStreamUri
```

## 访问方式

| 协议 | 地址 |
|---|---|
| ONVIF 自动发现 | 组播 239.255.255.250:3702（WS-Discovery，类型 NetworkVideoTransmitter）|
| ONVIF SOAP | `http://<电视IP>:8000/onvif/device_service`（GetProfiles / GetStreamUri 等）|
| RTSP | `rtsp://<电视IP>:8554/cam`（VLC / ffmpeg / OpenCV / Frigate）|
| HTTP MJPEG | `http://<电视IP>:8090/stream`（浏览器直接看）|
| 单帧快照 | `http://<电视IP>:8090/snapshot.jpg` |

## 构建

依赖：JDK 17+、Android SDK（platforms;android-34、build-tools;34.0.0、cmake;3.22.1）、NDK r27c。

```bash
gradle :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 电视上插入 USB 摄像头，首次弹出授权对话框时允许（此后免授权）
2. 摄像头插入/重插会经 USB ATTACH 广播直接拉起后台服务（不经界面）
3. 服务持有 WakeLock + WifiLock，熄屏后持续可用（需系统 WiFi 休眠策略为"永不"）

## MIUI 兼容性说明（重要）

- MIUI TV 会在应用离开前台时以「free resource」强杀带前台 Activity 的应用（TvMgr-MemoryAutoKill），且禁止第三方应用前台服务与开机广播
- 应对策略：授权后 Activity 立即 finish，全部工作由纯后台 Service 承担；USB ATTACH 广播直启服务；JobScheduler（15 分钟周期、persisted）自愈
- 系统级建议：`adb shell settings put global wifi_sleep_policy 2` 防止熄屏断网

## 说明

- 原生依赖以源码形式入库：`app/src/main/jni/third_party/libusb`（LGPL-2.1）与 `libuvc`（BSD-2）
- 仅透传 MJPEG，不做解码；分辨率协商失败会提示（可改 `StreamService` 中的宽高帧率常量）
- RTSP 采用 RFC 2435 打包：解析帧内 DQT 量化表与 SOS 扫描数据，按分片偏移打包，ffmpeg 实测可解
- 采集启动失败时自动执行 `USBDEVFS_RESET` 复位重试（进程异常退出后的接口残留自愈）
- 仅供局域网开发用途，所有服务无鉴权

## 已验证设备

- 小米电视 4（MiTV4-ANSM0，Android 6.0.1，armeabi-v7a）+ HIK 720P Camera (2bdf:0284)
