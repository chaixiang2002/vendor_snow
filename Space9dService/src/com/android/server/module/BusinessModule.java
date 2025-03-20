package com.android.server.module;

import static com.android.internal.space.ConfigProperty.TYPE_BOOL;
import static com.android.internal.space.ConfigProperty.TYPE_COMB;
import static com.android.internal.space.ConfigProperty.TYPE_INT;
import static com.android.internal.space.ConfigProperty.TYPE_MULTI;
import static com.android.server.Space9dManagerService.TAG;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;
import android.os.SystemProperties;

import android.annotation.NonNull;

import com.android.internal.space.ConfigProperty;
import com.android.server.comm.AppHelper;
import com.android.server.comm.CommonHelper;
import com.android.server.comm.HttpHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BusinessModule implements IModule {

    public static final String NAME = "business";

    private static final String DEFAULT_PATH = "/system/etc/service_config.xml";
    private static final String PATH = ModuleManager.COMMON_DIR + "service_config.xml";

    private final ArrayMap<String, ConfigProperty> mProperties = new ArrayMap<>();

    public static final String INSTALL_VERIFY = "install_verify";
    public static final String ACTIVITY_CONTROL = "start_activity_control";
    public static final String SPECIAL_PACKAGES = "special_packages";
    public static final String PRIVILEGE_PACKAGE = "privilege_packages";
    public static final String NOT_INSTALL_SPECIFIC_APP = "not_install_specific_app";

    private Context mContext;
    private AppUpdater mUpdater;

    public class AppUpdater {
        private Handler mH;
        private static final int WHAT_UPDATE_SPECIAL_PACKAGES = 1;
        private static final int INTERVAL_DEFAULT = 30 * 60 * 1000;
        private int UPDATE_INTERVAL = INTERVAL_DEFAULT;
        private ArrayList<Integer> mPrivilegeUidArray;
        private String[] mPrivilegeApps;
        private List<String> mSpecialApps = new ArrayList<>();
        private Context mContext;

        private String server;
        Thread updateThread;

        AppUpdater(Context context, String server, String[] apps) {
            Slog.v(TAG, "[AppUpdater] init AppUpdater");
            this.mContext = context;
            this.server = server;
            this.mPrivilegeApps = apps;
            initPrivilegeApps();
            initUpdateInterval();
            mH = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(@NonNull Message msg) {
                    super.handleMessage(msg);
                    if (msg.what == WHAT_UPDATE_SPECIAL_PACKAGES) {
                        if (updateThread != null && updateThread.isAlive()) {
                            updateThread.interrupt();
                            updateThread = null;
                        }
                        updateThread = new Thread(() -> startUpdate(), "update_thread");
                        updateThread.start();
                    }
                    mH.sendEmptyMessageDelayed(WHAT_UPDATE_SPECIAL_PACKAGES, UPDATE_INTERVAL);
                }
            };
            mH.sendEmptyMessage(WHAT_UPDATE_SPECIAL_PACKAGES);
        }

        public void startUpdate() {
            Slog.v(TAG, "[AppUpdater] startUpdate");
            List<String> strings = AppHelper.getAllInstalledApps(mContext);
            if (strings.isEmpty()) {
                Slog.v(TAG, "[AppUpdater] No third party apps are installed");
                return;
            }
            // strings.addAll(mSpecialApps);
            String apps = String.join(",", strings);
            Slog.v(TAG, "[AppUpdater] getAllInstalledApps: " + apps);
            ArrayMap<String, String> body = new ArrayMap<>();
            body.put("ip", CommonHelper.getIPv4Address());
            body.put("packageNames", apps);
            HttpHelper.HttpResult result = HttpHelper.doPost(server, body);
            if (result.isOk()) {
                mSpecialApps.clear();
                try {
                    JSONObject resultJSONObject = new JSONObject(result.response);

                    if (resultJSONObject.getInt("code") == 0) {
                        JSONArray hidedApps = resultJSONObject.getJSONObject("data").getJSONArray("packageList");
                        for (int i = 0; i < hidedApps.length(); i++) {
                            mSpecialApps.add(hidedApps.getString(i));
                        }
                    }

                } catch (JSONException e) {
                    Slog.w(TAG, "[AppUpdater] get hidden apps remotely error", e);
                }
                Slog.v(TAG, "[AppUpdater] get hidden apps remotely: " + String.join(", ", mSpecialApps));
            }

        }

        private void initPrivilegeApps() {
            mPrivilegeUidArray = new ArrayList<>();
            if (mPrivilegeApps != null && mPrivilegeApps.length > 0) {
                int tmpUid;
                for (String app : mPrivilegeApps) {
                    if (!AppHelper.isAppInstalled(mContext, app)) {
                        continue;
                    }
                    tmpUid = AppHelper.getAppUid(mContext, app);
                    Slog.v(TAG, "[AppUpdater] getAppUid, app: " + app + " ,uid: " + tmpUid);

                    if (tmpUid != -1) {
                        mPrivilegeUidArray.add(tmpUid);
                    }
                }
            }
            // 将系统应用和所有内置应用的pid放入特权列表
            mPrivilegeUidArray.add(1000);
            List<Integer> internalApps = AppHelper.getAllInternalApps(mContext);
            for (Integer uid : internalApps) {
                mPrivilegeUidArray.add(uid);
            }
            Slog.v(TAG, "[AppUpdater] initPrivilegeApps,privList: "
                    + mPrivilegeUidArray.toString());
        }

        private void initUpdateInterval() {
            int updateInterval = INTERVAL_DEFAULT;
            try {
                updateInterval = SystemProperties.getInt("s9.app_filter.update_interval", INTERVAL_DEFAULT);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get system property when get update_interval: " + e.getMessage());
            }
            UPDATE_INTERVAL = updateInterval;
            Slog.i(TAG, "[AppUpdater] update interval: " + UPDATE_INTERVAL);

        }

        public int[] onUpdateHiddenApps(List<String> hiddenList) {
            hiddenList.clear();
            hiddenList.addAll(mSpecialApps);
            int[] res = new int[mPrivilegeUidArray.size()];
            for (int i = 0; i < mPrivilegeUidArray.size(); i++) {
                res[i] = mPrivilegeUidArray.get(i);
            }
            return res;
        }
    }

    public AppUpdater getUpdater() {
        return mUpdater;
    }

    @Override
    public void init(Context context) {
        this.mContext = context;
        loadFromStorage();

        ConfigProperty hiddenProperty = getProp(SPECIAL_PACKAGES);
        ConfigProperty privilegeProperty = getProp(PRIVILEGE_PACKAGE);
        if (hiddenProperty != null) {
            ConfigProperty.CombProperty hideCp = hiddenProperty.valueOfComb();
            if (hideCp != null && hideCp.enable && !TextUtils.isEmpty(hideCp.combValue)) {
                String[] privList = null;
                if (privilegeProperty != null) {
                    ConfigProperty.MultiProperty privMp = privilegeProperty.valueOfMulti();
                    privList = privMp.multiValue;
                }
                mUpdater = new AppUpdater(context, hideCp.combValue, privList);
            }
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ConfigProperty getProp(String propName) {
        if (mProperties.containsKey(propName)) {
            return mProperties.get(propName);
        }
        return null;
    }

    @Override
    public boolean setProp(String name, String value) {
        boolean isUpdate = false;

        if (mProperties.containsKey(name)) {
            ConfigProperty configProperty = mProperties.get(name);
            int type = configProperty.getType();

            String[] vars = value.split(" ");
            if (vars.length != 3) {
                Slog.w(TAG, "setProp " + name + "=" + value + " error.");
                return false;
            }

            if (type == TYPE_INT) {
                ConfigProperty.IntProperty property = configProperty.valueOfInt();
                property.intValue = Integer.valueOf(vars[1].substring(2));
                isUpdate = true;
            } else if (type == TYPE_BOOL) {
                ConfigProperty.BoolProperty property = configProperty.valueOfBool();
                property.enable = Boolean.parseBoolean(vars[1].substring(2));
                isUpdate = true;
            } else if (type == TYPE_COMB) {
                ConfigProperty.CombProperty property = configProperty.valueOfComb();
                for (String item : vars) {
                    if (item.startsWith("e:")) {
                        property.enable = Boolean.parseBoolean(item.substring(2));
                        isUpdate = true;
                    } else if (item.startsWith("v:")) {
                        property.combValue = item.substring(2);
                        isUpdate = true;
                    }
                }
            } else if (type == TYPE_MULTI) {
                ConfigProperty.MultiProperty property = configProperty.valueOfMulti();
                for (String item : vars) {
                    if (item.startsWith("e:")) {
                        property.enable = Boolean.parseBoolean(item.substring(2));
                        isUpdate = true;
                    } else if (item.startsWith("v:")) {
                        property.multiValue = item.substring(2).split(",");
                        isUpdate = true;
                    }
                }
            }
        } else {
            String[] vars = value.split(" ");
            int type = -1;
            boolean enable = false;
            String eval = "";
            for (String item : vars) {
                if (item.startsWith("t:")) {
                    type = Integer.valueOf(item.substring(2));
                } else if (item.startsWith("e:")) {
                    enable = Boolean.parseBoolean(item.substring(2));
                } else if (item.startsWith("v:")) {
                    eval = item.substring(2);
                }
            }

            ConfigProperty configProperty = new ConfigProperty(type, name);
            if (type == TYPE_INT) {
                configProperty.setValue(Integer.valueOf(eval));
                isUpdate = true;
            } else if (type == TYPE_BOOL) {
                configProperty.setEnable(enable);
                isUpdate = true;
            } else if (type == TYPE_COMB) {
                configProperty.setEnable(enable);
                configProperty.setValue(eval);
                isUpdate = true;
            } else if (type == TYPE_MULTI) {
                configProperty.setEnable(enable);
                configProperty.setValue(eval.split(","));
                isUpdate = true;
            }

            if (isUpdate) {
                mProperties.put(name, configProperty);
            }
        }

        if (isUpdate) {
            storeToStorage();
        }
        return isUpdate;
    }

    @Override
    public String onDump() throws JSONException {
        JSONObject dump = new JSONObject();
        dump.put("moduleName", name());

        JSONArray array = new JSONArray();
        JSONObject item;
        ArrayMap<String, ConfigProperty> arrayMap = new ArrayMap<>(mProperties);
        for (String key : arrayMap.keySet()) {
            item = new JSONObject();
            item.put(key, arrayMap.get(key).toString());
            array.put(item);
        }
        dump.put("configs", array);
        return dump.toString(4);
    }

    @Override
    public String getLocalPath() {
        return PATH;
    }

    @Override
    public void storeToStorage() {
        try {
            writeToXml(getLocalPath(), new ArrayMap<>(mProperties));
        } catch (Exception e) {
            Slog.w(TAG, "storeToStorage: " + e.getMessage());
        }
    }

    @Override
    public void loadFromStorage() {
        ArrayMap<String, ConfigProperty> parseMap = null;
        try {
            parseMap = readFromXml(DEFAULT_PATH);
        } catch (Exception e) {
            Slog.w(TAG, "loadFromStorage: " + e.getMessage());
        }

        if (parseMap != null && parseMap.size() > 0) {
            mProperties.putAll(parseMap);
        }

        if (parseMap != null)
            parseMap.clear();

        try {
            parseMap = readFromXml(getLocalPath());
        } catch (Exception e) {
            Slog.w(TAG, "loadFromStorage: " + e.getMessage());
        }
        if (parseMap != null && parseMap.size() > 0) {
            mProperties.putAll(parseMap);
        }
    }

    private ArrayMap<String, ConfigProperty> readFromXml(String path)
            throws IOException, XmlPullParserException {
        ArrayMap<String, ConfigProperty> arrayMap = new ArrayMap<>();
        FileInputStream inputStream = new FileInputStream(path);

        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(inputStream, "utf-8");

        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                String name = parser.getAttributeValue(null, "name");
                if ("int".equals(tagName)) {
                    ConfigProperty property = new ConfigProperty(TYPE_INT, name);
                    property.setValue(Integer.valueOf(parser.nextText()));
                    arrayMap.put(name, property);
                } else if ("bool".equals(tagName)) {
                    ConfigProperty property = new ConfigProperty(TYPE_BOOL, name);
                    property.setEnable(Boolean.parseBoolean(parser.nextText()));
                    arrayMap.put(name, property);
                } else if ("comb".equals(tagName)) {
                    ConfigProperty property = new ConfigProperty(TYPE_COMB, name);
                    property.setEnable(Boolean.parseBoolean(parser.getAttributeValue(null, "enable")));
                    property.setValue(parser.nextText());
                    arrayMap.put(name, property);
                } else if ("multi".equals(tagName)) {
                    ConfigProperty property = new ConfigProperty(TYPE_MULTI, name);
                    property.setEnable(Boolean.parseBoolean(parser.getAttributeValue(null, "enable")));
                    property.setValue(parser.nextText().split(","));
                    arrayMap.put(name, property);
                }
            }
            eventType = parser.next();
        }
        inputStream.close();
        return arrayMap;
    }

    private void writeToXml(String path, ArrayMap<String, ConfigProperty> arrayMap)
            throws IOException, XmlPullParserException {
        XmlSerializer xmlSerializer = Xml.newSerializer();
        FileOutputStream outputStream = new FileOutputStream(path);
        xmlSerializer.setOutput(outputStream, "UTF-8");
        xmlSerializer.startDocument(null, true);
        xmlSerializer.startTag(null, "ConfigProperties");

        ConfigProperty property = null;
        for (String key : arrayMap.keySet()) {
            property = arrayMap.get(key);

            String tagType = property.getType() == TYPE_INT ? "int"
                    : property.getType() == TYPE_BOOL ? "bool" : property.getType() == TYPE_COMB ? "comb" : "multi";

            xmlSerializer.startTag(null, tagType);
            xmlSerializer.attribute(null, "name", key);
            if (property.getType() == TYPE_INT) {
                xmlSerializer.text(String.valueOf(property.valueOfInt().intValue));
            } else if (property.getType() == TYPE_BOOL) {
                xmlSerializer.text(String.valueOf(property.valueOfBool().enable));
            } else if (property.getType() == TYPE_COMB) {
                xmlSerializer.attribute(null, "enable",
                        String.valueOf(property.valueOfComb().enable));
                xmlSerializer.text(String.valueOf(property.valueOfComb().combValue));
            } else if (property.getType() == TYPE_MULTI) {
                xmlSerializer.attribute(null, "enable",
                        String.valueOf(property.valueOfMulti().enable));
                xmlSerializer.text(String.join(",", property.valueOfMulti().multiValue));
            }
            xmlSerializer.endTag(null, tagType);
        }
        xmlSerializer.endTag(null, "ConfigProperties");
        xmlSerializer.endDocument();
        outputStream.close();
    }
}
