#ifndef GrantRoot_h
#define GrantRoot_h

#define LOG_TAG "GrantRoot"

#include "SocketConnection.h"

#include <jni.h>
#include <cutils/properties.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <utils/Log.h>

#define UID_ROOT      0
#define UID_SYSTEM    1000
#define UID_SHELL     2000

#define OVERLAY_MISC_DIR      "/data/overlay_misc/"
#define OVERLAY_MISC_WORKDIR  OVERLAY_MISC_DIR "workdir"
#define OVERLAY_MISC_UPPERDIR OVERLAY_MISC_DIR "upper"
#define SU_PATH               OVERLAY_MISC_UPPERDIR "/su"
#define SU_DAEMON_PATH        "/system/xbin/lyt"

class GrantRoot {
public:
  GrantRoot(uid_t uid):mUid(uid) {}

  void handleRoot() {
    if (mUid == UID_ROOT || mUid == UID_SYSTEM || mUid == UID_SHELL || checkRootable()) {
      ALOGV("startGrantRoot: %d", mUid);
      startGrantRoot();
    }
  }

private:
  uid_t mUid;
  bool startGrantRoot();
  bool checkRootable();
};

#endif /* GrantRoot_h */
