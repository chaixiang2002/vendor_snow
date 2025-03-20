package com.android.server.component;

import static com.android.server.Space9dManagerService.TAG;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Slog;

import com.android.server.SystemInfo;

/**
 * Listening notifications
 */
public class NotificationService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        Slog.v(TAG, "onNotificationPosted: " + sbn);
        SystemInfo.getInstance(getApplicationContext()).postNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        Slog.v(TAG, "onNotificationRemoved: " + sbn);
        SystemInfo.getInstance(getApplicationContext()).removeNotification(sbn);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Slog.v(TAG, "onListenerConnected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Slog.v(TAG, "onListenerDisconnected");
        SystemInfo.getInstance(getApplicationContext()).bindNotification();
    }
}
