cmake_minimum_required(VERSION 3.22.1)
project(uvcrelay C)

set(third ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# ---------------- libusb (静态库) ----------------
# 源码清单对齐 libusb 官方 android/jni/libusb.mk，Android 走 linux_usbfs 后端
set(LIBUSB_DIR ${third}/libusb)
add_library(usb_static STATIC
        ${LIBUSB_DIR}/libusb/core.c
        ${LIBUSB_DIR}/libusb/descriptor.c
        ${LIBUSB_DIR}/libusb/hotplug.c
        ${LIBUSB_DIR}/libusb/io.c
        ${LIBUSB_DIR}/libusb/sync.c
        ${LIBUSB_DIR}/libusb/strerror.c
        ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
        ${LIBUSB_DIR}/libusb/os/events_posix.c
        ${LIBUSB_DIR}/libusb/os/threads_posix.c)
target_include_directories(usb_static PUBLIC
        ${LIBUSB_DIR}
        ${LIBUSB_DIR}/libusb
        ${LIBUSB_DIR}/android)          # 提供 config.h
target_compile_definitions(usb_static PUBLIC
        _GNU_SOURCE)

# ---------------- libuvc (静态库) ----------------
# 不启用 JPEG 解码（透传 MJPEG），版本头由本仓库 gen_include 优先提供
set(LIBUVC_DIR ${third}/libuvc)
add_library(uvc_static STATIC
        ${LIBUVC_DIR}/src/ctrl.c
        ${LIBUVC_DIR}/src/ctrl-gen.c
        ${LIBUVC_DIR}/src/device.c
        ${LIBUVC_DIR}/src/diag.c
        ${LIBUVC_DIR}/src/frame.c
        ${LIBUVC_DIR}/src/init.c
        ${LIBUVC_DIR}/src/stream.c
        ${LIBUVC_DIR}/src/misc.c)
target_include_directories(uvc_static PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/gen_include   # libuvc_config.h（无需 JPEG）
        ${LIBUVC_DIR}/include)
target_link_libraries(uvc_static PUBLIC usb_static)

# ---------------- JNI 垫片 ----------------
add_library(uvcrelay SHARED uvc_jni.c)
target_link_libraries(uvcrelay
        uvc_static
        usb_static
        log)
