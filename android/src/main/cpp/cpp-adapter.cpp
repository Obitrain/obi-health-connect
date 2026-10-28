#include <jni.h>
#include "obitrain_healthconnectOnLoad.hpp"

#include <fbjni/fbjni.h>


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::obitrain_healthconnect::registerAllNatives();
  });
}