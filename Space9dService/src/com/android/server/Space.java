package com.android.server;

import android.app.Application;

public class Space extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        new Space9dManagerService(this);
    }
}
