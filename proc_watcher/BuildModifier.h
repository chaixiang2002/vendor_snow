#ifndef BuildModifier_h
#define BuildModifier_h

#include <jni.h>
#include <utils/Log.h>
#include <cutils/properties.h>
#include <map>
#include <string>
#include <vector>

#include <SocketConnection.h>

void setStringValue(jclass clz, JNIEnv* env, jfieldID field, std::string value);
void setStringArrayValue(jclass clz, JNIEnv* env, jfieldID field, std::string value);
void setLongValue(jclass clz, JNIEnv* env, jfieldID field, std::string value);
void setBooleanValue(jclass clz, JNIEnv* env, jfieldID field, std::string value);
void setIntegerValue(jclass clz, JNIEnv* env, jfieldID field, std::string value);

class BuildModifier {
public:
  std::vector<std::vector<std::string>> gBuildFields = {
    {"ro.build.id", "ID", "Ljava/lang/String;"},
    {"ro.build.display.id", "DISPLAY", "Ljava/lang/String;"},
    {"ro.product.name", "PRODUCT", "Ljava/lang/String;"},
    {"ro.product.device", "DEVICE", "Ljava/lang/String;"},
    {"ro.product.board", "BOARD", "Ljava/lang/String;"},
    {"ro.product.manufacturer", "MANUFACTURER", "Ljava/lang/String;"},
    {"ro.product.brand", "BRAND", "Ljava/lang/String;"},
    {"ro.product.model", "MODEL", "Ljava/lang/String;"},
    {"ro.bootloader", "BOOTLOADER", "Ljava/lang/String;"},
    {"ro.hardware", "HARDWARE", "Ljava/lang/String;"},
    {"no.such.thing", "SERIAL", "Ljava/lang/String;"},
    {"ro.product.cpu.abilist", "SUPPORTED_ABIS", "[Ljava/lang/String;"},
    {"ro.build.user", "USER", "Ljava/lang/String;"},
    {"ro.build.host", "HOST", "Ljava/lang/String;"},
    {"ro.build.type", "TYPE", "Ljava/lang/String;"},
    {"ro.build.tags", "TAGS", "Ljava/lang/String;"},
    {"ro.build.fingerprint", "FINGERPRINT", "Ljava/lang/String;"},
    {"gsm.version.baseband", "RADIO", "Ljava/lang/String;"},
    {"ro.product.cpu.abilist32", "SUPPORTED_32_BIT_ABIS", "[Ljava/lang/String;"},
    {"ro.product.cpu.abilist64", "SUPPORTED_64_BIT_ABIS", "[Ljava/lang/String;"},
    {"ro.build.date.utc", "TIME", "J"}, // * 1000
    {"ro.debuggable", "IS_DEBUGGABLE", "Z"},
    {"ro.treble.enabled", "IS_TREBLE_ENABLED", "Z"},
    {"ro.kernel.qemu", "IS_EMULATOR", "Z"},
  };

  std::vector<std::vector<std::string>> gBuildVersionFields = {
    {"ro.build.version.incremental", "INCREMENTAL", "Ljava/lang/String;"},
    {"ro.build.version.release", "RELEASE", "Ljava/lang/String;"},
    {"ro.build.version.base_os", "BASE_OS", "Ljava/lang/String;"},
    {"ro.build.version.security_patch", "SECURITY_PATCH", "Ljava/lang/String;"},
    {"ro.build.version.sdk", "SDK", "Ljava/lang/String;"},
    {"ro.build.version.preview_sdk_fingerprint", "PREVIEW_SDK_FINGERPRINT", "Ljava/lang/String;"},
    {"ro.build.version.codename", "CODENAME", "Ljava/lang/String;"},
    {"ro.build.version.min_supported_target_sdk", "MIN_SUPPORTED_TARGET_SDK_INT", "I"},
    {"ro.build.version.preview_sdk", "PREVIEW_SDK_INT", "I"},
    {"ro.build.version.all_codenames", "ALL_CODENAMES", "[Ljava/lang/String;"},
  };

  BuildModifier(JNIEnv* xenv):env(xenv) {
    mBuildClass = env->FindClass("android/os/Build");
    mVersionClass = env->FindClass("android/os/Build$VERSION");
    actions["[Ljava/lang/String;"] = setStringArrayValue;
    actions["Ljava/lang/String;"] = setStringValue;
    actions["I"] = setIntegerValue;
    actions["Z"] = setBooleanValue;
    actions["J"] = setLongValue;
  }

  ~BuildModifier() {
    env->DeleteLocalRef(mBuildClass);
    env->DeleteLocalRef(mVersionClass);
  }

  void apply();
private:
  jclass mBuildClass;
  jclass mVersionClass;
  JNIEnv* env;
  std::map<std::string, std::function<void(jclass jc, JNIEnv* env, jfieldID, std::string)>> actions;

  std::map<std::string, std::string> getApplyproperties();

  std::vector<std::string> lookupSignBuild(std::string key) {
    std::vector<std::string> result;
    for (std::vector<std::string> vector : gBuildFields) {
      if (strcmp(key.c_str(), vector[0].c_str()) == 0) {
        result.assign(vector.begin(), vector.end());;
        break;
      }
    }
    return result;
  }

  std::vector<std::string> lookupSignVersion(std::string key) {
    std::vector<std::string> result;
    for (std::vector<std::string> vector : gBuildVersionFields) {
      if (strcmp(key.c_str(), vector[0].c_str()) == 0) {
        result.assign(vector.begin(), vector.end());;
        break;
      }
    }
    return result;
  }
};

#endif /* BuildModifier_h */
