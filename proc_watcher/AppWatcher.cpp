#include <jni.h>
#include <stdio.h>

#include "GrantRoot.h"
#include "BuildModifier.h"

/**
 * Zygote fork before
 */
extern "C" void onZygoteFork(JNIEnv* env, uid_t uid) {
  BuildModifier modifier(env);
  modifier.apply();
}

/**
 * Zygote foek after
 */
extern "C" void onAppCreate(int uid) {
  GrantRoot root(uid);
  root.handleRoot();
}
