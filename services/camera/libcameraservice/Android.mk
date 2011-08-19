LOCAL_PATH:= $(call my-dir)

#
# libcameraservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    CameraService.cpp

LOCAL_SHARED_LIBRARIES:= \
    libui \
    libutils \
    libbinder \
    libcutils \
    libmedia \
    libcamera_client \
    libgui \
    libhardware

LOCAL_MODULE:= libcameraservice

ifeq ($(BOARD_CAMERA_USE_GETBUFFERINFO),true)
    LOCAL_CFLAGS += -DUSE_GETBUFFERINFO
endif

ifeq ($(BOARD_CAMERA_CUSTOM_PARAMETERS),true)
    LOCAL_CFLAGS += -DUSE_CUSTOM_PARAMETERS
endif

include $(BUILD_SHARED_LIBRARY)



ifeq ($(USE_CAMERA_STUB),true)
#
# libcamerastub
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    CameraHardwareStub.cpp      \
    FakeCamera.cpp

LOCAL_MODULE:= libcamerastub

ifeq ($(TARGET_SIMULATOR),true)
    LOCAL_CFLAGS += -DSINGLE_PROCESS
endif

LOCAL_SHARED_LIBRARIES:= libui

ifeq ($(BOARD_CAMERA_USE_GETBUFFERINFO),true)
    LOCAL_CFLAGS += -DUSE_GETBUFFERINFO
endif

ifeq ($(BOARD_CAMERA_CUSTOM_PARAMETERS),true)
    LOCAL_CFLAGS += -DUSE_CUSTOM_PARAMETERS
endif

include $(BUILD_STATIC_LIBRARY)
endif # USE_CAMERA_STUB

