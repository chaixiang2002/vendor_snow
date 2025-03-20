package com.android.server.mock;

import static com.android.server.Space9dManagerService.TAG;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.text.TextUtils;

import com.android.internal.space.api.AmMock;
import com.android.internal.space.api.BatteryMock;
import com.android.internal.space.api.BluetoothMock;
import com.android.internal.space.api.ConnectivityMock;
import com.android.internal.space.api.IMockCallback;
import com.android.internal.space.api.VpnMock;
// import com.android.internal.space.api.WiFiMock;
import com.android.internal.space.api.GPSMock;
import com.android.internal.space.NineDSpaceManager;
import android.os.Bundle;
import com.android.server.comm.CommonHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MockManager {
    private static final String DEFAULT_MOCK_PATH = "/system/etc/default_mock.prop";
    private static final String SIM_MOCK_NAME = "sim";
    private static final String SIM_MOCK_PATH = "/data/misc/radio/sim.prop";

    private Map<String, Object> mDefaultProps;

    private static final String[] SUPPORT_MOCK_NAME = {
            BatteryMock.NAME,
            BluetoothMock.NAME,
            ConnectivityMock.NAME,
            VpnMock.NAME,
            // WiFiMock.NAME,
            SIM_MOCK_NAME,
            AmMock.NAME,
            GPSMock.NAME,
    };

    private ArrayMap<String, MockInfo> mMockMap = new ArrayMap<>();

    private Context mContext;

    public MockManager(Context context) {
        this.mContext = context;
        initMockFile();
        loadMockInfo();
    }

    private void initMockFile() {
        File file = new File("/data/misc/mock/");
        if (file.exists() && file.isFile()) {
            file.delete();
        } else if (!file.exists()) {
            file.mkdirs();
        }
    }

    private Map<String, Object> getDefMap(String name) {
        if (mDefaultProps == null) {
            mDefaultProps =  CommonHelper.readMapFile(DEFAULT_MOCK_PATH);
        }

        Map<String, Object> initMap = new ArrayMap();
        for (String mockKey : mDefaultProps.keySet()) {
            if (mockKey.startsWith(name + ".")) {
                initMap.put(mockKey.substring(name.length() + 1), mDefaultProps.get(mockKey));
            }
        }
        return initMap;
    }

    private void loadMockInfo() {
        mMockMap.clear();

        MockInfo mockInfo;
        String path;
        for (String name : SUPPORT_MOCK_NAME) {
            mockInfo = new MockInfo();
            mockInfo.map.putAll(getDefMap(name));
            if (SIM_MOCK_NAME.equals(name)) {
                path = SIM_MOCK_PATH;
                mockInfo.map.putAll(CommonHelper.readPropFile(path));
            } else {
                path = "/data/misc/mock/" + name + ".prop";
                mockInfo.map.putAll(CommonHelper.readMapFile(path));
            }
            //chenm
           // mockInfo.map.putAll(getDefMap(name));
           //chenm
            mMockMap.put(name, mockInfo);
        }
    }

    private void syncMockInfo(String name, Map<String, Object> map) {
        if (SIM_MOCK_NAME.equals(name)) {
            CommonHelper.writeMapPropFile(SIM_MOCK_PATH, map);
        } else {
            String path = "/data/misc/mock/" + name + ".prop";
            CommonHelper.writeMapToFile(path, map);
        }
    }

    public class MockInfo {
        public Map<String, Object> map;
        public IMockCallback callback;

        public MockInfo() {
            map = new ArrayMap<>();
        }
    }

    public static boolean isSupportMock(String name) {
        return Arrays.asList(SUPPORT_MOCK_NAME).contains(name);
    }

    public Map<String, Object> addMockCallback(String name, IMockCallback callback) {
        MockInfo mockInfo;
        if (mMockMap.containsKey(name)) {
            mockInfo = mMockMap.get(name);
        } else {
            mockInfo = new MockInfo();
        }
        mockInfo.callback = callback;
        mMockMap.put(name, mockInfo);

        Map<String, Object> result = new ArrayMap();
        result.putAll(mockInfo.map);
        return result;
    }

    public Map<String, Object> getMockInfo(String name) {
        MockInfo mockInfo = mMockMap.getOrDefault(name, null);
        if (mockInfo == null) {
            return new ArrayMap<String, Object>();
        }

        Map<String, Object> result = new ArrayMap();
        result.putAll(mockInfo.map);
        return result;
    }

    public boolean isTakeEffect(String name) {
        MockInfo mockInfo = mMockMap.getOrDefault(name, null);
        if (mockInfo == null) {
            return false;
        }
        return mockInfo.callback != null || SIM_MOCK_NAME.equals(name);
    }

    public String getMockPrefix(String key) {
        int index = key.indexOf(".");
        if (index != -1) {
            return key.substring(0, index);
        }
        return null;
    }

    public String toMockKey(String key) {
        int index = key.indexOf(".");
        if (index != -1) {
            return key.substring(index + 1);
        }
        return null;
    }

    public String[] updateMock(Map<String, Object> addMap, List<String> removeKeys) {
        // android.util.Log.d("0002|", "SUPPORT_MOCK_NAME= " + SUPPORT_MOCK_NAME);
        // android.util.Log.d("0002|", "SUPPORT_MOCK_NAME= " + GPSMock.NAME);
        // android.util.Log.d("0002|", "isSupportMock= " + isSupportMock(GPSMock.NAME));
        // SUPPORT_MOCK_NAME
        // isSupportMock(GPSMock.NAME);
        List<String> mocks = new ArrayList<>();
        if (addMap != null && addMap.size() > 0) {
            String name="";
            for (String item : addMap.keySet()) {
                name = getMockPrefix(item);
                android.util.Log.d(TAG, "updateMock---add-name= " + name);
                if (name == null) {
                    Slog.w(TAG, String.format("Unknown mock information %s=%s", item, addMap.get(item)));
                    continue;
                }
                if (!isSupportMock(name)) {
                    Slog.w(TAG, String.format("Unknown mock tag: %s", name));
                    // Slog.w(TAG, String.format("11111111111 %s", name));
                    // android.util.Log.d("0002|", "SUPPORT_MOCK_NAME= " + SUPPORT_MOCK_NAME);
                    // android.util.Log.d("0002|", "SUPPORT_MOCK_NAME= " + GPSMock.NAME);
                    // android.util.Log.d("0002|", "isSupportMock= " + isSupportMock(GPSMock.NAME));
                    // Slog.w(TAG, String.format("Unknown mock tag: %s", name));

                    continue;
                }

                mMockMap.get(name).map.put(toMockKey(item), addMap.get(item));

                if (!mocks.contains(name)) {
                    mocks.add(name);
                }
            }
            if(name.equals("location")){
                updateGPS();
            }
        }

        if (removeKeys != null && removeKeys.size() > 0) {
            String name;
            for (String item : removeKeys) {
                name = getMockPrefix(item);
                android.util.Log.d(TAG, "updateMock---remove-name= " + name);
                if (name == null) {
                    Slog.w(TAG, String.format("Unknown mock information %s", item));
                    continue;
                }
                if (!isSupportMock(name)) {
                    Slog.w(TAG, String.format("Unknown mock tag: %s", name));
                    continue;
                }
                mMockMap.get(name).map.remove(toMockKey(item));

                if (!mocks.contains(name)) {
                    mocks.add(name);
                }
            }
        }

        MockInfo mockInfo;
        Map<String, Object> arrayMap;
        for (String name : mocks) {
            mockInfo = mMockMap.get(name);
            arrayMap = getMockInfo(name);
            android.util.Log.d(TAG, "updateMock--mockInfo----mockInfo= " + mockInfo);
            if (mockInfo != null) {
                syncMockInfo(name, arrayMap);
                try {
                    android.util.Log.d(TAG, "updateMock--mockInfo----callback= " +mockInfo.callback );
                    if (mockInfo.callback != null) {
                        mockInfo.callback.onCallback(arrayMap);
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "onCallback", e);
                }
            }
        }
        String[] results = new String[mocks.size()];
        mocks.toArray(results);
        return results;
    }

    public String dump(String opt) {
        try {
            JSONObject dumpJson = new JSONObject();

            boolean isDumpAll = false;
            if (TextUtils.isEmpty(opt) || "all".equals(opt)) {
                isDumpAll = true;
            }

            if (isDumpAll) {
                for (String name : SUPPORT_MOCK_NAME) {
                    dumpJson.put(name, dumpStubMock(name));
                }
            } else if (isSupportMock(opt)) {
                dumpJson.put(opt, dumpStubMock(opt));
            }
            return dumpJson.toString(4);
        } catch (JSONException e) {
            return e.getMessage();
        }
    }

    private JSONObject dumpStubMock(String name) throws JSONException {
        JSONObject mockJson = new JSONObject();
        Map<String, Object> map = getMockInfo(name);
        mockJson.put("apply", isTakeEffect(name) ? true : false);

        for (String key : map.keySet()) {
            mockJson.put(key, map.get(key));
        }
        return mockJson;
    }


    public String updateGPS(){
        if(mMockMap==null || mMockMap.get("location")==null || mMockMap.isEmpty()){
            return "";
        }
        String mock1 = (String) mMockMap.get("location").map.getOrDefault("mock", "null");
        String lat = (String) mMockMap.get("location").map.getOrDefault("lat", "null");
        String lon = (String) mMockMap.get("location").map.getOrDefault("lon", "null");
        String alt = (String) mMockMap.get("location").map.getOrDefault("alt", "null");

        if (mock1.equals("null") || lat.equals("null") || lon.equals("null")) {
            return "";
        }

        // 将字符串转换为双精度数值数组
        double latitude = Double.parseDouble(lat);
        double longitude = Double.parseDouble(lon);
        double altitude = Double.parseDouble(alt);

        // 创建数组并放入Bundle中
        double[] locationData = new double[]{latitude, longitude, altitude};

        Bundle dataPacket = new Bundle();
        dataPacket.putDoubleArray("data", locationData);

        // 如果你需要调试打印，可以查看数组内容
        Slog.d("LocationData", Arrays.toString(locationData));
        dataPacket.putString("resultInfo", "success");
        
        NineDSpaceManager mSpaceManager = (NineDSpaceManager) mContext.getSystemService(Context.SPACE_SERVICE);
        mSpaceManager.sendTracking("gpsData", dataPacket);
        // mContext.getSystemService(Context.SPACE_SERVICE).sendTracking("gpsData", dataPacket);
        return "";
    }
}
