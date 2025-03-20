package com.android.server;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.ArrayMap;
import android.util.Slog;
import android.text.TextUtils;
import com.android.internal.space.ConfigProperty;
import com.android.internal.space.NineDSpaceManager;
import com.android.internal.space.INineDSpace;
import com.android.internal.space.api.IMockCallback;

import com.android.internal.view.IInputContext;

import com.android.server.comm.AppHelper;
import com.android.server.comm.CommonHelper;
import com.android.server.comm.HttpHelper;
import com.android.server.mock.MockManager;
import com.android.server.module.BusinessModule;
import com.android.server.module.IModule;
import com.android.server.module.SystemModule;
import com.android.server.module.ModuleManager;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FilenameFilter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

public class Space9dManagerService extends INineDSpace.Stub {

    public static final String TAG = "Space";

    public Context mContext;

    private ServerSocket mServerSocket;
    private SystemInfo mSystemInfo;
    private TrackInterceptor mInterceptor;
    private ModuleManager mModuleManager;
    private MockManager mMockManager;

    private IInputContext mInputContext;

    public Space9dManagerService(Context context) {
        this.mContext = context;

        this.mModuleManager = new ModuleManager(mContext);
        this.mMockManager = new MockManager(mContext);

        this.mInterceptor = new TrackInterceptor(mContext);

        this.mSystemInfo = SystemInfo.getInstance(mContext);
        this.mServerSocket = new ServerSocket(new MessageHandler(this));
        this.mServerSocket.startServer();

        onStart();
    }

    public SystemInfo getSystemInfo() {
        return mSystemInfo;
    }

    public ServerSocket getServerSocket() {
        return mServerSocket;
    }

    public IInputContext getInputContext() {
        return mInputContext;
    }

    public ModuleManager getModuleManager() {
        return mModuleManager;
    }

    public void onStart() {
        ServiceManager.addService(Context.SPACE_SERVICE, this);
        Slog.d(TAG, "onStart: addService " + Context.SPACE_SERVICE);
    }

    @Override
    public boolean isAutoModeEnabled(String mode) {
        IModule module = mModuleManager.getModule(SystemModule.NAME);
        String propVal = (String) module.getProp(mode);
        return TextUtils.isEmpty(propVal) ? true : Boolean.parseBoolean(propVal);
    }

    @Override
    public void syncInputContext(IInputContext inputContext) throws RemoteException {
        if (inputContext != null) {
            mInputContext = inputContext;
        }
    }

    @Override
    public boolean installVerify(String apkPath, String packageName) {
        IModule module = mModuleManager.getModule(BusinessModule.NAME);
        ConfigProperty property = (ConfigProperty) module.getProp(BusinessModule.INSTALL_VERIFY);
        if (property == null) {
            return true;
        }

        ConfigProperty.CombProperty cp = property.valueOfComb();
        String server = cp.combValue;

        if (!cp.enable || TextUtils.isEmpty(server)) {
            return true;
        }

        ArrayMap<String, String> bodyMap = new ArrayMap<>();
        bodyMap.put("ip", CommonHelper.getIPv4Address());
        bodyMap.put("packageName", packageName);
        try {
            bodyMap.put("appName", URLEncoder.encode(AppHelper.getAppNameV2(mContext, apkPath),
                    "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            bodyMap.put("appName", AppHelper.getAppNameV2(mContext, apkPath));
        }
        bodyMap.put("versionCode",
                String.valueOf(AppHelper.getAppVersionCodeV2(mContext, apkPath)));
        bodyMap.put("versionName", AppHelper.getAppVersionNameV2(mContext, apkPath));
        try {
            Signature signature = AppHelper.getAppSignatureV2(mContext, apkPath);

            bodyMap.put("sign", signature.toCharsString());
            String[] certificates = AppHelper.decodeSignatures(signature.toByteArray());
            bodyMap.put("issuser", certificates[0]);
            bodyMap.put("subject", certificates[1]);
            bodyMap.put("thumbprint", certificates[2]);
        } catch (Exception e) {
            Slog.w(TAG, "installVerify get sign error", e);
        }

        HttpHelper.HttpResult result = HttpHelper.doPost(server, bodyMap);
        if (result.isOk()) {
            try {
                JSONObject resultJSONObject = new JSONObject(result.response);

                if (resultJSONObject.getInt("code") == 0
                        && resultJSONObject.getJSONObject("data").getInt("allowInstall") == 1) {

                    return true;
                }

            } catch (JSONException e) {
                Slog.w(TAG, "installVerify" + packageName + " error", e);
            }

        }

        return false;
    }
//chenmin
    @Override
    public boolean isNotInstallSpecificApp(String apkPath) {
         IModule module = mModuleManager.getModule(BusinessModule.NAME);
        ConfigProperty cp = (ConfigProperty) module.getProp(BusinessModule.NOT_INSTALL_SPECIFIC_APP);
        if (cp == null) {
            Slog.i("TAG", "IsNInstSpApp:cp=null return false");
            return false;
        }
        ConfigProperty.MultiProperty mp = cp.valueOfMulti();
        if (!mp.enable) {
            Slog.i("TAG", "IsNInstSpApp:enable=false");
            return false;
        }

        ArrayList<String> list = new ArrayList<String>(Arrays.asList(mp.multiValue)) ;
        File dir = new File(apkPath);
        FilenameFilter filter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                // return name.equals("libvmtools.so");
                return list.contains(name);
            }
        };
        List<File> fileList = getFilesWithCondition(dir, filter);
        // for (File file : fileList) {
        //     Slog.w("TAG", "IsNInstSpApp:filepatch="+file.getAbsolutePath());
        // }
        if (fileList.size() > 0) {
            return true;
        }
        return false;
    }

    private List<File> getFilesWithCondition(File dir, FilenameFilter filter) {
        List<File> fileList = new ArrayList<>();

        File[] files = dir.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isDirectory()) {
                    fileList.addAll(getFilesWithCondition(file, filter));
                } else {
                    if (filter.accept(dir, file.getName())) {
                        fileList.add(file);
                    }
                }
            }
        }

        return fileList;
    }
//chenmin
    @Override
    public boolean onActivityStarting(int callingUid, String callingPackage,
            Intent targetIntent) {
        IModule module = mModuleManager.getModule(BusinessModule.NAME);
        ConfigProperty cp = (ConfigProperty) module.getProp(BusinessModule.ACTIVITY_CONTROL);
        if (cp == null) {
            return true;
        }
        ConfigProperty.MultiProperty mp = cp.valueOfMulti();
        if (!mp.enable) {
            return true;
        }

        if (callingUid == Process.SYSTEM_UID ||
                callingUid == Process.SHELL_UID ||
                callingUid == Process.ROOT_UID) {
            return true;
        }

        PackageManager manager = mContext.getPackageManager();
        ResolveInfo resolveInfo = manager.resolveActivity(targetIntent, 0);
        if (resolveInfo == null) {
            return true;
        }

        final boolean isInternalStart = callingPackage.equals(resolveInfo.resolvePackageName);
        boolean isPrivilegeApp = false;
        for (int i = 0; i < mp.multiValue.length; i++) {
            if (resolveInfo.resolvePackageName.equals(mp.multiValue[i])) {
                isPrivilegeApp = true;
                break;
            }
        }
        if (isPrivilegeApp || isInternalStart) {
            return true;
        }
        return false;
    }

    @Override
    public void updateHiddenApp() throws RemoteException {
        Slog.v(TAG, "[Space9dManagerService] start updateHiddenApp");
        IModule module = mModuleManager.getModule(BusinessModule.NAME);
        BusinessModule bm = (BusinessModule) module;
        BusinessModule.AppUpdater updater = bm.getUpdater();
        if (updater != null) {
            updater.startUpdate();
        } else {
            Slog.w(TAG, "[Space9dManagerService] updateHiddenApp failed, null ptr");
        }
    }

    @Override
    public int[] getHiddenPackages(List<String> hiddenList) throws RemoteException {
        IModule module = mModuleManager.getModule(BusinessModule.NAME);
        BusinessModule bm = (BusinessModule) module;
        BusinessModule.AppUpdater updater = bm.getUpdater();
        if (updater != null) {
            int res[] = updater.onUpdateHiddenApps(hiddenList);
            return res;
        } else {
            Slog.w(TAG, "[Space9dManagerService] get updater failed, null ptr");
        }
        return new int[0];
    }

    @Override
    public void sendTracking(String name, Bundle data) throws RemoteException {
        if (mInterceptor.onProcessTrack(name, data)) {
            return;
        }
        JSONObject message = new JSONObject();
        try {
            message.put(MessageHandler.OP_TYPE, name);
            message.put(MessageHandler.OP_RESULT_CODE, MessageHandler.OP_SUCCESS);
    
            if (data != null) {
                Object val;
                for (String key : data.keySet()) {
                    val = data.get(key);
                    if (val instanceof Float) {
                        message.put(key, (Float) val);
                    } else if (val instanceof Long) {
                        message.put(key, (Long) val);
                    } else if (val instanceof Integer) {
                        message.put(key, (Integer) val);
                    } else if (val instanceof Double) {
                        message.put(key, (Double) val);
                    } else if (val instanceof double[]) {
                        // 如果 val 是 double[]，将其转换为 JSONArray
                        JSONArray jsonArray = new JSONArray();
                        for (double d : (double[]) val) {
                            jsonArray.put(d);  // 将每个 double 值添加到 JSONArray
                        }
                        message.put(key, jsonArray);  // 将 JSONArray 添加到 message
                    } else {
                        message.put(key, (String) val);
                    }
                }
            }
    
            // 打印最终的消息
            Slog.v(TAG, "s9_sock---sendTracking: " + name + " (" + message.toString() + ")");
            
            // 发送数据
            mServerSocket.send(message.toString());
        } catch (JSONException e) {
            Slog.w(TAG, "sendTracking: " + name + " error", e);
        }
    }
    
    @Override
    public boolean sendToClient(String packageName, String data) throws RemoteException {
        Bundle bundle = new Bundle();
        bundle.putString("package", packageName);
        bundle.putString("data", data);
        sendTracking(NineDSpaceManager.STATUS_APP_MESSAGE, bundle);
        return true;
    }

    @Override
    public ConfigProperty getProperty(String name) throws RemoteException {
        IModule module = mModuleManager.getModule(BusinessModule.NAME);
        return (ConfigProperty) module.getProp(name);
    }

    @Override
    public String getPropValue(String name) throws RemoteException {
        IModule module = mModuleManager.getModule(SystemModule.NAME);
        return (String) module.getProp(name);
    }

    @Override
    public Map addMockCallback(String name, IMockCallback callback) throws RemoteException {
        return mMockManager.addMockCallback(name, callback);
    }

    @Override
    public Map getMockConfig(String name) throws RemoteException {
        return mMockManager.getMockInfo(name);
    }

    @Override
    public boolean updateModule(String moduleName, String key, String value) throws RemoteException {
        IModule module = null;
        if (BusinessModule.NAME.equals(moduleName)) {
            module = mModuleManager.getModule(BusinessModule.NAME);
        } else if (SystemModule.NAME.equals(moduleName)) {
            module = mModuleManager.getModule(SystemModule.NAME);
        }
        return module == null ? false : module.setProp(key, value);
    }

    @Override
    public String[] updateMock(Map addMap, List removeKeys) throws RemoteException {
        return mMockManager.updateMock((Map<String, Object>) addMap, (List<String>) removeKeys);
    }

    @Override
    public String dumpModule(String name) throws RemoteException {
        return mModuleManager.dumpModule(name);
    }

    @Override
    public String dumpMock(String opt) throws RemoteException {
        return mMockManager.dump(opt);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new Space9dManagerShellCommand(this, mContext).exec(this, in, out, err, args, callback, result);
    }
}
