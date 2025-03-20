#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

const char kClassName[] = "com/android/server/ServerSocket";

int createServerSocket(JNIEnv *env, jobject thiz, jstring path) {
    struct sockaddr_un server_socket;

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        return -1;
    }

    int opt = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    memset(&server_socket, 0, sizeof(server_socket));
    server_socket.sun_family = AF_LOCAL;

    const char *path_c = env->GetStringUTFChars(path, NULL);
    strcpy(server_socket.sun_path, path_c);
    socklen_t
    socklen = strlen(path_c) + offsetof(
    struct sockaddr_un, sun_path);

    env->ReleaseStringUTFChars(path, path_c);

    if (bind(sock, (struct sockaddr *) &server_socket, socklen) < 0) {
        close(sock);
        return -1;
    }
    return sock;
}

static const JNINativeMethod sMethods[] = {
        {"createServerSocket", "(Ljava/lang/String;)I",
         reinterpret_cast<void *>(createServerSocket)},
};

int register_com_android_server_ServerSocket(JNIEnv *env) {
    jclass clazz = env->FindClass(kClassName);
    if (!clazz) {
        return JNI_ERR;
    }

    int result = env->RegisterNatives(clazz, sMethods, sizeof(sMethods) / sizeof(sMethods[0]));
    env->DeleteLocalRef(clazz);
    if (result != 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv *env = NULL;

    if (jvm->GetEnv((void**) &env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if (register_com_android_server_ServerSocket(env) == -1) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
