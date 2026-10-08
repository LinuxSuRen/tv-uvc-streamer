---
name: tv-uvc-streamer-dev
description: tv-uvc-streamer 项目专属规范：三协议拓扑与端口、采集优先级链路（内置 Camera2→UVC USB 回退）、自愈看门狗、相机选择持久化、方向跟随、测试设备矩阵与各机型验证命令、tag 触发的发布流程。
whenToUse: 开发、调试、验证或发布 tv-uvc-streamer（Android 摄像头转流网关，https://github.com/LinuxSuRen/tv-uvc-streamer）时使用。
---

# tv-uvc-streamer 项目规范

## 拓扑（一图流）

```
摄像头（内置 Camera2 多路并发 / UVC USB 回退）
  ├─ HTTP :8090   / 状态页 | /snapshot.jpg?cam=N | /stream?cam=N (MJPEG)
  ├─ RTSP :8554   /cam（=cam0）| /camN  —— MJPEG over RTP（RFC 2435），TCP interleaved + UDP 双模
  └─ ONVIF :8000  WS-Discovery 自动发现 + 多 Profile GetProfiles/GetStreamUri/GetSnapshotUri
StreamService：前台服务 + WakeLock + WifiLock(LOW_LATENCY) + 帧停滞看门狗 + 管线自愈
```

## 采集链路（优先级）

1. **内置 Camera2**：全部相机并发开；HAL 不支持并发（`Too many cameras already open`）时按最大分辨率降序抢开（默认分辨率最高的一颗）
2. JPEG 直出优先，YUV_420_888 软压缩回退；**带 DRI 重启标记的帧必须重编码归一化**（RTP-JPEG 客户端普遍不支持，绿屏根因）
3. UVC USB 回退（电视场景）：JNI + libuvc，fd 来自 UsbManager 授权
4. 方向跟随：加速度传感器（平放保持）→ `JPEG_ORIENTATION` 由 HAL 转正像素
5. 相机选择：`SharedPreferences("tvuvc").camera_index`（-1=自动），界面切换即管线重启

## 关键验证命令

```bash
# 三协议快检（IP 换成设备地址）
curl -s http://$IP:8090/ | grep -oE "fps: [0-9]+[^<]*"        # 状态页：每路 fps/帧数/崩溃堆栈
curl -s -o /tmp/s.jpg "http://$IP:8090/snapshot.jpg?cam=$N"   # 单帧（PIL 查尺寸=方向验证）
timeout 15 ffprobe -v error -rtsp_transport tcp -show_streams rtsp://$IP:8554/cam$N
# ONVIF profile 枚举与寻址
curl -s -X POST http://$IP:8000/onvif/device_service -H "Content-Type: application/soap+xml" \
  -d '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl"><s:Body><trt:GetProfiles/></s:Body></s:Envelope>'
# 绿屏检测（RTSP 解码后像素统计）
timeout 20 ffmpeg -rtsp_transport tcp -i rtsp://$IP:8554/cam$N -frames:v 3 -q:v 2 /tmp/f%d.jpg
python3 -c "...绿像素占比采样..."
```

## 测试设备矩阵（每台设备的坑与验证点）

| 设备 | 系统 | 验证点 / 已知坑 |
|---|---|---|
| 小米电视4 | Android 6 | UVC USB 原路径；MIUI free-resource 强杀（后台服务设计的原因） |
| Max2.0 工控板 | Android 12 | 双 Camera2 但 HAL 禁并发；看家应用特权抢相机（自愈验证机）；app idle 60s 强停；需 appops 解锁 |
| OPPO 手机 | Android 11 | HAL 卡死后 connectDevice 挂起（相机启动必须在工作线程）；force-stop 后用 monkey 启动 |
| 联想 TB335ZC | Android 16 | targetSdk 门槛 24；WiFi 省电大流量黑洞（LOW_LATENCY 锁） |

## 发布流程

1. 版本号 PR（`chore: 版本号提升到 vX.Y.Z`，versionCode 递增）
2. 合并后打 tag `vX.Y.Z` 推送 → **Actions 自动构建 APK 附到 Release**（勿手动传 APK）
3. 流水线：JDK21 + Gradle 9.2.1 手动装 + NDK 27.0.12077973 显式预装（勿改回自动安装）
4. 版本号规则：功能版本进位 minor；纯修复/CI 变更 patch

## 关键设计约束（改动前必读）

- ONVIF 只广播**真实在推流**的相机（相机关闭即反注册），Profile token `profile_{N+1}` ↔ 相机编号 N 严格对应
- 看门狗整体重启走**服务内存活管线重启**（EXTRA_RESTART_PIPELINE），绝不能 stopService + 延迟 startService（定制 ROM 静默丢弃）
- RTSP：CSeq 必须回显、含 DRI 的帧直接丢弃（发布层已归一化，正常到不了这）、尺寸/采样从 SOF 动态解析
- 与 [onvif-ai](https://github.com/LinuxSuRen/onvif-ai)（姊妹客户端）联调：其 RTSP 仅 H.264 + G.711，MJPEG 设备走 GetSnapshotUri 快照降级（feat/rtsp-mjpeg 分支已支持 MJPEG 直播）
