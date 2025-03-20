package com.android.server.comm;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import android.util.Slog;
import org.json.JSONObject;
import org.json.JSONException;
import static com.android.server.Space9dManagerService.TAG;

public final class HttpHelper {

    public static class HttpResult {
        int httpCode;
        public String response;

        public boolean isOk() {
            return this.httpCode == HttpURLConnection.HTTP_OK;
        }
    }

    public static HttpResult doPost(String url, Map<String, String> data) {
        HttpResult result = new HttpResult();
        try {
            URL urlObject = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) urlObject.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setDoOutput(true);

            String postData = CommonHelper.mapToString(data);
            Slog.v(TAG, "start making request to url:" + url + ", post data:" + postData);

            // 获取输出流写入请求参数
            try (OutputStream os = connection.getOutputStream();
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"))) {
                writer.write(postData);
                writer.flush();
            }

            int responseCode = connection.getResponseCode();
            result.httpCode = responseCode;

            InputStream is = responseCode == HttpURLConnection.HTTP_OK ? connection.getInputStream()
                    : connection.getErrorStream();
            result.response = CommonHelper.stremToString(is);

            Slog.v(TAG, "get response form url:" + url + ", data:" + result.response);

        } catch (Exception e) {
            result.httpCode = -1;
            result.response = e.getMessage();
        }
        return result;
    }
}
