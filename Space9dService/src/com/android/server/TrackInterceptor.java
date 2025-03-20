package com.android.server;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemProperties;

import com.android.internal.space.NineDSpaceManager;
import com.android.internal.space.GoogleWrapper;

import java.util.List;

import android.util.Slog;

public class TrackInterceptor {

    private Context mContext;

    public TrackInterceptor(Context context) {
        this.mContext = context;

        updateGoogleUidProperty();
    }

    private void updateGoogleUidProperty() {
        StringBuffer buffer = new StringBuffer();
        PackageManager manager = mContext.getPackageManager();
        List<ApplicationInfo> applications = manager.getInstalledApplications(0);
        for (ApplicationInfo ai : applications) {
            if (GoogleWrapper.isGooglePackage(ai.packageName)) {
                buffer.append(ai.uid % 10000).append(",");
            }
        }
        int length = buffer.length();
        if (length > 0) {
            buffer.deleteCharAt(length - 1);
        }
        SystemProperties.set("s9.google.ids", buffer.toString());
    }

    public boolean onProcessTrack(String name, Bundle bundle) {
        boolean intercept = false;
        switch (name) {
            case NineDSpaceManager.STATUS_INSTALL_CALLBACK:
            case NineDSpaceManager.STATUS_UNINSTALL_CALLBACK:
                updateGoogleUidProperty();
                break;
            case NineDSpaceManager.STATUS_PROCESS_CREATE:
            case NineDSpaceManager.STATUS_PROCESS_DESTORY:
                intercept = true;
                break;
            default:
                break;
        }

        return intercept;
    }
}
