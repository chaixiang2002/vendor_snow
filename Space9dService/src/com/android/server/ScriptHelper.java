package com.android.server;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Slog;

import com.android.server.comm.CommonHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ScriptHelper {

    public static class ResultTask {
        LocalSocket socket;
        JSONObject mResult;
    }

    public static ScriptAsyncTask execScriptTask(MessageHandler handler, int messageWhat,
                                                 String command, ResultTask resultTask) {
        ScriptAsyncTask sat = new ScriptAsyncTask(handler, messageWhat, resultTask);
        sat.execute(command);
        return sat;
    }

    public static class ScriptAsyncTask extends AsyncTask<String, Void, Void> {

        private boolean isDropResult = false;

        public Handler mHandler;
        public int mWhat;
        public ResultTask mResultTask;
        public MessageHandler mMessageHandler;

        public ScriptAsyncTask(MessageHandler handler, int messageWhat, ResultTask resultTask) {
            this.mMessageHandler = handler;
            this.mHandler = mMessageHandler.mHandler;
            this.mWhat = messageWhat;
            this.mResultTask = resultTask;
        }

        public void setFailureResult(String error) {
            try {
                mResultTask.mResult.put(MessageHandler.OP_RESULT_CODE, MessageHandler.OP_FAILURE);
                mResultTask.mResult.put(MessageHandler.OP_RESULT_INFO, error);
            } catch (JSONException e) {
                Slog.w(Space9dManagerService.TAG, "set result", e);
            }
            mHandler.removeMessages(mWhat);
            mMessageHandler.getServer().send(mResultTask.socket, mResultTask.mResult.toString());
        }

        public void setSuccessResult(int exitCode, String scriptMsg) {
            try {
                mResultTask.mResult.put(MessageHandler.OP_RESULT_CODE, MessageHandler.OP_SUCCESS);
                mResultTask.mResult.put(MessageHandler.OP_RESULT_INFO, scriptMsg);
                mResultTask.mResult.put("exitCode", exitCode);
            } catch (JSONException e) {
                Slog.w(Space9dManagerService.TAG, "set result", e);
            }
            mHandler.removeMessages(mWhat);
            mMessageHandler.getServer().send(mResultTask.socket, mResultTask.mResult.toString());
        }

        @Override
        protected Void doInBackground(String... strings) {
            File parentFile = new File("/data/system/scripts/");
            if (!parentFile.exists()) {
                parentFile.mkdirs();
            }
            File mScriptFile;
            int taskId = -1;
            try {
                taskId = mResultTask.mResult.getInt("taskId");
                mScriptFile = new File(parentFile,
                        "task_" + taskId + "_" + System.currentTimeMillis() + ".sh");
            } catch (JSONException e) {
                mScriptFile = new File(parentFile, "task_error_" +
                        System.currentTimeMillis() + ".sh");
            }
            boolean write = CommonHelper.writeToFile(mScriptFile.getPath(), strings[0] /** command */);
            if (!write) {
                setFailureResult("write script to file error");
                return null;
            }

            LocalSocket socket = establishConnection();
            if (socket == null) {
                setFailureResult("establish connection failure");
                return null;
            }

            String path = mScriptFile.getPath();
            try {
                OutputStream outputStream = socket.getOutputStream();
                outputStream.write(String.format("%4d", path.length()).getBytes());
                outputStream.write(path.getBytes());
                outputStream.flush();
                outputStream.close();
            } catch (IOException e) {
                setFailureResult("send file path failure");
                mScriptFile.delete();
                return null;
            }

            int exitCode;
            String scriptMsg;
            try {
                byte[] buffer = new byte[4];
                InputStream inputStream = socket.getInputStream();
                inputStream.read(buffer);
                exitCode = Integer.valueOf(new String(buffer));

                inputStream.read(buffer);
                int length = Integer.valueOf(new String(buffer));
                byte[] message = new byte[length];
                inputStream.read(message);
                scriptMsg = new String(message);
            } catch (IOException e) {
                exitCode = -1;
                scriptMsg = e.getMessage();
            }

            Slog.v(Space9dManagerService.TAG, "exitCode: " + exitCode + " msg: " + scriptMsg);

            if (!isDropResult) {
                setSuccessResult(exitCode, scriptMsg);
            } else {
                Slog.w(Space9dManagerService.TAG,
                        String.format("timeout drop task result (%d)", taskId));
            }
            mScriptFile.delete();
            return null;
        }

        public LocalSocket establishConnection() {
            LocalSocket localSocket = new LocalSocket(LocalSocket.SOCKET_STREAM);
            LocalSocketAddress address = new LocalSocketAddress("script_guard",
                    LocalSocketAddress.Namespace.ABSTRACT);
            try {
                localSocket.connect(address);
                return localSocket;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Void unused) {
            super.onPostExecute(unused);
        }

        public void setTimeOut() {
            isDropResult = true;
        }
    }
}
