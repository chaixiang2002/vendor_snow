package com.android.server;

import static com.android.server.Space9dManagerService.TAG;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.annotation.NonNull;

import com.android.internal.view.IInputContext;

import java.util.Properties;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

public class MessageHandler implements ServerSocket.MessageListener, SystemInfo.SystemInfoListener {

    public static final String OP_TYPE = "opType";

    private Space9dManagerService mService;
    public Handler mHandler;

    public static final String OP_RESULT_CODE = "resultCode";
    public static final String OP_RESULT_INFO = "resultInfo";

    public static final int OP_SUCCESS = 0;
    public static final int OP_FAILURE = 1;
    //modify by chenmin start
    // private static final String OP_SCREEN_ORIENTATION = "screen_orientation";
    //modify by chenmin end
    private static final String OP_SCREEN_ORIENTATION = "screenOrientation";
    private static final String OP_FOREGROUND_APP = "foreground_application";
    private static final String OP_EXEC_COMMAND = "exec_command";
    private static final String OP_COMMIT_TEXT = "commit_text";
    private static final String OP_HIDE_KEYBOARD = "hide_keyboard";
    private static final String OP_WRITE_CLIPBOARD = "clipboard_copy";
    private static final String OP_WRITE_CLIPBOARD_V2 = "clipboardCopy";
    private static final String OP_TOTAL_FRAMES = "total_frames";
    private static final String OP_CLIENT_MESSAGE = "client_message";
    private static final String OP_BOOT_COMPLETE = "boot_completed";
    private static final String OP_START_APP = "start_app";
    private static final String OP_FORCE_STOP_APP = "stop_app";
    private static final String OP_CLEAR_DATA = "clear_app";
    private static final String OP_INSTALL_PACKAGE = "install_package";
    private static final String OP_UNINSTALL_PACKAGE = "uninstall_package";
    private static final String OP_HEART_BEAT = "heart_beat";

    private static int isCopyFromArmStream = 0;

    private SystemInfo mInfo;

    public MessageHandler(Space9dManagerService service) {
        this.mService = service;

        mInfo = mService.getSystemInfo();
        mInfo.startListener(this);

        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);

                ScriptHelper.ScriptAsyncTask sat = (ScriptHelper.ScriptAsyncTask) msg.obj;
                sat.setTimeOut();

                JSONObject mResult = sat.mResultTask.mResult;
                try {
                    mResult.put(OP_RESULT_CODE, OP_FAILURE);
                    mResult.put(OP_RESULT_INFO, "script exec timeout");
                } catch (JSONException e) {
                    Slog.w(TAG, "script exec timeout", e);
                }

                Slog.v(TAG, String.format("handle delay message (what: %d, taskId: %d) timeout", msg.what));
                getServer().send(sat.mResultTask.socket, mResult.toString());
            }
        };
    }

    private boolean onInternalMessage(LocalSocket socket, String data) {
        if (data.startsWith("prop:")) {
            Properties properties = mService.getModuleManager().getSysProp(false);
            StringBuffer buffer = new StringBuffer();
            Set<String> keys = properties.stringPropertyNames();
            if (keys.size() > 0) {
                for (String key : keys) {
                    buffer.append(key).append('=').append(properties.getProperty(key).toString());
                }
                buffer.deleteCharAt(buffer.length() - 1);
            }
            getServer().send(socket, buffer.toString());
            return true;
        }
        return false;
    }

    @Override
    public void onMessageReceive(LocalSocket socket, String data) {
        if (onInternalMessage(socket, data)) {
            return;
        }
        JSONObject mResult = new JSONObject();
        try {
            JSONObject mData = new JSONObject(data);
            String opType = mData.getString(OP_TYPE);
            mResult.put(OP_TYPE, opType);

            switch (opType) {
                case OP_SCREEN_ORIENTATION:
                    setResult(mResult, OP_SUCCESS, String.valueOf(mService.getSystemInfo().getSystemOri()));
                    break;
                case OP_FOREGROUND_APP:
                    setResult(mResult, OP_SUCCESS, mService.getSystemInfo().getForegroundApp());
                    break;
                case OP_EXEC_COMMAND:
                    int msgWhat = (int)(System.currentTimeMillis() & Integer.MAX_VALUE);

                    String command = mData.getString("script");
                    final int taskId = mData.getInt("taskId");
                    mResult.put("taskId", taskId);

                    ScriptHelper.ResultTask task = new ScriptHelper.ResultTask();
                    task.socket = socket;
                    task.mResult = mResult;

                    ScriptHelper.ScriptAsyncTask sat = ScriptHelper.execScriptTask(this, msgWhat, command, task);

                    Slog.v(TAG, String.format("send delay message(what: %d, taskId: %d)", msgWhat, taskId));
                    long timeout = mData.has("timeout") ? mData.getLong("timeout") : 5 * 60;
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(msgWhat, sat), timeout * 1000);
                    break;
                case OP_COMMIT_TEXT:
                    String text = mData.getString("text");
                    boolean success = commitText(text);
                    if (success) {
                        setResult(mResult, OP_SUCCESS, "success");
                    } else {
                        setResult(mResult, OP_FAILURE, "input channel not connected");
                    }
                    break;

                case OP_WRITE_CLIPBOARD_V2:
                case OP_WRITE_CLIPBOARD:
                    String c_text = mData.getString("text");
                    boolean paste = mData.optBoolean("paste");
                    ClipboardManager cm = (ClipboardManager) mService.mContext.
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    isCopyFromArmStream++;//!
                    cm.setPrimaryClip(ClipData.newPlainText("client", c_text));

                    if (paste) {
                        boolean suc = commitText(c_text);
                        if (!suc) {
                            setResult(mResult, OP_FAILURE, "input channel not connected");
                            return;
                        }
                    }
                    setResult(mResult, OP_SUCCESS, "success");
                    break;
                case OP_HEART_BEAT:
                    setResult(mResult, OP_SUCCESS, "success");
                    break;
                // @Todo protocol
                default:
                    setResult(mResult, OP_FAILURE, "Protocol error: " + opType + " is not supported");
                    break;
            }
        } catch (JSONException e) {
            try {
                setResult(mResult, OP_FAILURE, e.getMessage());
            } catch (JSONException ex) {
                Slog.w(TAG, data, ex);
            }
        }
        getServer().send(socket, mResult.toString());
    }

    private void setResult(JSONObject result, int code, String msg) throws JSONException {
        result.put(OP_RESULT_CODE, code);
        result.put(OP_RESULT_INFO, msg);
    }

    protected ServerSocket getServer() {
        return mService.getServerSocket();
    }

    private boolean commitText(String text) {
        IInputContext input = mService.getInputContext();
        if (input != null) {
            try {
                input.commitText(text, 1);
                return true;
            } catch (RemoteException e) {
                Slog.w(TAG, "input channel not connected: " + e.getMessage());
            }
        }
        return false;
    }

    @Override
    public void onInfoChanged(String action, Bundle bundle) {
        try {
            if(action.equals("clipboardChanaged") && isCopyFromArmStream>0){
                isCopyFromArmStream--;
                return;
            }else{
                mService.sendTracking(action, bundle);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "onInfoChanged: action:" + action, e);
            isCopyFromArmStream=0;
        }
    }
}
