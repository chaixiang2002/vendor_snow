package com.android.server.module;

import static com.android.server.Space9dManagerService.TAG;

import android.content.Context;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;
import com.android.server.comm.AppHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;

public class SystemModule implements IModule {

    public static final String NAME = "system";

    private static final String PATH = ModuleManager.COMMON_DIR + "system.prop";

    private Properties mProperties = new Properties();

    private Context mContext;

    @Override
    public void init(Context context) {
        this.mContext = context;
        loadFromStorage();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String getProp(String propName) {
        synchronized (mProperties) {
            Object value = mProperties.get(propName);
            if (value != null) {
                return (String) value;
            }
            return null;
        }
    }

    public Properties getProperties(boolean isPublic) {
        Properties result = new Properties();
        synchronized (mProperties) {
            Set<String> keys = mProperties.stringPropertyNames();
            for (String key : keys) {
                if (!isPublic && key.startsWith("s9.")) {
                    result.put(key, mProperties.getProperty(key));
                } else if(isPublic && !key.startsWith("s9.")) {
                    result.put(key, mProperties.getProperty(key));
                }
            }
        }
        return result;
    }

    private void onPropSet(String name, String value) {
        if ("deny_traceme".equals(name)) {
            if (TextUtils.isEmpty(value)) {
                return;
            }
            String[] packages = value.split(",");
            StringBuffer buffer = new StringBuffer();
            int tint;
            for (String pkg : packages) {
                tint = AppHelper.getAppUid(mContext, pkg);
                if (tint != -1) {
                    buffer.append(tint).append(",");
                }
            }
            tint = buffer.length();
            if (tint > 0) {
                buffer.deleteCharAt(tint - 1);
            }
            SystemProperties.set("sys.common.deny_traceme", buffer.toString());
        }
    }

    @Override
    public boolean setProp(String name, String value) {
        synchronized (mProperties) {
            mProperties.put(name, value);
        }
        storeToStorage();
        onPropSet(name, value);
        return true;
    }

    @Override
    public String onDump() throws JSONException {
        JSONObject dump = new JSONObject();
        dump.put("moduleName", name());

        JSONArray values = new JSONArray();
        Set<String> keys = mProperties.stringPropertyNames();
        JSONObject item;
        for (String key : keys) {
            item = new JSONObject();
            item.put(key, mProperties.getProperty(key));
            values.put(item);
        }
        dump.put("props", values);
        return dump.toString(4);
    }

    @Override
    public String getLocalPath() {
        return PATH;
    }

    @Override
    public void storeToStorage() {
        try {
            mProperties.store(new FileOutputStream(getLocalPath()), name());
        } catch (IOException e) {
            Slog.w(TAG, "storeToStorage: " + e.getMessage());
        }
    }

    @Override
    public void loadFromStorage() {
        try {
            mProperties.load(new FileInputStream(getLocalPath()));
        } catch (IOException e) {
            Slog.w(TAG, "loadFromStorage: " + e.getMessage());
        }
    }
}
