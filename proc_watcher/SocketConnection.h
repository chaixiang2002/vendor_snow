#ifndef SocketConnection_h
#define SocketConnection_h

#include <errno.h>
#include <utils/Log.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mount.h>
#include <unistd.h>

class SocketConnection {
public:
  bool doOpen(const char* path);
  bool s9handshake(const char* path);
  bool request(const char* message, char* result, int size);
  void doClose();
private:
  int mFd;
};

#endif /* SocketConnection_h */
