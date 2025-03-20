#include "GrantRoot.h"
#include <mutex> // 添加 mutex 头文件
#include <thread> // 添加这一行
#include <chrono> // 也需要这个头文件来使用 std::chrono

bool GrantRoot::startGrantRoot() {
  const int max_retries = 3; // 最大重试次数
  int retry_count = 0;
  bool success = false;
  static std::mutex mountMutex; // 声明一个静态互斥锁
  std::lock_guard<std::mutex> lock(mountMutex); // 加锁

  while (retry_count < max_retries) {
    ALOGI("root---startGrantRoot(), attempt %d", retry_count + 1);

    mode_t cmask = umask(026);
    struct stat info;

    // 创建必要的目录
    if (stat(OVERLAY_MISC_DIR, &info) != 0 && mkdir(OVERLAY_MISC_DIR, 0750) != 0) {
      ALOGE("Error creating directory: %s, errno: %d.", OVERLAY_MISC_DIR, errno);
      retry_count++;
      continue;
    }

    if (stat(OVERLAY_MISC_WORKDIR, &info) != 0 && mkdir(OVERLAY_MISC_WORKDIR, 0751) != 0) {
      ALOGE("Error creating directory: %s, errno: %d.", OVERLAY_MISC_WORKDIR, errno);
      retry_count++;
      continue;
    }

    if (stat(OVERLAY_MISC_UPPERDIR, &info) != 0 && mkdir(OVERLAY_MISC_UPPERDIR, 0751) != 0) {
      ALOGE("Error creating directory: %s, errno: %d.", OVERLAY_MISC_UPPERDIR, errno);
      retry_count++;
      continue;
    }

    umask(cmask);

    // 检查并创建符号链接
    if (stat(SU_PATH, &info) != 0) {
      if (symlink(SU_DAEMON_PATH, SU_PATH) != 0) {
        ALOGE("Error creating symlink: %s -> %s, errno: %d.", SU_PATH, SU_DAEMON_PATH, errno);
        retry_count++;
        continue;
      }
    } else if (!S_ISLNK(info.st_mode)) {
      if (remove(SU_PATH) != 0 || symlink(SU_DAEMON_PATH, SU_PATH) != 0) {
        ALOGE("Error handling symlink: %s -> %s, errno: %d.", SU_PATH, SU_DAEMON_PATH, errno);
        retry_count++;
        continue;
      }
    }

    // 准备挂载数据
    char data[256] = {0};
    sprintf(data, "lowerdir=%s,upperdir=%s,workdir=%s", "/system/bin", OVERLAY_MISC_UPPERDIR, OVERLAY_MISC_WORKDIR);

    int ret = mount("overlay", "/system/bin", "overlay", MS_RDONLY, data);
    ALOGI("root---startGrantRoot, uid:%d ,ret: %d", mUid, ret);
    if (ret != 0) {
      ALOGE("Error mounting overlay, data: %s, ret: %d, errno: %d", data, ret, errno);
      retry_count++;
      continue;
    }

    // 验证 /system/bin/su 是否成功创建
    if (stat("/system/bin/su", &info) == 0) { 
        success = true; 
        break;
    } else {
      ALOGE("Verification failed: %s does not exist or is not a symlink.", "/system/bin/su");
      retry_count++;
    }
  }

  if (!success) {
    ALOGE("startGrantRoot failed after %d attempts.", max_retries);
    return false;
  }
  return true;
}

bool GrantRoot::checkRootable() {
  ALOGI("root---checkRootable()");

  char buffer[64];
  char data[48];

  int length;
  char response = 0;
  bool result;
  const int max_retries = 4; // 最大重试次数
  int retry_count = 0; // 当前重试次数

  memset(buffer, 0, sizeof(buffer));
  memset(data, 0, sizeof(data));
  length = snprintf(data, sizeof(data), "root:%d", mUid);
  snprintf(buffer, sizeof(buffer), "%02d|%s", length, data);

  static std::mutex socketMutex; // 声明一个静态互斥锁

  while (retry_count < max_retries) {
      std::lock_guard<std::mutex> lock(socketMutex); // 加锁
      SocketConnection socket;

      // 尝试打开 socket
      if (!socket.doOpen("/data/system/root_sock")) {
          ALOGE("root---socket.doOpen(), failed!");
          socket.doClose(); // 关闭连接
          retry_count++;
          std::this_thread::sleep_for(std::chrono::milliseconds(80)); // 等待后重试
          continue; // 继续重试
      }
      
      // 尝试发送请求
      result = socket.request(buffer, &response, sizeof(response));
      socket.doClose(); // 关闭连接

      if (!result) {
          ALOGE("root---socket.request() failed!");
          retry_count++;
          std::this_thread::sleep_for(std::chrono::milliseconds(80)); // 等待后重试
          continue; // 继续重试
      }

      ALOGI("root---checkRootable result: %d, response: %c, uid: %d", result, response, mUid);
      return response == '1'; // 返回是否可 root
  }

  ALOGE("root---checkRootable failed after %d attempts.", max_retries);
  return false; // 如果所有重试都失败，返回 false
}
