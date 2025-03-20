package com.android.server.module;

import static com.android.server.Space9dManagerService.TAG;

import android.content.Context;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.json.JSONObject;
import org.json.JSONException;

public final class ModuleManager {

    private Context mContext;

    private ArrayMap<String, IModule> mModules = new ArrayMap<>();

    public static final String COMMON_DIR = "/data/misc/s9/";

    public ModuleManager(Context context) {
        this.mContext = context;

        mModules.put(SystemModule.NAME, new SystemModule());
        mModules.put(BusinessModule.NAME, new BusinessModule());

        initModules();
    }

    public IModule getModule(String name) {
        return mModules.get(name);
    }

    public void initModules() {
        File dirFile = new File(COMMON_DIR);
        dirFile.mkdirs();

        for (String name : mModules.keySet()) {
            String propPath = mModules.get(name).getLocalPath();
            File profile = new File(propPath);
            if (!profile.exists()) {
                try {
                    profile.createNewFile();
                } catch (IOException e) {
                }
            }
            mModules.get(name).init(mContext);
        }
    }

    public Properties getSysProp(boolean isPublic) {
        SystemModule module = (SystemModule) getModule(SystemModule.NAME);
        return module.getProperties(isPublic);
    }

    public String dumpModule(String moduleName) {
        try {
            if (TextUtils.isEmpty(moduleName)) {
                JSONObject dumpJson = new JSONObject();
                for (String name : mModules.keySet()) {
                    dumpJson.put(name, mModules.get(name).onDump());
                }
                return dumpJson.toString(4);
            } else {
                IModule module = mModules.get(moduleName);
                return module.onDump();
            }
        } catch (JSONException e) {
            Slog.w(TAG, "dumpModule", e);
        }
        return "";
    }
}
