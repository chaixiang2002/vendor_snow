package com.android.server.module;

import android.content.Context;
import org.json.JSONException;

public interface IModule {
    void init(Context context);
    String name();
    Object getProp(String propName);
    boolean setProp(String name, String value);
    String onDump() throws JSONException;
    String getLocalPath();
    void storeToStorage();
    void loadFromStorage();
}
