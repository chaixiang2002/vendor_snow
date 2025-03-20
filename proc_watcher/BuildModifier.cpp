#include "BuildModifier.h"

static std::vector<std::string> split_array_string(std::string str, std::string pattern) {
  std::string::size_type pos;
  std::vector<std::string> result;

  str += pattern;
  int size = str.size();

  for(int i = 0; i < size;) {
    pos = str.find(pattern, i);
    if (pos < size && pos != std::string::npos) {
      std::string s = str.substr(i, pos - i);
      result.push_back(s);
      i = pos + pattern.size();
    }
  }
  return result;
}

static std::map<std::string, std::string> parse_response(const std::string& queryString) {
  std::map<std::string, std::string> keyValuePairs;
  size_t start = 0;
  size_t end = 0;

  while (end != std::string::npos) {
    end = queryString.find('&', start);
    std::string keyValue = queryString.substr(start, (end == std::string::npos) ? std::string::npos : end - start);

    size_t equalSignPos = keyValue.find('=');
    if (equalSignPos != std::string::npos) {
      std::string key = keyValue.substr(0, equalSignPos);
      std::string value = keyValue.substr(equalSignPos + 1);
      keyValuePairs[key] = value;
    }

    if (end != std::string::npos) {
      start = end + 1;
    }
  }
  return keyValuePairs;
}

void setStringValue(jclass jc, JNIEnv* env, jfieldID field, std::string value) {
  jstring stringValue = env->NewStringUTF(value.c_str());
  env->SetStaticObjectField(jc, field, stringValue);
  env->DeleteLocalRef(stringValue);
}

void setStringArrayValue(jclass jc, JNIEnv* env, jfieldID field, std::string value) {
  std::vector<std::string> vector = split_array_string(value, ",");
  int length = vector.size();

  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray strArray = env->NewObjectArray(length, stringClass, 0);

  int i = 0;
  jstring stringValue;

  for (auto &val : vector) {
    stringValue = env->NewStringUTF(val.c_str());
    env->SetObjectArrayElement(strArray, i, stringValue);
    env->DeleteLocalRef(stringValue);
    i++;
  }

  env->SetStaticObjectField(jc, field, strArray);

  jobject object;
  for (i = 0; i < length; i++) {
    object = env->GetObjectArrayElement(strArray, i);
    env->DeleteLocalRef(object);
  }
  env->DeleteLocalRef(strArray);
  env->DeleteLocalRef(stringClass);
}

void setLongValue(jclass jc, JNIEnv* env, jfieldID field, std::string value) {
  int64_t l_value = atol(value.c_str());
  env->SetStaticLongField(jc, field, l_value);
}

void setBooleanValue(jclass jc, JNIEnv* env, jfieldID field, std::string value) {
  transform(value.begin(), value.end(), value.begin(), ::tolower);
  if (value.compare("true") == 0) {
    env->SetStaticBooleanField(jc, field, JNI_TRUE);
  } else {
    env->SetStaticBooleanField(jc, field, JNI_FALSE);
  }
}

void setIntegerValue(jclass jc, JNIEnv* env, jfieldID field, std::string value) {
  env->SetStaticIntField(jc, field, atoi(value.c_str()));
}

std::map<std::string, std::string> BuildModifier::getApplyproperties() {
  std::map<std::string, std::string> properties;

  char protocol[1024];
  char buffer[1024];
  int length;
  bool result;
  char response[4090];
  SocketConnection socket;

  // 打开 socket 连接
  if (!socket.doOpen("/data/system/s9_sock")) {
    return properties;
  }

  memset(response, 0, sizeof(response));
  memset(buffer, 0, sizeof(buffer));
  memset(protocol, 0, sizeof(protocol));

  // 构造协议消息
  length = snprintf(protocol, sizeof(protocol), "prop:%d", sizeof(void*) != sizeof(uint64_t) ? 1 : 0);
  snprintf(buffer, sizeof(buffer), "%04d!%s", length, protocol);

  // 请求数据
  result = socket.s9handshake(("/data/system/s9_sock"));
  result = socket.request(buffer, response, sizeof(response));

  // 关闭 socket
  socket.doClose();

  // 检查请求结果
  if (!result) {
    ALOGW("0001|  BuildModifier.cpp socket.request err:");
    return properties;
  }

  // 处理响应
  length = strlen(response);
  if (length == 0) {
    return properties;
  }

  const std::string str_response(response);
  properties = parse_response(str_response);

  return properties;
}

void BuildModifier::apply() {
  std::map<std::string, std::string> propertyMap = getApplyproperties();
  std::vector<std::string> vector;
  jfieldID field;

  for (auto it = propertyMap.begin(); it != propertyMap.end(); ++it) {
    vector = lookupSignBuild(it->first);

    if (vector.size() != 0) {
      ALOGV("%s:%s:%s", vector[0].c_str(), vector[1].c_str(), vector[2].c_str());
      field = env->GetStaticFieldID(mBuildClass, vector[1].c_str(), vector[2].c_str());
      if (field == nullptr) { continue; }

      if (actions.find(vector[2]) != actions.end()) {
        actions[vector[2].c_str()](mBuildClass, env, field, it->second);
        env->ExceptionClear();
      } else {
        ALOGW("Apply: key is not supported: %s", it->first.c_str());
      }
    } else {
      vector = lookupSignVersion(it->first);
      if (vector.size() != 0) {
        ALOGV("%s:%s:%s", vector[0].c_str(), vector[1].c_str(), vector[2].c_str());

        if (actions.find(vector[2]) != actions.end()) {
          actions[vector[2].c_str()](mVersionClass, env, field, it->second);
          env->ExceptionClear();
        } else {
          ALOGW("Apply: (version) key is not supported: %s", it->first.c_str());
        }
      }
    }
  }
}
