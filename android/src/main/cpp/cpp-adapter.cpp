#include <jni.h>
#include "AudioBrowserOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::audiobrowser::initialize(vm);
}
