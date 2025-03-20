package com.android.server;

import static com.android.internal.space.NineDSpaceManager.STATUS_BOOT_COMPLETED;
import static com.android.internal.space.NineDSpaceManager.STATUS_SCREEN_ORIENTATION;
import static com.android.internal.space.NineDSpaceManager.STATUS_POST_NOTIFICATION;
import static com.android.internal.space.NineDSpaceManager.STATUS_CLIPBOARD;
import static com.android.server.Space9dManagerService.TAG;

import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.service.notification.StatusBarNotification;
import android.util.Slog;
import android.view.Display;
import android.view.IRotationWatcher;
import android.view.IWindowManager;

import com.android.server.component.NotificationService;

import java.util.List;

public class SystemInfo {

    private int mSystemOri = -1;
    private boolean isSystemBooted;
    private IWindowManager iwm;
    private ClipboardManager icm;
    private INotificationManager inm;
    private SystemInfoListener mListener;
    private BootCompletedReceiver mReceiver;
    private Context mContext;

    private static SystemInfo instance;

    public static synchronized SystemInfo getInstance(Context context) {
        if (instance == null) {
            instance = new SystemInfo(context);
        }
        return instance;
    }

    private SystemInfo(Context context) {
        this.mContext = context;
        iwm = IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        icm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        inm = INotificationManager.Stub.asInterface(ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        isSystemBooted = SystemProperties.getInt("sys.boot_completed", 0) == 1;
    }

    private void init() {
        try {
            mSystemOri = iwm.getDefaultDisplayRotation();
        } catch (RemoteException e) {
            Slog.w(TAG, "getDefaultDisplayRotation", e);
        }
    }

    public int getSystemOri() {
        if (mSystemOri < 0 || mSystemOri > 3) {
            try {
                mSystemOri = iwm.getDefaultDisplayRotation();
            } catch (RemoteException e) {
            }
        }
        return mSystemOri;
    }

    public boolean isSystemBooted() {
        return isSystemBooted;
    }

    public void startListener(SystemInfoListener listener) {
        this.mListener = listener;

        try {
            iwm.watchRotation(new IRotationWatcher.Stub() {
                @Override
                public void onRotationChanged(int i) throws RemoteException {
                    mSystemOri = i;

                    Bundle data = new Bundle();
                    //modify by chenmin start
                    // data.putInt("ori", i);
                    data.putString("ori",String.valueOf(i));
                    //modify by chenmin end
                    if (mListener != null) {
                        mListener.onInfoChanged(STATUS_SCREEN_ORIENTATION, data);
                    }
                }
            }, Display.DEFAULT_DISPLAY);
        } catch (RemoteException e) {
            Slog.w(TAG, "watchRotation", e);
        }

        icm.addPrimaryClipChangedListener(new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                ClipData clipData = icm.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    ClipData.Item item = clipData.getItemAt(0);
                    CharSequence text = item.getText();
                    if (text != null) {
                        String textStr = text.toString();
                        if (textStr.length() > 3000) {
                            textStr = textStr.substring(0, 3000);
                            Slog.d("Space", "length()>3000, truncated to 3000");
                        }

                        if (mListener != null) {
                            Bundle bundle = new Bundle();
                            bundle.putString("clipData", textStr);
                            if(clipData.getDescription().getExtras() != null){
                                bundle.putString("opMode", clipData.getDescription().getExtras().getString("operation_type"));
                                Slog.d("Space", "clipData.getDescription().getExtras()"+clipData.getDescription().getExtras().getString("operation_type"));
                            }else{
                                Slog.d("Space", "clipData.getDescription().getExtras() NULL!!!!!!!");
                            }
                            mListener.onInfoChanged(STATUS_CLIPBOARD, bundle);
                        }
                    }
                }
            }
        });

        bindNotification();

        IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
        mReceiver = new BootCompletedReceiver();
        mContext.registerReceiver(mReceiver, filter);
    }

    public void postNotification(StatusBarNotification sbn) {
        if (mListener != null) {
            Bundle bundle = new Bundle();
            bundle.putString("package", sbn.getPackageName());

            Notification notification = sbn.getNotification();
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            bundle.putString("text", text == null ? "" : text.toString());
            mListener.onInfoChanged(STATUS_POST_NOTIFICATION, bundle);
        }
    }

    public void removeNotification(StatusBarNotification sbn) {
/**
        if (mListener != null) {
            Bundle bundle = new Bundle();
            bundle.putParcelable("remove", sbn);
            mListener.onInfoChanged(STATUS_POST_NOTIFICATION, bundle);
        }
*/
    }

    public void bindNotification() {
        /* deleted by ntimespace at 20241209
        try {
            inm.setNotificationListenerAccessGranted(new ComponentName(mContext.getPackageName(),
                    NotificationService.class.toGenericString()), true);
        } catch (RemoteException e) {
            Slog.w(TAG, "setNotificationListenerAccessGranted", e);
        }
        */ 
    }

    public StatusBarNotification[] getAllNotifications() {
        NotificationManager manager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        return manager.getActiveNotifications();
    }

    public String getForegroundApp() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes != null && !processes.isEmpty()) {
            for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
                if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return processInfo.pkgList[0];
                }
            }
        }
        return null;
    }

    private class BootCompletedReceiver extends BootReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            super.onReceive(context, intent);
            isSystemBooted = true;

            if (mListener != null) {
                mListener.onInfoChanged(STATUS_BOOT_COMPLETED, null);
            }
        }
    }

    public interface SystemInfoListener {
        void onInfoChanged(String action, Bundle bundle);
    }
}
