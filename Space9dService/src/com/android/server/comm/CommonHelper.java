package com.android.server.comm;

import static com.android.server.Space9dManagerService.TAG;

import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.Map;

public final class CommonHelper {

    public static String stremToString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder stringBuilder = new StringBuilder();
        String line;

        try {
            while (true) {
                if (!((line = reader.readLine()) != null)) break;
                stringBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
        }
        return stringBuilder.toString();
    }

    public static String mapToString(Map<String, String> data) {
        StringBuilder stringBuilder = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (first) {
                first = false;
            } else {
                stringBuilder.append("&");
            }
            try {
                stringBuilder.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                stringBuilder.append("=");
                stringBuilder.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
            }
        }
        return stringBuilder.toString();
    }

    public static String getIPv4Address() {
        ArrayMap<String, String> addresses = new ArrayMap<>(2);
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                if (!networkInterface.getName().equals("wlan0")
                        && !networkInterface.getName().equals("eth0")) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        addresses.put(networkInterface.getName(), inetAddress.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "getIPv4Address", e);
        }
        return addresses.containsKey("wlan0") ? addresses.get("wlan0") :
                addresses.containsKey("eth0") ? addresses.get("eth0") : "";
    }

    public static boolean writeToFile(String path, String content) {
        try {
            FileOutputStream fos = new FileOutputStream(path, false);
            fos.write(content.getBytes());
            fos.close();
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "writeToFile: " + e.getMessage());
            return false;
        }
    }

    public static Map<String, Object> readPropFile(String filePath) {
        ArrayMap<String, Object> resultMap = new ArrayMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String[] kvs;
            String line;
            while ((line = br.readLine()) != null) {
                if (TextUtils.isEmpty(line.trim())) {
                    continue;
                }
                kvs = line.split("=");
                if (kvs.length == 2) {
                    resultMap.put(kvs[0], kvs[1]);
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "readPropFile: " + e.getMessage());
        }
        return resultMap;
    }

    public static void writeMapPropFile(String filePath, Map<String, Object> map) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filePath))) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                bw.write(entry.getKey() + "=" + entry.getValue().toString());
                bw.newLine();
            }
        } catch (IOException e) {
            Slog.w(TAG, "writeMapPropFile: " + e.getMessage());
        }
    }

    public static Map<String, Object> readMapFile(String filePath) {
        ArrayMap<String, Object> resultMap = new ArrayMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            String type;
            String key;
            String value;
            int index;
            while ((line = br.readLine()) != null) {
                if (TextUtils.isEmpty(line.trim())) {
                    continue;
                }
                index = line.indexOf(':');
                type = line.substring(0, index).toLowerCase();

                line = line.substring(index + 1);
                index = line.indexOf('=');
                if (index != -1) {
                    key = line.substring(0, index).trim();
                    value = line.substring(index + 1).trim();
                    if ("int".equals(type)) {
                        resultMap.put(key, Integer.valueOf(value));
                    } else if ("boolean".equals(type)) {
                        resultMap.put(key, Boolean.parseBoolean(value));
                    } else if ("float".equals(type)) {
                        resultMap.put(key, Float.valueOf(value));
                    } else if ("double".equals(type)) {
                        resultMap.put(key, Double.valueOf(value));
                    } else if ("long".equals(type)) {
                        resultMap.put(key, Long.valueOf(value));
                    } else {
                        resultMap.put(key, value);
                    }
                } else {
                    Slog.w(TAG, "parseFile: Invalid line: " + line);
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "readMapFile: " + e.getMessage());
        }
        return resultMap;
    }

    public static void writeMapToFile(String filePath, Map<String, Object> map) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filePath))) {
            Object val;
            String type;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                val = entry.getValue();
                if (val instanceof Integer) {
                    type = "int";
                } else if (val instanceof Float) {
                    type = "float";
                } else if (val instanceof Double) {
                    type = "double";
                } else if (val instanceof Long) {
                    type = "long";
                } else if (val instanceof Boolean) {
                    type = "boolean";
                } else {
                    type = "string";
                }
                String line = type + ":" + entry.getKey() + "=" + val;
                bw.write(line);
                bw.newLine();
            }
        } catch (IOException e) {
            Slog.w(TAG, "writeMapToFile: " + e.getMessage());
        }
    }
}
