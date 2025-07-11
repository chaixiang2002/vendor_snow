// src/client/xu.cpp
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <com/xu/service/IXu.h>
#include <com/xu/service/CommandResult.h>
#include <utils/String16.h>
#include <binder/ParcelFileDescriptor.h>

#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>
#include <poll.h>
#include <signal.h>
#include <getopt.h> // Needed for -h option
#include <vector>
#include <string>
#include <memory> // For unique_ptr
#include <iostream> // For std::getline
#include <map>

using android::sp;
using android::String16;
using android::binder::Status;
using android::os::ParcelFileDescriptor;

// Global variables for terminal handling
struct termios g_original_termios;
bool g_is_raw_mode = false;
int g_pty_master_fd = -1; // Keep track of the PTY master FD for signal handler

// Helper function to collect current environment variables
std::vector<android::String16> collectEnvironmentVariables() {
    std::vector<android::String16> envArray;
    extern char **environ;
    
    for (char **env = environ; *env != nullptr; env++) {
        std::string envStr(*env);
        // 直接添加"key=value"格式的字符串
        envArray.push_back(android::String16(envStr.c_str()));
    }
    
    return envArray;
}

// Function to restore terminal settings
void restore_terminal() {
    if (g_is_raw_mode) {
        if (tcsetattr(STDIN_FILENO, TCSANOW, &g_original_termios) == -1) {
            PLOG(ERROR) << "tcsetattr failed restoring terminal";
        }
        g_is_raw_mode = false;
    }
}

// Function to set terminal to raw mode
void set_raw_mode() {
    bool debug_logging_enabled = android::base::GetBoolProperty("persist.sys.cloud.root.debug", false);
    if (debug_logging_enabled) {
        LOG(DEBUG) << "Setting terminal to raw mode";
    }
    if (tcgetattr(STDIN_FILENO, &g_original_termios) == -1) {
        PLOG(ERROR) << "tcgetattr failed";
        exit(1); // Exit if we can't get original settings
    }
    // Ensure terminal is restored on normal exit or via signals handled by exit()
    if (atexit(restore_terminal) != 0) {
         PLOG(ERROR) << "atexit registration failed";
         // Try to restore immediately if atexit failed? Maybe just exit.
         exit(1);
    }


    struct termios raw = g_original_termios;
    cfmakeraw(&raw);
    // Set VMIN and VTIME for non-blocking reads? Usually cfmakeraw is enough.

    if (tcsetattr(STDIN_FILENO, TCSANOW, &raw) == -1) {
        PLOG(ERROR) << "tcsetattr failed setting raw mode";
        exit(1); // Exit if we can't set raw mode
    }
    g_is_raw_mode = true;
    if (debug_logging_enabled) {
        LOG(DEBUG) << "Terminal set to raw mode successfully";
    }
}

// Signal handler for SIGWINCH (terminal resize)
void handle_sigwinch(int sig) {
    (void)sig; // Mark sig as unused
    bool debug_logging_enabled = android::base::GetBoolProperty("persist.sys.cloud.root.debug", false);
    if (debug_logging_enabled) {
        LOG(DEBUG) << "Received SIGWINCH signal (terminal resize)";
    }
    if (g_pty_master_fd != -1 && isatty(STDIN_FILENO)) { // Check if stdin is a tty
        struct winsize ws;
        if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == -1) {
            // Don't log error if not a tty (e.g., redirected input)
            if (errno != ENOTTY) {
                 PLOG(ERROR) << "ioctl(TIOCGWINSZ) failed";
            }
            return;
        }
        if (ioctl(g_pty_master_fd, TIOCSWINSZ, &ws) == -1) {
            PLOG(ERROR) << "ioctl(TIOCSWINSZ) failed";
            // Non-fatal, continue
        } else {
            if (debug_logging_enabled) {
                LOG(DEBUG) << "Window size synchronized: " << ws.ws_col << "x" << ws.ws_row;
            }
        }
    }
}


void print_usage()
{
    bool debug_logging_enabled = android::base::GetBoolProperty("persist.sys.cloud.root.debug", false);
    if (debug_logging_enabled) {
        LOG(DEBUG) << "Printing usage information";
    }
    // Simplified usage message with corrected multi-line formatting
    fprintf(stderr, "Usage: xu [-h] [<uid>] [<command> <args...>]\n\n"
                    "Options:\n"
                    "  -h                 Show this help message.\n\n"
                    "  -c <command>       Specify the command to run as a string.\n"
                    "                     If -c is used, further arguments are ignored.\n"
                    "                     This is primarily for non-TTY input scenarios but can be used with TTYs.\n\n"
                    "Description:\n"
                    "  Executes a command or starts an interactive shell with root privileges\n"
                    "  or as the specified user <uid> via a PTY session.\n\n"
                    "Examples:\n"
                    "  xu                 Start interactive root shell.\n"
                    "  xu id              Run 'id' command as root interactively.\n"
                    "  xu 1000            Start interactive shell as user 1000.\n"
                    "  xu 1000 busybox ls Run 'busybox ls' as user 1000 interactively.\n");
}

// run_interactive_session function remains largely the same, minor robustness added
int run_interactive_session(sp<com::xu::service::IXu>& service, int32_t uid, const std::vector<android::String16>& cmd_args) {
    bool debug_logging_enabled = android::base::GetBoolProperty("persist.sys.cloud.root.debug", false);
    
    if (debug_logging_enabled) {
        LOG(INFO) << "Starting interactive session for UID: " << uid;
        if (!cmd_args.empty()) {
            LOG(INFO) << "Command args count: " << cmd_args.size();
            for (size_t i = 0; i < cmd_args.size(); ++i) {
                LOG(DEBUG) << "  arg[" << i << "]: " << android::String8(cmd_args[i]).string();
            }
        }
    }

    // 收集当前环境变量
    std::vector<android::String16> envVars = collectEnvironmentVariables();
    if (debug_logging_enabled) {
        LOG(INFO) << "Collected " << envVars.size() << " environment variables from client";
        // 不在这里详细打印所有环境变量，因为可能很多，在daemon端打印即可
    }

    ParcelFileDescriptor parcelFd;
    Status status = service->createPtySession(uid, cmd_args, envVars, &parcelFd);

    if (!status.isOk()) {
        // 服务调用错误，始终输出
        LOG(ERROR) << "Failed to create PTY session: " << status.toString8().c_str();
        return 1;
    }

    // Check validity using get()
    if (parcelFd.get() < 0) {
         // 系统错误，始终输出
         LOG(ERROR) << "Received invalid ParcelFileDescriptor (FD < 0)";
        return 1;
    }

    if (debug_logging_enabled) {
        LOG(DEBUG) << "PTY session created successfully, FD: " << parcelFd.get();
    }

    // Duplicate the FD to take ownership, as detach() might not be available
    // The original FD in parcelFd will be closed by its destructor.
    g_pty_master_fd = dup(parcelFd.get());
    if (g_pty_master_fd < 0) {
         // 系统错误，始终输出
         PLOG(ERROR) << "Failed to dup PTY master FD";
         // parcelFd destructor will still close the original FD
         return 1;
    }
    if (debug_logging_enabled) {
        LOG(DEBUG) << "PTY master FD duplicated: " << g_pty_master_fd;
    }
    
    // Ensure the duplicated FD is closed on exit using unique_ptr
    // Note: g_pty_master_fd is global for signal handler, but unique_ptr manages this dup'd copy
    auto pty_fd_closer = [](int* fd_ptr){ if (*fd_ptr >= 0) { close(*fd_ptr); *fd_ptr = -1; } }; // Close and reset global fd
    std::unique_ptr<int, decltype(pty_fd_closer)> pty_fd_guard(&g_pty_master_fd, pty_fd_closer);


    // Check if stdin is a TTY before setting raw mode
    if (!isatty(STDIN_FILENO)) {
         // 兼容性警告，始终输出
         LOG(WARNING) << "Standard input is not a TTY. Interactive session may not work as expected.";
         // Proceed anyway? Or exit? For now, proceed.
    } else {
        if (debug_logging_enabled) {
            LOG(DEBUG) << "Standard input is a TTY, setting up terminal";
        }
        // Set terminal to raw mode only if stdin is a tty
        set_raw_mode();

        // Setup signal handler for window resize only if stdin is a tty
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = handle_sigwinch;
        sa.sa_flags = SA_RESTART; // Restart syscalls if possible
        if (sigaction(SIGWINCH, &sa, nullptr) == -1) {
             // 系统错误，始终输出
             PLOG(ERROR) << "sigaction(SIGWINCH) failed";
             // Non-fatal?
        } else {
            if (debug_logging_enabled) {
                LOG(DEBUG) << "SIGWINCH handler installed successfully";
            }
        }

        // Initial window size sync only if stdin is a tty
        handle_sigwinch(0); // Call once manually
    }

    if (debug_logging_enabled) {
        LOG(INFO) << "Entering interactive I/O loop";
    }
    // Main I/O loop
    struct pollfd fds[2];
    fds[0].fd = STDIN_FILENO;    // Monitor standard input
    fds[0].events = POLLIN;
    fds[1].fd = g_pty_master_fd; // Monitor PTY master
    fds[1].events = POLLIN;

    char buffer[4096];
    bool running = true;
    size_t total_bytes_forwarded_to_pty = 0;
    size_t total_bytes_forwarded_to_stdout = 0;

    while (running) {
        int ret = poll(fds, 2, -1); // Wait indefinitely
        if (ret < 0) {
            if (errno == EINTR) {
                if (debug_logging_enabled) {
                    LOG(DEBUG) << "poll() interrupted by signal, continuing";
                }
                continue; // Interrupted by signal (like SIGWINCH), just poll again
            }
            // 系统错误，始终输出
            PLOG(ERROR) << "poll failed";
            running = false;
            break;
        }

        // Check standard input
        if (fds[0].revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL)) {
             if (fds[0].revents & POLLIN) {
                 ssize_t bytes_read = read(STDIN_FILENO, buffer, sizeof(buffer));
                 if (bytes_read < 0) { // Read error
                      // EINTR is handled by poll loop, others are errors
                     // 系统错误，始终输出
                     PLOG(ERROR) << "Read error from stdin";
                     running = false;
                 } else if (bytes_read == 0) { // EOF on stdin
                      if (debug_logging_enabled) {
                          LOG(INFO) << "EOF reached on stdin";
                      }
                     // Close the write direction to the PTY master?
                     // For raw mode, maybe just let the remote end detect EOF via read=0?
                     // Or explicitly send EOF (e.g., Ctrl+D, if terminal settings pass it)?
                     // Simplest for now: stop forwarding input. The remote might exit.
                      // We could shutdown(g_pty_master_fd, SHUT_WR); but that requires <sys/socket.h>
                      // For simplicity, we just stop reading stdin, remote will eventually get EOF on read?
                      fds[0].fd = -1; // Stop polling stdin

                 } else { // Data read from stdin
                      if (debug_logging_enabled) {
                          LOG(DEBUG) << "Read " << bytes_read << " bytes from stdin";
                      }
                      ssize_t total_written = 0;
                      while (total_written < bytes_read) {
                          ssize_t bytes_written = write(g_pty_master_fd, buffer + total_written, bytes_read - total_written);
                           if (bytes_written < 0) {
                               if (errno == EINTR) continue; // Try writing again if interrupted
                               // 系统错误，始终输出
                               PLOG(ERROR) << "Write error to PTY master";
                               running = false;
                               break; // Exit inner write loop
                           }
                           total_written += bytes_written;
                      }
                      if (total_written > 0) {
                          total_bytes_forwarded_to_pty += total_written;
                          if (debug_logging_enabled) {
                              LOG(DEBUG) << "Forwarded " << total_written << " bytes to PTY (total: " << total_bytes_forwarded_to_pty << ")";
                          }
                      }
                      if (!running) break; // Exit outer loop if write failed
                 }
             } else { // POLLERR | POLLHUP | POLLNVAL on stdin
                 // 系统错误，始终输出
                 LOG(ERROR) << "Error/Hup/Invalid event on stdin";
                 running = false;
             }
        }


        // Check PTY master
         if (fds[1].revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL)) {
              if (fds[1].revents & POLLIN) {
                  ssize_t bytes_read = read(g_pty_master_fd, buffer, sizeof(buffer));
                  if (bytes_read < 0) { // Read error from PTY
                      if (errno == EIO) {
                           // EIO is expected on PTY master when the slave closes
                           if (debug_logging_enabled) {
                               LOG(INFO) << "PTY master received EIO (slave closed gracefully)";
                           }
                      } else if (errno != EINTR) {
                           // 系统错误，始终输出
                           PLOG(ERROR) << "Read error from PTY master";
                      } else {
                           // EINTR, continue loop
                           continue;
                      }
                      running = false; // Stop on error or EIO
                  } else if (bytes_read == 0) { // EOF from PTY (less common than EIO for PTYs)
                      if (debug_logging_enabled) {
                          LOG(INFO) << "PTY master closed (EOF received)";
                      }
                      running = false; // Stop the loop
                  } else { // Data read from PTY
                      if (debug_logging_enabled) {
                          LOG(DEBUG) << "Read " << bytes_read << " bytes from PTY";
                      }
                      ssize_t total_written = 0;
                       while (total_written < bytes_read) {
                           ssize_t bytes_written = write(STDOUT_FILENO, buffer + total_written, bytes_read - total_written);
                           if (bytes_written < 0) {
                               if (errno == EINTR) continue; // Try writing again
                               // 系统错误，始终输出
                               PLOG(ERROR) << "Write error to stdout";
                               running = false;
                               break; // Exit inner write loop
                           }
                           total_written += bytes_written;
                       }
                       if (total_written > 0) {
                           total_bytes_forwarded_to_stdout += total_written;
                           if (debug_logging_enabled) {
                               LOG(DEBUG) << "Forwarded " << total_written << " bytes to stdout (total: " << total_bytes_forwarded_to_stdout << ")";
                           }
                       }
                       if (!running) break; // Exit outer loop if write failed
                  }
              } else if (fds[1].revents & POLLHUP) { // Check for HUP separately
                   // PTY slave hung up (closed), treat as normal termination or info.
                   if (debug_logging_enabled) {
                       LOG(INFO) << "PTY master received HUP (slave likely closed)";
                   }
                   running = false; // Stop the loop
              } else { // POLLERR | POLLNVAL (when not POLLIN or POLLHUP)
                   // Log the specific event flags for better debugging
                   // 系统错误，始终输出
                   LOG(ERROR) << "Error/Invalid event on PTY master. revents = " << fds[1].revents;
                   running = false;
              }
         }
    }

    if (debug_logging_enabled) {
        LOG(INFO) << "Interactive session ended. Total bytes: stdin->pty=" << total_bytes_forwarded_to_pty 
                  << ", pty->stdout=" << total_bytes_forwarded_to_stdout;
    }

    // Cleanup:
    // - Terminal restored by atexit handler (restore_terminal) if raw mode was set.
    // - PTY master FD closed by unique_ptr guard (pty_fd_guard).
    // Or maybe return status from remote process if possible? Difficult with PTY.
    return 0;
}

// New function to run commands non-interactively
int run_batch_command(sp<com::xu::service::IXu>& service, int32_t uid, const std::vector<android::String16>& cmd_args) {
    bool debug_logging_enabled = android::base::GetBoolProperty("persist.sys.cloud.root.debug", false);
    
    if (debug_logging_enabled) {
        LOG(INFO) << "Starting batch command execution for UID: " << uid;
    }
    if (cmd_args.empty()) {
        // This case should ideally be caught in main() before calling this function
        // when -n is specified.
        // 参数错误，始终输出
        LOG(ERROR) << "A command is required for non-interactive mode.";
        fprintf(stderr, "Error: A command is required for non-interactive mode.\n");
        // print_usage(); // Optionally print usage
        return 126; // Indicate an error before even calling the service
    }

    if (debug_logging_enabled) {
        LOG(INFO) << "Command args count: " << cmd_args.size();
        for (size_t i = 0; i < cmd_args.size(); ++i) {
            LOG(DEBUG) << "  arg[" << i << "]: " << android::String8(cmd_args[i]).string();
        }
    }

    // 收集当前环境变量
    std::vector<android::String16> envVars = collectEnvironmentVariables();
    if (debug_logging_enabled) {
        LOG(INFO) << "Collected " << envVars.size() << " environment variables from client";
    }

    com::xu::service::CommandResult result; // Declare CommandResult to store the outcome

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Calling executeBatchCommand for UID: " << uid;
    }

    // Updated to call the new AIDL method signature
    // The BpXu proxy will have a method like:
    // Status executeBatchCommand(int32_t uid, const std::vector<String16>& cmd_args, const std::vector<String16>& envVars, CommandResult* _aidl_return);
    Status status = service->executeBatchCommand(uid, cmd_args, envVars, &result);
    
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to execute command non-interactively: " << status.toString8().c_str();
        // Attempt to return a somewhat meaningful exit code based on binder error
        if (status.exceptionCode() == Status::EX_SECURITY) return 126; // Permission denied like
        if (status.exceptionCode() == Status::EX_ILLEGAL_ARGUMENT) return 125; // Argument error
        // If the call fails, 'result' might not be reliably populated by the service, 
        // though our daemon-side implementation now initializes it to default error values.
        return 1; // Generic client-side error for failed binder call
    }

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Command executed successfully";
        LOG(DEBUG) << "result.output length: " << result.output.size();
        LOG(DEBUG) << "result.stderrOutput length: " << result.stderrOutput.size();
        LOG(DEBUG) << "result.exitCode: " << result.exitCode;
    }

    // INFO级别：打印命令执行结果
    if (debug_logging_enabled) {
        std::string commandForLog;
        for (const auto& arg : cmd_args) {
            commandForLog += android::String8(arg).string();
            commandForLog += " ";
        }
        if (!commandForLog.empty()) {
            commandForLog.pop_back(); // Remove trailing space
        }
        LOG(INFO) << "INPUT: Command=[" << commandForLog << "] UID=" << uid;
        LOG(INFO) << "RESULT: ExitCode=" << result.exitCode 
                    << " StdoutBytes=" << result.output.size() 
                    << " StderrBytes=" << result.stderrOutput.size();
    }
    // Print the captured standard output from the CommandResult object
    android::String8 output_s8(result.output); // Access result.output
    if (output_s8.length() > 0) {
        if (debug_logging_enabled) {
            LOG(DEBUG) << "Writing " << output_s8.length() << " bytes to stdout";
        }
        printf("%s", output_s8.string());
        // Ensure a newline if the output doesn't end with one, for cleaner terminal output.
        // However, it's generally better to let the command's output dictate newlines.
        // For now, we print as is. If the command's output is intended for piping,
        // adding an extra newline might be undesirable.
    }

    // Print the captured standard error from the CommandResult object to stderr
    android::String8 stderr_s8(result.stderrOutput); // Access result.stderrOutput
    if (stderr_s8.length() > 0) {
        if (debug_logging_enabled) {
            LOG(DEBUG) << "Writing " << stderr_s8.length() << " bytes to stderr";
        }
        fprintf(stderr, "%s", stderr_s8.string());
    }
    
    if (debug_logging_enabled) {
        LOG(INFO) << "Batch command completed with exit code: " << result.exitCode;
    }
    // The exit_code from the service is the actual command's exit code, accessed via result.exitCode
    return result.exitCode; // Access result.exitCode
}

int run_streaming_shell_session(sp<com::xu::service::IXu>& service, int32_t uid) {
    bool debug_logging_enabled = android::base::GetBoolProperty("persist.sys.cloud.root.debug", false);
    
    if (debug_logging_enabled) {
        LOG(INFO) << "Starting streaming shell session for UID: " << uid;
    }
    
    // 收集当前环境变量
    std::vector<android::String16> envVars = collectEnvironmentVariables();
    if (debug_logging_enabled) {
        LOG(INFO) << "Collected " << envVars.size() << " environment variables from client";
    }
    
    // INFO级别：记录流式会话开始
    if (debug_logging_enabled) {
        LOG(INFO) << "INPUT: Streaming shell session started, UID=" << uid 
                  << " EnvVars=" << envVars.size();
    }
    
    ParcelFileDescriptor parcelFd;
    Status status = service->createStreamingShellSession(uid, envVars, &parcelFd);
    
    if (!status.isOk()) {
        // 服务调用错误，始终输出
        LOG(ERROR) << "Failed to create streaming shell session: " << status.toString8().c_str();
        return 1;
    }
    
    int shell_fd = dup(parcelFd.get());
    if (shell_fd < 0) {
        // 系统错误，始终输出
        PLOG(ERROR) << "Failed to dup streaming shell FD";
        return 1;
    }
    
    if (debug_logging_enabled) {
        LOG(DEBUG) << "Streaming shell session created successfully, FD: " << shell_fd;
        LOG(INFO) << "Entering streaming shell loop - async mode (type 'exit' to quit)";
    }
    
    // 使用poll进行异步I/O，类似于interactive session
    struct pollfd fds[2];
    fds[0].fd = STDIN_FILENO;  // 监控标准输入
    fds[0].events = POLLIN;
    fds[1].fd = shell_fd;      // 监控shell输出
    fds[1].events = POLLIN;
    
    char buffer[4096];
    bool running = true;
    bool stdin_closed = false; // 标记stdin是否已关闭
    size_t total_bytes_sent = 0;
    size_t total_bytes_received = 0;
    
    while (running) {
        // 当stdin关闭后，只监控shell输出
        int poll_count = stdin_closed ? 1 : 2;
        int ret = poll(fds + (stdin_closed ? 1 : 0), poll_count, -1); // 无限等待
        if (ret < 0) {
            if (errno == EINTR) {
                if (debug_logging_enabled) {
                    LOG(DEBUG) << "poll() interrupted by signal, continuing";
                }
                continue;
            }
            // 系统错误，始终输出
            PLOG(ERROR) << "poll failed in streaming session";
            running = false;
            break;
        }
        
        // 处理标准输入 -> shell (只有在stdin未关闭时才处理)
        if (!stdin_closed && (fds[0].revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL))) {
            if (fds[0].revents & POLLIN) {
                ssize_t bytes_read = read(STDIN_FILENO, buffer, sizeof(buffer));
                if (bytes_read < 0) {
                    // 系统错误，始终输出
                    PLOG(ERROR) << "Read error from stdin";
                    running = false;
                } else if (bytes_read == 0) {
                    if (debug_logging_enabled) {
                        LOG(INFO) << "EOF reached on stdin, sending exit command to shell";
                    }
                    // 发送exit命令给shell以便优雅退出
                    const char* exit_cmd = "exit\n";
                    write(shell_fd, exit_cmd, strlen(exit_cmd));
                    
                    // 停止监控stdin，但继续等待shell输出
                    fds[0].fd = -1;
                    stdin_closed = true;
                    if (debug_logging_enabled) {
                        LOG(INFO) << "Stopped monitoring stdin, waiting for shell to exit";
                    }
                } else {
                    // 检查是否是exit命令
                    std::string input(buffer, bytes_read);
                    if (input.find("exit") == 0) {
                        if (debug_logging_enabled) {
                            LOG(INFO) << "Exit command detected";
                        }
                        // 依然发送给shell，让它正常退出
                    }
                    
                    if (debug_logging_enabled) {
                        LOG(DEBUG) << "Read " << bytes_read << " bytes from stdin";
                    }
                    ssize_t total_written = 0;
                    while (total_written < bytes_read) {
                        ssize_t bytes_written = write(shell_fd, buffer + total_written, bytes_read - total_written);
                        if (bytes_written < 0) {
                            if (errno == EINTR) continue;
                            // 系统错误，始终输出
                            PLOG(ERROR) << "Write error to shell";
                            running = false;
                            break;
                        }
                        total_written += bytes_written;
                    }
                    if (total_written > 0) {
                        total_bytes_sent += total_written;
                        if (debug_logging_enabled) {
                            // 打印buffer内容
                            LOG(DEBUG) << "Forwarded to shell: [" << buffer << "]";
                        }
                    }
                    if (!running) break;
                }
            } else {
                // stdin出现异常，发送exit命令给shell并停止监控stdin
                if (debug_logging_enabled) {
                    LOG(INFO) << "Stdin error/hup/invalid event, sending exit command to shell";
                }
                
                // 发送exit命令给shell以便优雅退出
                const char* exit_cmd = "exit\n";
                write(shell_fd, exit_cmd, strlen(exit_cmd));
                
                // 停止监控stdin，但继续等待shell输出
                fds[0].fd = -1;
                stdin_closed = true;
                if (debug_logging_enabled) {
                    LOG(INFO) << "Stopped monitoring stdin, waiting for shell to exit";
                }
            }
        }
        
        // 处理shell输出 -> stdout
        if (fds[1].revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL)) {
            if (fds[1].revents & POLLIN) {
                ssize_t bytes_read = read(shell_fd, buffer, sizeof(buffer));
                if (bytes_read < 0) {
                    if (errno == EINTR) {
                        continue;
                    }
                    // 系统错误，始终输出
                    PLOG(ERROR) << "Read error from shell";
                    running = false;
                } else if (bytes_read == 0) {
                    if (debug_logging_enabled) {
                        LOG(INFO) << "Shell closed connection (EOF received) - session ending";
                    }
                    // 只有当shell关闭时才真正退出
                    running = false;
                } else {
                    if (debug_logging_enabled) {
                        LOG(DEBUG) << "Read " << bytes_read << " bytes from shell";
                    }
                    ssize_t total_written = 0;
                    while (total_written < bytes_read) {
                        ssize_t bytes_written = write(STDOUT_FILENO, buffer + total_written, bytes_read - total_written);
                        if (bytes_written < 0) {
                            if (errno == EINTR) continue;
                            // 系统错误，始终输出
                            PLOG(ERROR) << "Write error to stdout";
                            running = false;
                            break;
                        }
                        total_written += bytes_written;
                    }
                    if (total_written > 0) {
                        total_bytes_received += total_written;
                        if (debug_logging_enabled) {
                            // 打印buffer内容
                            LOG(DEBUG) << "Forwarded to stdout: [" << buffer << "]";
                        }
                    }
                    if (!running) break;
                }
            } else if (fds[1].revents & POLLHUP) {
                if (debug_logging_enabled) {
                    LOG(INFO) << "Shell hung up connection - session ending";
                }
                // 只有当shell挂断时才真正退出
                running = false;
            } else {
                // 系统错误，始终输出
                LOG(ERROR) << "Error/Invalid event on shell FD. revents = " << fds[1].revents;
                running = false;
            }
        }
    }
    
    if (debug_logging_enabled) {
        LOG(INFO) << "Streaming shell session ended. Total bytes: stdin->shell=" << total_bytes_sent 
                  << ", shell->stdout=" << total_bytes_received
                  << " (stdin was " << (stdin_closed ? "closed" : "open") << " at end)";
    }
    close(shell_fd);
    return 0;
}

int main(int argc, char **argv)
{
    bool debug_logging_enabled = android::base::GetBoolProperty("persist.sys.cloud.root.debug", false);
    
    if (debug_logging_enabled) {
        LOG(INFO) << "xu client starting, PID=" << getpid() << ", PPID=" << getppid() << ", argc=" << argc;
        for (int i = 0; i < argc; ++i) {
            LOG(DEBUG) << "argv[" << i << "]: " << argv[i];
        }
    }

    // 如果需要更详细的调用者信息，也可以记录进程名
    char parent_comm[256] = {0};
    char parent_proc_path[256];
    snprintf(parent_proc_path, sizeof(parent_proc_path), "/proc/%d/comm", getppid());
    FILE *fp = fopen(parent_proc_path, "r");
    if (fp) {
        if (fgets(parent_comm, sizeof(parent_comm), fp)) {
            // 移除末尾的换行符
            char *newline = strchr(parent_comm, '\n');
            if (newline) *newline = '\0';
            if (debug_logging_enabled) {
                LOG(INFO) << "Called by parent process: " << parent_comm << " (PID: " << getppid() << ")";
            }
        }
        fclose(fp);
    } else {
        if (debug_logging_enabled) {
            LOG(DEBUG) << "Could not read parent process name from " << parent_proc_path;
        }
    }

    int opt;
    bool c_option_used = false;
    std::string command_from_c_option;

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Parsing command line options";
    }
    // Use getopt for -h and -c. -c requires an argument.
    // The initial parsing loop for options.
    while ((opt = getopt(argc, argv, "+hc:")) != -1) { 
        switch (opt) {
            case 'h':
                if (debug_logging_enabled) {
                    LOG(DEBUG) << "Help option selected";
                }
                print_usage();
                return 0;
            case 'c':
                c_option_used = true;
                command_from_c_option = optarg;
                if (debug_logging_enabled) {
                    LOG(DEBUG) << "Command option selected: " << command_from_c_option;
                }
                break;
            case '?': // Unknown option or missing argument for -c
            default:
                // 参数错误，始终输出
                LOG(ERROR) << "Invalid command line option";
                print_usage(); 
                return 1;
        }
    }

    // Arguments after options (UID or command parts if -c wasn't used)
    // optind is the index of the first non-option argument.
    int first_remaining_arg_index = optind;
    if (debug_logging_enabled) {
        LOG(DEBUG) << "First remaining argument index: " << first_remaining_arg_index;
    }

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Initializing Android ProcessState";
    }
    android::ProcessState::initWithDriver("/dev/binder");

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Getting service manager";
    }
    sp<android::IServiceManager> sm = android::defaultServiceManager();
    if (sm == nullptr) {
        // 系统错误，始终输出
        LOG(ERROR) << "Failed to get service manager";
        return 1;
    }

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Getting xu_service binder";
    }
    sp<android::IBinder> binder = sm->getService(String16("xu_service"));
    if (binder == nullptr)
    {
        // 服务连接错误，始终输出
        LOG(ERROR) << "Failed to get xu_service";
        return 1;
    }

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Casting to IXu interface";
    }
    sp<com::xu::service::IXu> service = android::interface_cast<com::xu::service::IXu>(binder);
    if (service == nullptr) {
        // 服务接口错误，始终输出
        LOG(ERROR) << "Failed to get IXu interface";
        return 1;
    }

    if (debug_logging_enabled) {
        LOG(INFO) << "Successfully connected to xu_service";
    }

    int32_t target_uid = 0; 
    std::vector<android::String16> cmd_args_vector; 

    // Determine UID and command arguments
    int current_arg_index = first_remaining_arg_index;

    if (debug_logging_enabled) {
        LOG(DEBUG) << "Parsing UID and command arguments";
    }
    // if (!c_option_used) { // Only parse UID and cmd_args if -c was NOT used
        // Check for UID as the first potential non-option argument
        if (current_arg_index < argc) {
            char *endptr;
            long parsed_uid = strtol(argv[current_arg_index], &endptr, 10);
            if (*endptr == '\0' && argv[current_arg_index][0] != '\0') { // Successfully parsed as a number
                 if (parsed_uid < 0 || parsed_uid > INT32_MAX) {
                      // 参数错误，始终输出
                      LOG(ERROR) << "Invalid UID (out of range): " << argv[current_arg_index];
                      print_usage();
                      return 1;
                 }
                 target_uid = (int32_t)parsed_uid;
                 if (debug_logging_enabled) {
                     LOG(DEBUG) << "Parsed target UID: " << target_uid;
                 }
                 current_arg_index++; // Move to the next argument for command parts
            } else {
                if (debug_logging_enabled) {
                    LOG(DEBUG) << "First argument is not a UID, treating as command";
                }
            }
            // If not a UID, it's part of the command (or no command if current_arg_index == argc)
        }

        // Collect remaining arguments as command_args_vector if -c was not used
        int cmd_args_vector_index = 0;
        for (int i = current_arg_index; i < argc; ++i) {
            cmd_args_vector.push_back(String16(argv[i]));
            if (debug_logging_enabled) {
                LOG(INFO) << "cmd_args_vector[" << cmd_args_vector_index << "]: " << cmd_args_vector[cmd_args_vector_index];
            }
            cmd_args_vector_index++;
        }
        
    // }

    // If c_option_used is true, cmd_args_vector remains empty here,
    // and we'll use command_from_c_option later.

    bool stdin_is_tty = isatty(STDIN_FILENO);
    if (debug_logging_enabled) {
        LOG(INFO) << "stdin_is_tty: " << (stdin_is_tty ? "true" : "false");
        LOG(INFO) << "target_uid: " << target_uid;
        LOG(INFO) << "cmd_args_vector.size(): " << cmd_args_vector.size();
    }

    if (stdin_is_tty) {
        if (debug_logging_enabled) {
            LOG(INFO) << "Using interactive session mode (TTY detected)";
        }
        return run_interactive_session(service, target_uid, cmd_args_vector);
    } else { // Standard input is NOT a TTY
        if (cmd_args_vector.empty()) { // No command arguments on CLI
            // Use streaming shell session for line-by-line processing
            if (debug_logging_enabled) {
                LOG(INFO) << "No command args on CLI, using streaming shell session for line-by-line processing.";
            }
            return run_streaming_shell_session(service, target_uid);
        } else {
            // If cmd_args_vector is NOT empty, use batch processing
            if (debug_logging_enabled) {
                LOG(INFO) << "Using command arguments from CLI for batch execution.";
            }
            return run_batch_command(service, target_uid, cmd_args_vector);
        }
    }
}