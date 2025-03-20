package com.android.server;

import static com.android.server.Space9dManagerService.TAG;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServerSocket {
    private static native int createServerSocket(String path);

    private static final String SOCKET_PATH = "/data/system/s9_sock";

    private static BlockingQueue<Runnable> sPoolWorkQueue = 
            new LinkedBlockingQueue<>(16);
    private ExecutorService executorService = new ThreadPoolExecutor(
                    4,
                    8,
                    15L,
                    TimeUnit.SECONDS,
                    sPoolWorkQueue,
                    Executors.defaultThreadFactory(),
                    new ThreadPoolExecutor.DiscardPolicy());

    private static final int HEADER_SIZE = 4;

    private MessageListener mListener;

    private ArrayList<LocalSocket> mClients = new ArrayList<>(3);

    static {
        System.loadLibrary("native_socket");
    }
    // sPoolWorkQueue 是一个阻塞队列，用于保存待执行的任务。
    // executorService 是一个线程池，用于并发执行任务。
    // HEADER_SIZE 是消息头的大小。
    // mListener 是消息监听器的实例。
    // mClients 是一个客户端套接字列表，存储连接到服务器的客户端套接字。
    // 静态代码块用于加载本地库 native_socket。

    public ServerSocket(MessageListener listener) {
        this.mListener = listener;
    }

    public interface MessageListener {
        void onMessageReceive(LocalSocket socket, String data);
    }

    public void startServer() {
        new Thread(() -> createServerAndListen()).start();
    }

    private void createServerAndListen() {
        // 创建或检查套接字文件路径。
        // 调用本地方法 createServerSocket 创建服务器套接字。
        // 使用反射设置文件描述符。
        // 使用 LocalServerSocket 开始监听客户端连接。
        // 接受客户端连接并将其交给 ConnectRunner 处理。
        try {
            File sockFile = new File(SOCKET_PATH);
            if (!sockFile.getParentFile().exists()) {
                sockFile.getParentFile().mkdir();
            }
            if (sockFile.exists()) {
                sockFile.delete();
            }

            int fd = createServerSocket(SOCKET_PATH);
            FileDescriptor fileDescriptor = new FileDescriptor();
            try {
                Method method = FileDescriptor.class.getDeclaredMethod("setInt$", int.class);
                method.setAccessible(true);
                method.invoke(fileDescriptor, fd);
            } catch (Exception e) {
                Slog.w(TAG, "setInt$", e);
            }

            LocalServerSocket serverSocket = new LocalServerSocket(fileDescriptor);
            LocalSocket socket;
            while (true) {
                socket = serverSocket.accept();
                executorService.submit(new ConnectRunner(socket));
            }
        } catch (IOException e) {
            Slog.e(TAG, "createServerAndListen", e);
        }
    }

    private class ConnectRunner implements Runnable {
        // ConnectRunner 类实现了 Runnable 接口，用于处理每个客户端连接。
        // run 方法调用 handleClient 方法。
        private final LocalSocket socket;
        ConnectRunner(LocalSocket s) {
            this.socket = s;
        }

        @Override
        public void run() {
            handleClient(socket);
        }
    }

    private void handleClient(LocalSocket socket) {
        // 读取消息头，确定消息长度。
        // 读取消息内容，并将其传递给 MessageListener。
        // 处理特殊的 "CNCT" 消息，将新客户端添加到客户端列表。
        try {
            InputStream inputStream = socket.getInputStream();
            byte[] lengthBuffer = new byte[HEADER_SIZE];
            String headerStr;
            int maxBytes;
            int totalBytesRead = 0;
            int readBytes;
            byte[] messageBuffer;
            while (true) {
                Arrays.fill(lengthBuffer, (byte) 0);
                readBytes = inputStream.read(lengthBuffer);

                if (readBytes == -1) {
                    closeClient(socket, "reached the end");
                    break;
                }

                if (readBytes == 0) {
                    continue;
                }

                if (readBytes == HEADER_SIZE) {
                    //! 读取消息头，确定消息长度
                    headerStr = new String(lengthBuffer, StandardCharsets.UTF_8);
                    maxBytes = Integer.parseInt(headerStr.trim());
                    // skip '!'
                    inputStream.read();

                    messageBuffer = new byte[maxBytes];
                    totalBytesRead = 0;
                    while (totalBytesRead < maxBytes) {
                        readBytes = inputStream.read(messageBuffer, totalBytesRead, maxBytes - totalBytesRead);
                        if (readBytes <= 0) {
                            break;
                        }
                        totalBytesRead += readBytes;
                    }

                    String message = new String(messageBuffer, StandardCharsets.UTF_8);
                    Slog.v(TAG, "s9_sock-----handleClient-Received message: " + message);

                    //! 处理特殊的 "CNCT" 消息，将新客户端添加到客户端列表。
                    // 握手逻辑
                    if ("CNCT".equals(message)) {
                        if (!mClients.contains(socket)) {
                            mClients.add(socket);
                        } else {
                            Slog.w(TAG, "link has been established.");
                        }
                    } else {
                    //! 读取消息内容，并将其传递给 MessageListener
                        mListener.onMessageReceive(socket, message);
                    }
                } else {
                    Slog.w(TAG, "Error reading message content length.");
                }
            }
        } catch (IOException e) {
            closeClient(socket, "handleClient:" + e.getMessage());
        }
    }

    // 发送消息方法
    // 检查套接字和消息是否有效。
    // 获取输出流并发送消息。
    // 处理发送失败情况，关闭客户端连接。
    public void send(LocalSocket socket, String message) {
        // 初步检查
        if (socket == null || message == null || !mClients.contains(socket)) {
            Slog.w(TAG, "send warn: remove ? " + socket);
            return;
        }

        //获取输出流
        OutputStream outputStream = null;
        try {
            outputStream = socket.getOutputStream();
        } catch (IOException e) {
        }

        // 检查文件描述符和输出流
        FileDescriptor fd = socket.getFileDescriptor();
        if (fd == null || outputStream == null) {
            mClients.remove(socket);
            Slog.w(TAG, "send warn: " + fd + " -> " + outputStream);
            return;
        }

        // 准备消息
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        String sendMsg = String.format("%04d!%s", bytes.length, message);
        // 发送消息
        try {
            outputStream.write(sendMsg.getBytes());
            outputStream.flush();
        } catch (Exception e) {
            Slog.e(TAG, "send failure!", e);
            closeClient(socket, "send failure");
        }
    }
    // 广播消息方法
    public void send(String message) {
        synchronized(mClients) {
            for (int i = mClients.size() - 1; i >= 0; i--) {
                Slog.v(TAG, "s9_sock------send sock_num=" + i + " message=" + message);
                send(mClients.get(i), message);
            }
        }
    }

    private void closeClient(LocalSocket socket, String reason) {
        Slog.v(TAG, "closeClient: " + socket + ", reason: " + reason);
        synchronized(mClients) {
            if (socket != null) {
                mClients.remove(socket);
                try {
                    socket.shutdownInput();
                } catch (IOException e) {
                }
                try {
                    socket.shutdownOutput();
                } catch (IOException e) {
                }
                try {
                    socket.close();
                } catch (IOException e) {
                }
                socket = null;
            }
        }
    }
}
