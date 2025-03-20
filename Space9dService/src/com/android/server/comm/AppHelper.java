package com.android.server.comm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import java.util.ArrayList;
import java.util.List;

public class AppHelper {

    public static List<String> getAllInstalledApps(Context context) {
        List<String> installedApps = new ArrayList<>();

        PackageManager packageManager = context.getPackageManager();
        List<PackageInfo> packages = packageManager.getInstalledPackages(0);

        for (PackageInfo packageInfo : packages) {
            ApplicationInfo appInfo = packageInfo.applicationInfo;
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                installedApps.add(appInfo.packageName);
            }
        }
        return installedApps;
    }

    public static List<Integer> getAllInternalApps(Context context) {
        List<Integer> internalApps = new ArrayList<>();

        PackageManager packageManager = context.getPackageManager();
        List<PackageInfo> packages = packageManager.getInstalledPackages(0);

        for (PackageInfo packageInfo : packages) {
            ApplicationInfo appInfo = packageInfo.applicationInfo;
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                internalApps.add(appInfo.uid);
            }
        }
        return internalApps;
    }

    public static String getAppName(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            ApplicationInfo ai = manager.getApplicationInfo(packageName, 0);
            return (String) manager.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    public static int getAppUid(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            ApplicationInfo ai = manager.getApplicationInfo(packageName, 0);
            return ai.uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    public static long getAppVersionCode(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo pi = manager.getPackageInfo(packageName, 0);
            return pi.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    public static String getAppVersionName(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo pi = manager.getPackageInfo(packageName, 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    public static String getAppVersionNameV2(Context context, String apkPath) {
        PackageManager manager = context.getPackageManager();
        PackageInfo pi = manager.getPackageArchiveInfo(apkPath, 0);
        return pi == null ? "" : pi.versionName;
    }

    public static long getAppVersionCodeV2(Context context, String apkPath) {
        PackageManager manager = context.getPackageManager();
        PackageInfo pi = manager.getPackageArchiveInfo(apkPath, 0);
        return pi != null ? pi.getLongVersionCode() : -1;
    }

    public static String getAppNameV2(Context context, String apkPath) {
        PackageManager manager = context.getPackageManager();
        PackageInfo pi = manager.getPackageArchiveInfo(apkPath, 0);
        if (pi != null) {
            return (String) manager.getApplicationLabel(pi.applicationInfo);
        } else {
            return "";
        }
    }

    public static Signature getAppSignatureV2(Context context, String apkPath) {
        PackageManager manager = context.getPackageManager();
        PackageInfo pi = manager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES);
        if (pi != null) {
            return pi.signatures[0];
        } else {
            return null;
        }
    }

    public static String[] decodeSignatures(byte[] signs) {
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) certFactory.generateCertificate(
                    new ByteArrayInputStream(signs));
            String issuser = cert.getIssuerDN().getName();
            String subject = cert.getSubjectDN().getName();

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] der = cert.getEncoded();
            md.update(der);
            byte[] digest = md.digest();

            char[] hexDigits = { '0', '1', '2', '3', '4', '5', '6', '7',
                    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
            StringBuffer buffer = new StringBuffer(digest.length * 2);
            for (int i = 0; i < digest.length; ++i) {
                buffer.append(hexDigits[(digest[i] & 0xf0) >> 4]);
                buffer.append(hexDigits[digest[i] & 0x0f]);
            }

            String thumbprint = buffer.toString();
            return new String[] { issuser, subject, thumbprint };
        } catch (Exception e) {
            return new String[] { "", "", "" };
        }
    }

    public static boolean isAppInstalled(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            return manager.getPackageInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isSystemApp(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            ApplicationInfo applicationInfo = manager.getApplicationInfo(packageName, 0);
            return (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

}
