#include <jni.h>
#include "ReactNativeAudioBrowserOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::reactnativeaudiobrowser::initialize(vm);
}
