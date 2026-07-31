LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c ghostty_vt_scan.c ghostty_terminal_backend.c
LOCAL_C_INCLUDES:= $(LOCAL_PATH)/../../../../third_party/ghostty-vt/include
LOCAL_LDLIBS:= -ldl
include $(BUILD_SHARED_LIBRARY)
