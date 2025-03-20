#include "SocketConnection.h"
#include <thread> // 添加这一行
#include <chrono> // 也需要这个头文件来使用 std::chrono

bool SocketConnection::s9handshake(const char* sock_path) {
    if (strcmp(sock_path, "/data/system/s9_sock") == 0) {
        ALOGI("SocketConnection::s9handshake /data/system/s9_sock");

        const char* initialMessage = "0004!CNCT";
        int ret1 = write(mFd, initialMessage, strlen(initialMessage));
        if (ret1 < 0) {
            ALOGW("Write socket err initialMessage: %s", strerror(errno));
            doClose();
            return false;
        }

        // char* result = new char[64];
        // memset(result, 0, 64);  // 清零 64 字节
 
        // ret1 = read(mFd, result, 64);  // 读取 64 字节
        // if (ret1 < 0) {
        //     ALOGD("ret = read(mFd, result, 64) %d", ret1);
        // }

        // free(result);  // 释放内存
    }

    return true;
}

bool SocketConnection::doOpen(const char* sock_path) {
  struct sockaddr_un un;
  struct timeval tv;
  int ret;

  if (sock_path == nullptr) {
    ALOGW("Socket path is nullptr");
    return false;
  }

  if (access(sock_path, F_OK) != 0) {
    ALOGW("Access socket err: %s", strerror(errno));
    return false;
  }

  un.sun_family = AF_UNIX;
  strcpy(un.sun_path, sock_path);

  mFd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (mFd < 0) {
    ALOGW("Create socket err: %s", strerror(errno));
    return false;
  }

  tv.tv_sec  = 1;
  tv.tv_usec = 0;
  setsockopt(mFd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
  setsockopt(mFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

  // 增加重试机制
  for (int attempts = 0; attempts < 2; ++attempts) {
    ret = connect(mFd, (struct sockaddr *)&un, sizeof(un));
    if (ret != 0) {
      ALOGW("Connect socket err for path %s: %s", sock_path, strerror(errno));
      std::this_thread::sleep_for(std::chrono::milliseconds(10)); // 等待后重试
      continue;
    }
    return true; // 连接成功
  }
  
  doClose(); // 失败后关闭
  return false;
}

bool SocketConnection::request(const char* message, char* result, int result_size) {
  int ret = write(mFd, message, strlen(message));
  if (ret < 0) {
    ALOGW("Write socket err: %s", strerror(errno));
    return false;
  }

  ret = read(mFd, result, result_size);
  if (ret < 0) {
    ALOGW("0001| Read socket err: %s", strerror(errno));
    ALOGW("0001| Write socket message: %s", message);

    return false;
  }else{
    ALOGW("0001| Write socket message succeeful: %s", message);
  }
  return true;
}

void SocketConnection::doClose() {
  if (mFd > 0) {
    close(mFd);
  }
}
