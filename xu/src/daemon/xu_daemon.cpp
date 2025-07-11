// src/daemon/xu_daemon.cpp
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <com/xu/service/BnXu.h>
#include <cutils/multiuser.h>
#include <private/android_filesystem_config.h>
#include <selinux/selinux.h>
#include <utils/String16.h>
#include <binder/ParcelFileDescriptor.h>
#include <array>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <pty.h>
#include <termios.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <signal.h> // For signal handling
#include <android-base/unique_fd.h> // Include for unique_fd
#include <sys/prctl.h> // For prctl
#include <thread>
#include <sys/socket.h>
#include <atomic>
#include <map>

using android::sp;
using android::String16;
using android::binder::Status;
using android::os::ParcelFileDescriptor;
using android::base::unique_fd; // Add using directive
using android::base::GetBoolProperty; // Added for system property access

// Helper function to convert vector<String16> to vector<char*>
std::vector<char*> convertString16VecToCharVec(const std::vector<android::String16>& string16Vec) {
    std::vector<char*> charVec;
    // Need to store string data persistently for argv
    static std::vector<std::string> stringStorage;
    stringStorage.clear();

    for (const auto& s16 : string16Vec) {
        stringStorage.push_back(android::String8(s16).string());
    }
    for (const auto& str : stringStorage) {
        charVec.push_back(const_cast<char*>(str.c_str()));
    }
    charVec.push_back(nullptr); // Null terminate argv
    return charVec;
}

// Helper function to set process group ID (No longer called directly)
void setPgid() {
    if (setpgid(0, 0) < 0) {
        PLOG(ERROR) << "setpgid failed";
        _exit(1);
    }
}

// Helper function to setup environment for user
void setupUserEnvironment(uid_t uid, const std::vector<std::string>& envVars) {
    bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);
    if (debug_logging_enabled) {
        LOG(INFO) << "setupUserEnvironment for UID: " << uid << " with " << envVars.size() << " environment variables";
    }
    
    // 不清空环境变量，而是保留客户端的环境变量并添加/覆盖必要的系统变量
    // clearenv(); // 移除这行
    
    // 首先设置客户端传递的环境变量（格式为"key=value"）
    for (const auto& envStr : envVars) {
        size_t equalPos = envStr.find('=');
        if (equalPos != std::string::npos) {
            std::string key = envStr.substr(0, equalPos);
            std::string value = envStr.substr(equalPos + 1);
            setenv(key.c_str(), value.c_str(), 1);
            if (debug_logging_enabled) {
                LOG(DEBUG) << "Set env: " << key << "=" << value;
            }
        } else {
            if (debug_logging_enabled) {
                LOG(WARNING) << "Invalid environment variable format (missing '='): " << envStr;
            }
        }
    }
    
    // 然后设置或覆盖系统必需的环境变量
    setenv("HOME", "/", 1);
    setenv("SHELL", "/system/bin/sh", 1);

    std::string username = "root";
    if (uid >= AID_APP) {
        username = "u0_a" + std::to_string(uid - AID_APP);
        setenv("USER_ID", "1", 1);  // 非 root 显示 $
    } else {
        setenv("USER_ID", "0", 1);  // root 显示 #
    }
    if (debug_logging_enabled) {
        LOG(DEBUG) << "Setting USER to: " << username;
        LOG(DEBUG) << "Setting HOSTNAME to: " << username;
    }
    // 用户名相关环境变量
    setenv("USER", username.c_str(), 1);
    setenv("LOGNAME", username.c_str(), 1);
    setenv("USERNAME", username.c_str(), 1);
    
    // 设置 hostname 为用户名，最终 PS1 就是 root:/ #
    setenv("HOSTNAME", username.c_str(), 1);

    // 确保PATH包含必要的系统路径，但保留客户端的PATH如果存在
    const char* existing_path = getenv("PATH");
    std::string system_paths = "/system/bin:/vendor/bin:/sbin:/system/xbin";
    if (existing_path && strlen(existing_path) > 0) {
        std::string combined_path = std::string(existing_path) + ":" + system_paths;
        setenv("PATH", combined_path.c_str(), 1);
        if (debug_logging_enabled) {
            LOG(DEBUG) << "Combined PATH: " << combined_path;
        }
    } else {
        setenv("PATH", system_paths.c_str(), 1);
        if (debug_logging_enabled) {
            LOG(DEBUG) << "Set default PATH: " << system_paths;
        }
    }
    
    if (debug_logging_enabled) {
        LOG(DEBUG) << "Environment setup complete for UID: " << uid;
    }
}

// --- 添加 SIGCHLD 信号处理函数 ---
// SIGCHLD 信号处理函数
void handle_sigchld(int sig) {
    (void)sig; // 避免 unused parameter 警告
    // bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);
    // ^^^ Reading property in signal handler is generally not safe. 
    // If logging is absolutely needed here, it should be via a pre-set global/static flag updated outside handler.
    // For now, keeping original behavior with its commented-out logs, or making them very cautiously conditional.

    int saved_errno = errno; // waitpid 可能会修改 errno

    while (waitpid(-1, nullptr, WNOHANG) > 0) {
        // if (debug_logging_enabled) { // Still risky to log complex things here
        //    LOG(INFO) << "Reaped a child process in handle_sigchld."; 
        // }
    }

    if (errno != ECHILD) {
        // if (debug_logging_enabled) { // Risky
        //    PLOG(ERROR) << "waitpid failed in SIGCHLD handler (errno != ECHILD)";
        // }
    }
    errno = saved_errno;
}

// --- 添加安装信号处理器的函数 ---
// 在服务初始化的地方安装信号处理器
void install_signal_handler() {
    bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);
    if (debug_logging_enabled) {
        LOG(INFO) << "Attempting to install SIGCHLD handler.";
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = handle_sigchld;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART | SA_NOCLDSTOP;

    if (sigaction(SIGCHLD, &sa, nullptr) == -1) {
        PLOG(ERROR) << "Failed to install SIGCHLD handler";
    } else {
        if (debug_logging_enabled) {
            LOG(INFO) << "SIGCHLD handler installed successfully.";
        }
    }
}

namespace com
{
    namespace xu
    {
        namespace service
        {
            // 添加全局连接管理
            static std::atomic<int> active_pty_sessions{0};
            static std::atomic<int> active_streaming_sessions{0};
            static std::atomic<int> active_batch_commands{0};
            static const int MAX_PTY_SESSIONS = 10;
            static const int MAX_STREAMING_SESSIONS = 20;
            static const int MAX_BATCH_COMMANDS = 50;

            class XuService : public BnXu
            {
            public:
                Status createPtySession(int32_t uid, const std::vector<android::String16>& commandArgs, 
                                       const std::vector<android::String16>& envVars,
                                       ::android::os::ParcelFileDescriptor* _aidl_return) override {
                    bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);
                    
                    // 检查连接数限制
                    int current_pty_sessions = active_pty_sessions.load();
                    if (current_pty_sessions >= MAX_PTY_SESSIONS) {
                        LOG(WARNING) << "PTY session limit reached: " << current_pty_sessions << "/" << MAX_PTY_SESSIONS;
                        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "Too many PTY sessions");
                    }
                    
                    if (debug_logging_enabled) {
                        LOG(INFO) << "createPtySession called for UID: " << uid << " (active sessions: " << current_pty_sessions << ")";
                        std::string commandForLog_pty;
                        for (const auto& arg : commandArgs) {
                            commandForLog_pty += android::String8(arg).string();
                            commandForLog_pty += " ";
                        }
                        if (!commandForLog_pty.empty()) {
                            commandForLog_pty.pop_back(); // Remove trailing space
                        }
                        LOG(INFO) << "PTY Command: [" << commandForLog_pty << "]";
                        LOG(INFO) << "Environment variables count: " << envVars.size();
                    }

                     if (getuid() != 0)
                    {
                        // 安全相关错误，始终输出
                        LOG(ERROR) << "createPtySession: permission denied (not root).";
                        return Status::fromExceptionCode(Status::EX_SECURITY, "Requires root");
                    }
                    
                    if (!_aidl_return) { // Should not happen
                        // 系统错误，始终输出
                        LOG(ERROR) << "createPtySession: _aidl_return is null.";
                        return Status::fromExceptionCode(Status::EX_NULL_POINTER, "Output ParcelFileDescriptor pointer is null");
                    }

                    // 增加活跃会话计数
                    active_pty_sessions++;

                    int master_fd = -1;
                    pid_t pid = forkpty(&master_fd, nullptr, nullptr, nullptr);

                    if (pid < 0) {
                        active_pty_sessions--; // 回滚计数
                        // 系统错误，始终输出
                        PLOG(ERROR) << "createPtySession: forkpty failed";
                        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "forkpty failed");
                    }
                    if (debug_logging_enabled) {
                        LOG(DEBUG) << "createPtySession: forkpty successful. Master FD: " << master_fd << ", Child PID: " << pid;
                    }

                    if (pid == 0) { // Child process (slave side)
                        // 子进程不需要管理计数器，只需要执行任务并退出
                        if (debug_logging_enabled) {
                            LOG(INFO) << "PTY Child (PID: " << getpid() << ") started.";
                        }
                        close(master_fd); 

                        if (uid != 0) { 
                            if (debug_logging_enabled) {
                                LOG(INFO) << "PTY Child (PID: " << getpid() << "): Attempting to switch to UID: " << uid;
                            }
                             if (setresgid(uid, uid, uid) != 0 || setresuid(uid, uid, uid) != 0)
                            {
                                // 安全相关错误，始终输出
                                PLOG(ERROR) << "PTY Child (PID: " << getpid() << "): Failed to switch user to " << uid;
                                _exit(1);
                            }
                            if (debug_logging_enabled) {
                                LOG(INFO) << "PTY Child (PID: " << getpid() << "): Switched to UID: " << uid << " successfully.";
                            }
                            // 转换String16数组为std::string数组
                            std::vector<std::string> envVarsStd;
                            for (const auto& envVar : envVars) {
                                envVarsStd.push_back(android::String8(envVar).string());
                            }
                            setupUserEnvironment(uid, envVarsStd);
                        } else {
                            if (debug_logging_enabled) {
                                LOG(INFO) << "PTY Child (PID: " << getpid() << "): Running as root (UID: 0).";
                            }
                            // 转换String16数组为std::string数组
                            std::vector<std::string> envVarsStd;
                            for (const auto& envVar : envVars) {
                                envVarsStd.push_back(android::String8(envVar).string());
                            }
                            setupUserEnvironment(0, envVarsStd);
                        }

                        const char* shell_path = "/system/bin/sh";
                        std::vector<char*> argv;
                        static std::string command_storage_pty; // Keep static for argv lifetime

                        if (!commandArgs.empty()) {
                            command_storage_pty.clear();
                            for (size_t i = 0; i < commandArgs.size(); ++i) {
                                if (i > 0) command_storage_pty += " ";
                                command_storage_pty += android::String8(commandArgs[i]).string();
                            }
                            if (debug_logging_enabled) {
                                LOG(INFO) << "PTY Child (PID: " << getpid() << "): Executing via sh -c " << command_storage_pty;
                            }
                            argv.push_back(const_cast<char*>(shell_path));
                            argv.push_back(const_cast<char*>("-c"));
                            argv.push_back(const_cast<char*>(command_storage_pty.c_str()));
                            argv.push_back(nullptr);
                        } else {
                            // command_storage_pty = "export PS1='[" + std::to_string(uid) + "]:/ $ '; exec /system/bin/sh";
                            // Simplified for logging clarity, the actual PS1 logic can be complex
                            std::string ps1_export_cmd = "export PS1='[" + std::to_string(uid) + "]:/ $ '; exec /system/bin/sh";
                            command_storage_pty = ps1_export_cmd;
                            if (debug_logging_enabled) {
                                LOG(INFO) << "PTY Child (PID: " << getpid() << "): Starting interactive shell with command: " << command_storage_pty;
                            }
                            argv.push_back(const_cast<char*>(shell_path));
                            argv.push_back(const_cast<char*>("-c"));
                            argv.push_back(const_cast<char*>(command_storage_pty.c_str()));
                            argv.push_back(nullptr);
                        }

                        if (execvp(shell_path, argv.data()) < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "PTY Child (PID: " << getpid() << "): execvp failed for " << shell_path;
                            _exit(1);
                        }
                        _exit(1);
                    } else { // Parent process (master side)
                         if (debug_logging_enabled) {
                            LOG(INFO) << "PTY Parent (PID: " << getpid() << "), child PID: " << pid;
                        }
                        
                        // 在后台线程中监控子进程退出，减少活跃会话计数
                        std::thread([pid]() {
                            int status;
                            waitpid(pid, &status, 0);
                            active_pty_sessions--;
                            bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);
                            if (debug_logging_enabled) {
                                LOG(INFO) << "PTY session ended, child PID: " << pid << ", remaining sessions: " << active_pty_sessions.load();
                            }
                        }).detach();
                        
                        int dup_fd = dup(master_fd);
                        if (dup_fd < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "PTY Parent (PID: " << getpid() << "): Failed to dup master FD (" << master_fd << ")";
                            close(master_fd);
                            // Consider killing the child: kill(pid, SIGKILL);
                            return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "Failed to dup master FD");
                        }
                        close(master_fd); 
                        if (debug_logging_enabled) {
                             LOG(DEBUG) << "PTY Parent (PID: " << getpid() << "): Duped master FD to " << dup_fd << ". Original master FD " << master_fd << " closed.";
                        }

                        unique_fd ufd(dup_fd); 
                        *_aidl_return = ParcelFileDescriptor(std::move(ufd));

                        if ((*_aidl_return).get() < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "PTY Parent (PID: " << getpid() << "): Failed to create ParcelFileDescriptor (FD < 0 from _aidl_return.get())";
                            return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "Failed to create ParcelFileDescriptor");
                        }
                        if (debug_logging_enabled) {
                            LOG(INFO) << "createPtySession returning Status::ok() for UID: " << uid;
                        }
                        return Status::ok();
                    }
                }

                Status executeBatchCommand(int32_t uid, const ::std::vector<::android::String16>& commandArgs,
                                          const std::vector<android::String16>& envVars,
                                          ::com::xu::service::CommandResult* _aidl_return) override {
                    bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);

                    // 检查连接数限制
                    int current_batch_commands = active_batch_commands.load();
                    if (current_batch_commands >= MAX_BATCH_COMMANDS) {
                        LOG(WARNING) << "Batch command limit reached: " << current_batch_commands << "/" << MAX_BATCH_COMMANDS;
                        return ::android::binder::Status::fromExceptionCode(::android::binder::Status::EX_ILLEGAL_STATE, "Too many batch commands");
                    }

                    if (debug_logging_enabled) {
                        LOG(INFO) << "executeBatchCommand called for UID: " << uid << " (active commands: " << current_batch_commands << ")";
                        std::string commandForLog;
                        for (const auto& arg : commandArgs) {
                            commandForLog += android::String8(arg).string();
                            commandForLog += " ";
                        }
                        if (!commandForLog.empty()) {
                            commandForLog.pop_back(); // Remove trailing space
                        }
                        LOG(INFO) << "Command: [" << commandForLog << "]";
                        LOG(INFO) << "Environment variables count: " << envVars.size();
                    }

                    if (getuid() != 0) {
                        // 安全相关错误，始终输出
                        LOG(ERROR) << "executeBatchCommand: permission denied (not root).";
                        return ::android::binder::Status::fromExceptionCode(::android::binder::Status::EX_SECURITY, "Requires root");
                    }

                    if (commandArgs.empty()) {
                        // 参数错误，始终输出
                        LOG(ERROR) << "executeBatchCommand: commandArgs is empty.";
                        return ::android::binder::Status::fromExceptionCode(::android::binder::Status::EX_ILLEGAL_ARGUMENT, "Command cannot be empty for batch execution");
                    }

                    if (!_aidl_return) { 
                        // 系统错误，始终输出
                        LOG(ERROR) << "executeBatchCommand: _aidl_return is null.";
                        return ::android::binder::Status::fromExceptionCode(::android::binder::Status::EX_NULL_POINTER, "Output CommandResult pointer cannot be null");
                    }

                    // 增加活跃命令计数
                    active_batch_commands++;

                    _aidl_return->output = android::String16("");
                    _aidl_return->exitCode = -1; 
                    _aidl_return->stderrOutput = android::String16(""); // Initialize stderrOutput
                    if (debug_logging_enabled) {
                        LOG(DEBUG) << "executeBatchCommand: Initialized _aidl_return.";
                    }

                    int stdout_pipe[2];
                    int stderr_pipe[2]; // Pipe for stderr

                    if (pipe(stdout_pipe) < 0) {
                        // 系统错误，始终输出
                        PLOG(ERROR) << "executeBatchCommand: pipe for stdout failed";
                        return ::android::binder::Status::fromExceptionCode(::android::binder::Status::EX_ILLEGAL_STATE, "stdout pipe failed");
                    }
                    if (pipe(stderr_pipe) < 0) { // Create stderr pipe
                        // 系统错误，始终输出
                        PLOG(ERROR) << "executeBatchCommand: pipe for stderr failed";
                        close(stdout_pipe[0]);
                        close(stdout_pipe[1]);
                        return ::android::binder::Status::fromExceptionCode(::android::binder::Status::EX_ILLEGAL_STATE, "stderr pipe failed");
                    }
                    if (debug_logging_enabled) {
                        LOG(DEBUG) << "executeBatchCommand: Stdout and stderr pipes created successfully.";
                    }

                    pid_t pid = fork();

                    if (pid < 0) {
                        // 系统错误，始终输出
                        PLOG(ERROR) << "executeBatchCommand: fork failed";
                        close(stdout_pipe[0]);
                        close(stdout_pipe[1]);
                        close(stderr_pipe[0]);
                        close(stderr_pipe[1]);
                        return ::android::binder::Status::fromExceptionCode(::android::binder::Status::EX_ILLEGAL_STATE, "fork failed");
                    }

                    if (pid == 0) { // Child process
                        if (debug_logging_enabled) {
                            LOG(INFO) << "Child process (PID: " << getpid() << ") started.";
                        }
                        close(stdout_pipe[0]); // Close read end of stdout pipe in child
                        close(stderr_pipe[0]); // Close read end of stderr pipe in child

                        if (dup2(stdout_pipe[1], STDOUT_FILENO) < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "Child (PID: " << getpid() << "): dup2 stdout failed";
                            close(stdout_pipe[1]);
                            close(stderr_pipe[1]);
                            _exit(126); 
                        }
                        if (dup2(stderr_pipe[1], STDERR_FILENO) < 0) { // Redirect stderr
                            // 系统错误，始终输出
                            PLOG(ERROR) << "Child (PID: " << getpid() << "): dup2 stderr failed";
                            // STDOUT is already redirected, close its write end before exiting to avoid hanging parent.
                            close(stdout_pipe[1]); 
                            close(stderr_pipe[1]);
                            _exit(126); 
                        }

                        if (debug_logging_enabled) {
                            LOG(DEBUG) << "Child (PID: " << getpid() << "): stdout and stderr redirected to pipes.";
                        }
                        // Now that descriptors are duplicated, close the original write ends in child
                        close(stdout_pipe[1]);
                        close(stderr_pipe[1]);

                        if (uid != 0) {
                            if (debug_logging_enabled) {
                                LOG(INFO) << "Child (PID: " << getpid() << "): Attempting to switch to UID: " << uid;
                            }
                            if (setresgid(uid, uid, uid) != 0 || setresuid(uid, uid, uid) != 0) {
                                // 安全相关错误，始终输出
                                PLOG(ERROR) << "Child (PID: " << getpid() << "): Failed to switch user to " << uid;
                                _exit(125);
                            }
                            if (debug_logging_enabled) {
                                LOG(INFO) << "Child (PID: " << getpid() << "): Switched to UID: " << uid << " successfully.";
                            }
                            // 转换String16数组为std::string数组
                            std::vector<std::string> envVarsStd;
                            for (const auto& envVar : envVars) {
                                envVarsStd.push_back(android::String8(envVar).string());
                            }
                            setupUserEnvironment(uid, envVarsStd);
                        } else {
                            if (debug_logging_enabled) {
                                LOG(INFO) << "Child (PID: " << getpid() << "): Running as root (UID: 0).";
                            }
                            // 转换String16数组为std::string数组
                            std::vector<std::string> envVarsStd;
                            for (const auto& envVar : envVars) {
                                envVarsStd.push_back(android::String8(envVar).string());
                            }
                            setupUserEnvironment(0, envVarsStd);
                        }

                        // Construct the full command string from commandArgs
                        std::string full_command_str;
                        if (!commandArgs.empty()) {
                            for (size_t i = 0; i < commandArgs.size(); ++i) {
                                full_command_str += android::String8(commandArgs[i]).string();
                                if (i < commandArgs.size() - 1) {
                                    full_command_str += " "; // Add space between arguments
                                }
                            }
                        } else {
                            // This case should be prevented by the check at the function start
                            // 系统错误，始终输出
                            LOG(ERROR) << "Child (PID: " << getpid() << "): commandArgs is unexpectedly empty here.";
                            _exit(124);
                        }

                        if (debug_logging_enabled) {
                            LOG(INFO) << "Child (PID: " << getpid() << "): Executing with sh -c: [" << full_command_str << "]";
                        }
                        
                        // Execute the command via sh -c
                        execlp("/system/bin/sh", "sh", "-c", full_command_str.c_str(), (char *)NULL);
                        
                        // If execlp returns, it means an error occurred
                        // 系统错误，始终输出
                        PLOG(ERROR) << "Child (PID: " << getpid() << "): execlp sh -c failed for command: [" << full_command_str << "]";
                        _exit(127); 
                    } else { // Parent process
                        if (debug_logging_enabled) {
                            LOG(INFO) << "Parent process (PID: " << getpid() << "), child PID: " << pid;
                        }
                        close(stdout_pipe[1]); // Close write end of stdout pipe in parent
                        close(stderr_pipe[1]); // Close write end of stderr pipe in parent

                        std::string output_str_std;
                        std::string stderr_str_std; // String for stderr
                        char buffer[4096];
                        ssize_t bytes_read;
                        
                        // Read stdout
                        if (debug_logging_enabled) {
                            LOG(DEBUG) << "Parent (PID: " << getpid() << "): Reading from child (PID: " << pid << ") stdout pipe.";
                        }
                        while ((bytes_read = read(stdout_pipe[0], buffer, sizeof(buffer) - 1)) > 0) {
                            buffer[bytes_read] = '\0';
                            output_str_std += buffer;
                        }
                        close(stdout_pipe[0]);
                        if (debug_logging_enabled) {
                            LOG(DEBUG) << "Parent (PID: " << getpid() << "): Finished reading from stdout pipe. Total bytes read: " << output_str_std.length();
                        }
                        if (bytes_read < 0 && debug_logging_enabled) { // Log only if debug enabled, as it might be normal EOF if pipe was closed by child before write
                             PLOG(DEBUG) << "Parent (PID: " << getpid() << "): read from child stdout pipe returned " << bytes_read;
                        }

                        // Read stderr
                        if (debug_logging_enabled) {
                            LOG(DEBUG) << "Parent (PID: " << getpid() << "): Reading from child (PID: " << pid << ") stderr pipe.";
                        }
                        while ((bytes_read = read(stderr_pipe[0], buffer, sizeof(buffer) - 1)) > 0) {
                            buffer[bytes_read] = '\0';
                            stderr_str_std += buffer;
                        }
                        close(stderr_pipe[0]);
                        if (debug_logging_enabled) {
                            LOG(DEBUG) << "Parent (PID: " << getpid() << "): Finished reading from stderr pipe. Total bytes read: " << stderr_str_std.length();
                        }
                         if (bytes_read < 0 && debug_logging_enabled) {
                             PLOG(DEBUG) << "Parent (PID: " << getpid() << "): read from child stderr pipe returned " << bytes_read;
                        }
                        
                        _aidl_return->output = android::String16(output_str_std.c_str());
                        _aidl_return->stderrOutput = android::String16(stderr_str_std.c_str()); // Populate stderrOutput

                        int status_val;
                        if (debug_logging_enabled) {
                            LOG(DEBUG) << "Parent (PID: " << getpid() << "): Waiting for child (PID: " << pid << ") to exit.";
                        }
                        waitpid(pid, &status_val, 0);
                        
                        // 减少活跃命令计数
                        active_batch_commands--;
                        
                        if (debug_logging_enabled) {
                            LOG(INFO) << "Parent (PID: " << getpid() << "): Child (PID: " << pid << ") exited with raw status: " << status_val;
                        }

                        if (WIFEXITED(status_val)) {
                            _aidl_return->exitCode = WEXITSTATUS(status_val);
                            if (debug_logging_enabled) {
                                LOG(INFO) << "Parent (PID: " << getpid() << "): Child (PID: " << pid << ") exited normally with code: " << _aidl_return->exitCode;
                            }
                        } else if (WIFSIGNALED(status_val)) {
                            _aidl_return->exitCode = 128 + WTERMSIG(status_val);
                            // This is a WARNING, let's keep it if it happens, or make it conditional too?
                            // For now, making it conditional as it's part of detailed flow.
                            if (debug_logging_enabled) {
                                LOG(WARNING) << "Parent (PID: " << getpid() << "): Child (PID: " << pid << ") terminated by signal: " << WTERMSIG(status_val) << " (assigned exit code: " << _aidl_return->exitCode << ")";
                            }
                        } else {
                            _aidl_return->exitCode = -1; // Unknown exit reason
                            // 异常状态，始终输出
                            LOG(ERROR) << "Parent (PID: " << getpid() << "): Child (PID: " << pid << ") exited with unknown status.";
                        }
                        if (debug_logging_enabled) {
                            LOG(INFO) << "executeBatchCommand: Populated _aidl_return with output (length: " 
                                      << output_str_std.length() << "), stderr (length: " << stderr_str_std.length() 
                                      << ") and exitCode: " << _aidl_return->exitCode;
                            LOG(INFO) << "executeBatchCommand returning Status::ok() for UID: " << uid << " (remaining commands: " << active_batch_commands.load() << ")";
                        }
                        return ::android::binder::Status::ok();
                    }
                }

                Status createStreamingShellSession(int32_t uid, const std::vector<android::String16>& envVars,
                                                  ::android::os::ParcelFileDescriptor* _aidl_return) override {
                    bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);
                    
                    // 检查连接数限制
                    int current_streaming_sessions = active_streaming_sessions.load();
                    if (current_streaming_sessions >= MAX_STREAMING_SESSIONS) {
                        LOG(WARNING) << "Streaming session limit reached: " << current_streaming_sessions << "/" << MAX_STREAMING_SESSIONS;
                        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "Too many streaming sessions");
                    }
                    
                    if (debug_logging_enabled) {
                        LOG(INFO) << "createStreamingShellSession called for UID: " << uid << " (active sessions: " << current_streaming_sessions << ")";
                        LOG(INFO) << "Environment variables count: " << envVars.size();
                    }
                    
                    // 增加活跃会话计数
                    active_streaming_sessions++;
                    
                    // 创建双向管道
                    int to_shell[2], from_shell[2];
                    if (pipe(to_shell) < 0 || pipe(from_shell) < 0) {
                        active_streaming_sessions--; // 回滚计数
                        // 系统错误，始终输出
                        PLOG(ERROR) << "createStreamingShellSession: pipe failed";
                        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "pipe failed");
                    }
                    
                    if (debug_logging_enabled) {
                        LOG(DEBUG) << "createStreamingShellSession: pipes created - to_shell[" << to_shell[0] << "," << to_shell[1] 
                                   << "], from_shell[" << from_shell[0] << "," << from_shell[1] << "]";
                    }
                    
                    pid_t pid = fork();
                    if (pid < 0) {
                        active_streaming_sessions--; // 回滚计数
                        // 系统错误，始终输出
                        PLOG(ERROR) << "createStreamingShellSession: fork failed";
                        close(to_shell[0]); close(to_shell[1]);
                        close(from_shell[0]); close(from_shell[1]);
                        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "fork failed");
                    }
                    
                    if (pid == 0) { // 子进程
                        if (debug_logging_enabled) {
                            LOG(INFO) << "Streaming shell child (PID: " << getpid() << ") started for UID: " << uid;
                        }
                        
                        close(to_shell[1]);   // 关闭写端
                        close(from_shell[0]); // 关闭读端
                        
                        // 重定向stdin/stdout到管道
                        if (dup2(to_shell[0], STDIN_FILENO) < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "Streaming shell child: dup2 stdin failed";
                            _exit(126);
                        }
                        if (dup2(from_shell[1], STDOUT_FILENO) < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "Streaming shell child: dup2 stdout failed";
                            _exit(126);
                        }
                        if (dup2(from_shell[1], STDERR_FILENO) < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "Streaming shell child: dup2 stderr failed";
                            _exit(126);
                        }
                        
                        close(to_shell[0]);
                        close(from_shell[1]);
                        
                        // 设置用户环境
                        if (uid != 0) {
                            if (setresgid(uid, uid, uid) != 0 || setresuid(uid, uid, uid) != 0) {
                                // 安全相关错误，始终输出
                                PLOG(ERROR) << "Streaming shell child: Failed to switch to UID " << uid;
                                _exit(125);
                            }
                        }
                        // 转换String16数组为std::string数组
                        std::vector<std::string> envVarsStd;
                        for (const auto& envVar : envVars) {
                            envVarsStd.push_back(android::String8(envVar).string());
                        }
                        setupUserEnvironment(uid, envVarsStd);
                        
                        // 启动shell，设置为非交互模式（无提示符）
                        setenv("PS1", "", 1);
                        setenv("PS2", "", 1);
                        if (debug_logging_enabled) {
                            LOG(INFO) << "Streaming shell child: executing shell with no prompts";
                        }
                        execlp("/system/bin/sh", "sh", "-s", (char*)NULL); // -s从stdin读取
                        // 系统错误，始终输出
                        PLOG(ERROR) << "Streaming shell child: execlp failed";
                        _exit(127);
                    } else { // 父进程
                        if (debug_logging_enabled) {
                            LOG(INFO) << "Streaming shell parent (PID: " << getpid() << "), child PID: " << pid;
                        }
                        
                        close(to_shell[0]);   // 关闭读端
                        close(from_shell[1]); // 关闭写端
                        
                        // 创建socketpair用于与客户端通信
                        int socketfds[2];
                        if (socketpair(AF_UNIX, SOCK_STREAM, 0, socketfds) < 0) {
                            // 系统错误，始终输出
                            PLOG(ERROR) << "createStreamingShellSession: socketpair failed";
                            close(to_shell[1]);
                            close(from_shell[0]);
                            return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "socketpair failed");
                        }
                        
                        if (debug_logging_enabled) {
                            LOG(DEBUG) << "createStreamingShellSession: socketpair created [" << socketfds[0] << "," << socketfds[1] << "]";
                        }
                        
                        // 在后台线程中实现双向数据转发
                        std::thread([=]() {
                            if (debug_logging_enabled) {
                                LOG(INFO) << "Data forwarding thread started for PID: " << pid;
                            }
                            
                            fd_set readfds;
                            char buffer[4096];
                            int max_fd = std::max({socketfds[0], to_shell[1], from_shell[0]}) + 1;
                            
                            while (true) {
                                FD_ZERO(&readfds);
                                FD_SET(socketfds[0], &readfds);  // 客户端发来的数据
                                FD_SET(from_shell[0], &readfds); // shell的输出
                                
                                int ret = select(max_fd, &readfds, NULL, NULL, NULL);
                                if (ret < 0) {
                                    if (errno == EINTR) continue;
                                    if (debug_logging_enabled) {
                                        PLOG(ERROR) << "Data forwarding: select failed";
                                    }
                                    break;
                                }
                                
                                // 从客户端读取数据，发送给shell
                                if (FD_ISSET(socketfds[0], &readfds)) {
                                    ssize_t bytes = read(socketfds[0], buffer, sizeof(buffer));
                                    if (bytes <= 0) {
                                        if (debug_logging_enabled) {
                                            LOG(INFO) << "Client connection closed, bytes=" << bytes;
                                        }
                                        break;
                                    }
                                    if (debug_logging_enabled) {
                                        LOG(DEBUG) << "Forwarding " << bytes << " bytes from client to shell";
                                    }
                                    if (write(to_shell[1], buffer, bytes) != bytes) {
                                        if (debug_logging_enabled) {
                                            PLOG(ERROR) << "Failed to write to shell stdin";
                                        }
                                        break;
                                    }
                                }
                                
                                // 从shell读取输出，发送给客户端
                                if (FD_ISSET(from_shell[0], &readfds)) {
                                    ssize_t bytes = read(from_shell[0], buffer, sizeof(buffer));
                                    if (bytes <= 0) {
                                        if (debug_logging_enabled) {
                                            LOG(INFO) << "Shell output closed, bytes=" << bytes;
                                        }
                                        break;
                                    }
                                    if (debug_logging_enabled) {
                                        LOG(DEBUG) << "Forwarding " << bytes << " bytes from shell to client";
                                    }
                                    if (write(socketfds[0], buffer, bytes) != bytes) {
                                        if (debug_logging_enabled) {
                                            PLOG(ERROR) << "Failed to write to client";
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // 清理资源并减少计数
                            close(socketfds[0]);
                            close(to_shell[1]);
                            close(from_shell[0]);
                            
                            // 等待子进程退出
                            int status;
                            waitpid(pid, &status, 0);
                            
                            // 减少活跃会话计数
                            active_streaming_sessions--;
                            
                            if (debug_logging_enabled) {
                                LOG(INFO) << "Data forwarding thread ended for PID: " << pid << ", remaining sessions: " << active_streaming_sessions.load();
                            }
                        }).detach();
                        
                        unique_fd ufd(socketfds[1]); // 返回给客户端的FD
                        *_aidl_return = ParcelFileDescriptor(std::move(ufd));
                        
                        if (debug_logging_enabled) {
                            LOG(INFO) << "createStreamingShellSession returning Status::ok() for UID: " << uid;
                        }
                        return Status::ok();
                    }
                }
            };

        } // namespace service
    } // namespace xu
} // namespace com

int main(void)
{
    bool debug_logging_enabled = GetBoolProperty("persist.sys.cloud.root.debug", false);

    if (debug_logging_enabled) {
        LOG(INFO) << "XuService daemon starting...";
    }
    // --- 在启动线程池之前安装信号处理器 ---
    install_signal_handler(); // This will log internally if debug_logging_enabled

    if (debug_logging_enabled) {
        LOG(INFO) << "Initializing Android ProcessState.";
    }
    android::ProcessState::initWithDriver("/dev/binder");
    android::ProcessState::self()->startThreadPool();
    if (debug_logging_enabled) {
        LOG(INFO) << "ProcessState initialized and thread pool started.";
    }

    sp<com::xu::service::XuService> service = new com::xu::service::XuService();
    if (debug_logging_enabled) {
        LOG(INFO) << "XuService instance created.";
    }
    android::defaultServiceManager()->addService(android::String16("xu_service"), service);
    if (debug_logging_enabled) {
        LOG(INFO) << "XuService added to ServiceManager as \"xu_service\".";
    }

    LOG(INFO) << "XuService starting to join thread pool."; // Keep this as INFO always
    android::IPCThreadState::self()->joinThreadPool();
    
    // 通常不会执行到这里，除非服务停止
    LOG(INFO) << "XuService stopped."; // Keep this as INFO always
    return 0; 
}