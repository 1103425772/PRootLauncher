LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)

# ---- qemu-user 依赖库（带 SONAME 版本号）----
# AGP jniLibs 只收 lib*.so，ndk-build LOCAL_SRC_FILES 也必须 .so 结尾。
# 在 prebuilt/<abi>/ 里存 .so 名，用 LOCAL_MODULE_FILENAME 输出回 SONAME 原名。
# Android 16 禁止从 app 私有 files/ 目录 mmap 可执行 .so（W^X），
# 这些依赖库必须和 qemu 一起进 nativeLibraryDir，linker 从 $ORIGIN 直接找。

PB := prebuilt/$(TARGET_ARCH_ABI)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_glib
LOCAL_SRC_FILES := $(PB)/libglib-2.0.so
LOCAL_MODULE_FILENAME := libglib-2.0.so.0
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_gmodule
LOCAL_SRC_FILES := $(PB)/libgmodule-2.0.so
LOCAL_MODULE_FILENAME := libgmodule-2.0.so.0
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_dw
LOCAL_SRC_FILES := $(PB)/libdw.so
LOCAL_MODULE_FILENAME := libdw.so.1
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_elf
LOCAL_SRC_FILES := $(PB)/libelf.so
LOCAL_MODULE_FILENAME := libelf.so.1
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_hogweed
LOCAL_SRC_FILES := $(PB)/libhogweed.so
LOCAL_MODULE_FILENAME := libhogweed.so.6
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_nettle
LOCAL_SRC_FILES := $(PB)/libnettle.so
LOCAL_MODULE_FILENAME := libnettle.so.8
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_z
LOCAL_SRC_FILES := $(PB)/libz.so
LOCAL_MODULE_FILENAME := libz.so.1
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_zstd
LOCAL_SRC_FILES := $(PB)/libzstd.so
LOCAL_MODULE_FILENAME := libzstd.so.1
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_lzma
LOCAL_SRC_FILES := $(PB)/liblzma.so
LOCAL_MODULE_FILENAME := liblzma.so.5
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := qb_bz2
LOCAL_SRC_FILES := $(PB)/libbz2.so
LOCAL_MODULE_FILENAME := libbz2.so.1.0
include $(PREBUILT_SHARED_LIBRARY)
