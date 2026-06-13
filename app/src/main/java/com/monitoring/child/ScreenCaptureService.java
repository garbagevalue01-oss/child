package com.monitoring.child;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

public class ScreenCaptureService extends Service {
    private static final String TAG        = "ScreenCaptureService";
    private static final String CHANNEL_ID = "ChildMonitoringChannel";
    private static final int    NOTIFICATION_ID = 456;

    public static final String ACTION_TOGGLE_CAMERA = "com.monitoring.child.ACTION_TOGGLE_CAMERA";
    public static final String EXTRA_CAMERA_ENABLED  = "camera_enabled";

    private static final long FRAME_INTERVAL_MS = 100; // ~10 FPS

    public static boolean isRunning = false;

    // ── Core fields ────────────────────────────────────────────────────────────
    private MediaProjectionManager projectionManager;
    private MediaProjection         mediaProjection;

    // Screen streaming
    private VirtualDisplay streamVirtualDisplay;
    private ImageReader    imageReader;
    private boolean        isScreenStreaming = false;

    // Camera streaming
    private CameraDevice          cameraDevice;
    private CameraCaptureSession  captureSession;
    private ImageReader           cameraImageReader;
    private boolean               isCameraStreaming = false;

    // Shared background thread (used by whichever mode is active)
    private HandlerThread handlerThread;
    private Handler       backgroundHandler;
    private Handler       mainHandler;

    // WebSocket
    private OkHttpClient httpClient;
    private WebSocket    webSocket;
    private boolean      isWsConnected = false;

    // Service params
    private String  deviceId;
    private String  deviceName;
    private String  serverIp;
    private int     resultCode;
    private Intent  resultData;
    private boolean cameraEnabled = false;

    // Recording
    private ScreenRecorder screenRecorder;

    // Frame-rate throttle
    private long    lastFrameTime  = 0;
    private boolean isSendingFrame = false;

    // Uninstall polling
    private final Handler  uninstallStatusHandler  = new Handler(Looper.getMainLooper());
    private       Runnable uninstallStatusRunnable;

    // ── BroadcastReceiver for camera toggle from MainActivity ─────────────────
    private final BroadcastReceiver cameraToggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_TOGGLE_CAMERA.equals(intent.getAction())) {
                boolean enabled = intent.getBooleanExtra(EXTRA_CAMERA_ENABLED, false);
                mainHandler.post(() -> setCameraStreamEnabled(enabled));
            }
        }
    };

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler       = new Handler(Looper.getMainLooper());
        httpClient        = new OkHttpClient();
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraToggleReceiver,
                    new IntentFilter(ACTION_TOGGLE_CAMERA),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cameraToggleReceiver, new IntentFilter(ACTION_TOGGLE_CAMERA));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            );
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        resultCode = intent.getIntExtra("result_code", -1);
        resultData = intent.getParcelableExtra("data");
        deviceId   = intent.getStringExtra("device_id");
        deviceName = intent.getStringExtra("device_name");
        serverIp   = intent.getStringExtra("server_ip");
        cameraEnabled = intent.getBooleanExtra("camera_enabled", false);

        if (resultCode == -1 ||
                resultData == null ||
                deviceId == null ||
                serverIp == null) {

            stopSelf();
            return START_NOT_STICKY;
        }

        isRunning = true;

        if (mediaProjection == null) {
            mediaProjection =
                    projectionManager.getMediaProjection(resultCode, resultData);
        }

        screenRecorder = new ScreenRecorder(serverIp, deviceId, getCacheDir());

        connectWebSocket();
        startUninstallStatusPolling();

        return START_STICKY;
    }

    // ── WebSocket ──────────────────────────────────────────────────────────────

    private void connectWebSocket() {
        if (webSocket != null) {
            webSocket.close(1000, "Reconnecting");
            webSocket = null;
        }

        String wsUrl = UrlHelper.getWsUrl(serverIp, "/ws/child?device_id=" + deviceId);
        Request request = new Request.Builder().url(wsUrl).build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                isWsConnected = true;
                Log.d(TAG, "WebSocket connected");
                mainHandler.post(() -> {
                    // Start in the correct mode based on cameraEnabled flag
                    if (cameraEnabled) {
                        startCameraCapture();
                    } else {
                        startScreenStreaming();
                    }
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                Log.d(TAG, "WS command: " + text);
                try {
                    JSONObject json   = new JSONObject(text);
                    String     action = json.optString("action");
                    switch (action.toLowerCase()) {
                        case "start_record":
                            startScreenRecording();
                            break;
                        case "stop_record":
                            stopScreenRecording();
                            break;
                        case "toggle_camera":
                            boolean enable = json.optBoolean("enabled", false);
                            mainHandler.post(() -> setCameraStreamEnabled(enable));
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse WS command", e);
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                isWsConnected = false;
                Log.d(TAG, "WebSocket closed: " + reason);
                mainHandler.post(() -> {
                    stopCameraCapture();
                    stopScreenStreaming();
                });
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                isWsConnected = false;
                Log.e(TAG, "WebSocket failure, retrying in 5s", t);
                mainHandler.post(() -> {
                    stopCameraCapture();
                    stopScreenStreaming();
                    mainHandler.postDelayed(() -> connectWebSocket(), 5000);
                });
            }
        });
    }

    // ── Camera / Screen toggle ─────────────────────────────────────────────────

    /**
     * Switch between camera and screen streaming.
     * Properly tears down the current mode before starting the other.
     */
    private void setCameraStreamEnabled(boolean enabled) {
        cameraEnabled = enabled;
        if (!isWsConnected) return;

        if (enabled) {
            // Switch to camera
            stopScreenStreaming();
            // Give stopScreenStreaming a moment to release the handler thread,
            // then start camera on a fresh thread.
            mainHandler.postDelayed(this::startCameraCapture, 200);
        } else {
            // Switch to screen
            stopCameraCapture();
            mainHandler.postDelayed(this::startScreenStreaming, 200);
        }
    }

    // ── Screen Streaming ───────────────────────────────────────────────────────

    private void startScreenStreaming() {
        if (isScreenStreaming) return;     // already running
        if (isCameraStreaming) return;     // camera is active — wait for toggle
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            stopSelf();
            return;
        }

        ensureBackgroundThread();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth  = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int density      = metrics.densityDpi;

        final int targetWidth  = ((screenWidth  / 2) / 2) * 2;
        final int targetHeight = ((screenHeight / 2) / 2) * 2;

        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2);
        streamVirtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenStream",
                targetWidth, targetHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
        );

        imageReader.setOnImageAvailableListener(reader -> {
            if (!isWsConnected || isSendingFrame) {
                Image img = reader.acquireLatestImage();
                if (img != null) img.close();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                Image img = reader.acquireLatestImage();
                if (img != null) img.close();
                return;
            }
            lastFrameTime = now;

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                isSendingFrame = true;
                final Image finalImage = image;

                backgroundHandler.post(() -> {
                    try {
                        Image.Plane[] planes     = finalImage.getPlanes();
                        ByteBuffer    buffer     = planes[0].getBuffer();
                        int           pixelStride = planes[0].getPixelStride();
                        int           rowStride   = planes[0].getRowStride();
                        int           rowPadding  = rowStride - pixelStride * targetWidth;

                        Bitmap bmp = Bitmap.createBitmap(
                                targetWidth + rowPadding / pixelStride, targetHeight,
                                Bitmap.Config.ARGB_8888);
                        bmp.copyPixelsFromBuffer(buffer);

                        Bitmap cleanBmp;
                        if (rowPadding > 0) {
                            cleanBmp = Bitmap.createBitmap(bmp, 0, 0, targetWidth, targetHeight);
                            bmp.recycle();
                        } else {
                            cleanBmp = bmp;
                        }

                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        cleanBmp.compress(Bitmap.CompressFormat.JPEG, 50, bos);
                        cleanBmp.recycle();

                        if (isWsConnected && webSocket != null) {
                            webSocket.send(ByteString.of(bos.toByteArray()));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing screen frame", e);
                    } finally {
                        finalImage.close();
                        isSendingFrame = false;
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error acquiring screen image", e);
                if (image != null) image.close();
                isSendingFrame = false;
            }
        }, backgroundHandler);

        isScreenStreaming = true;
        Log.d(TAG, "Screen streaming started at " + targetWidth + "x" + targetHeight);
    }

    private void stopScreenStreaming() {
        isScreenStreaming = false;
        if (streamVirtualDisplay != null) {
            streamVirtualDisplay.release();
            streamVirtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        releaseBackgroundThread();
        Log.d(TAG, "Screen streaming stopped");
    }

    // ── Camera Streaming ───────────────────────────────────────────────────────

    private void startCameraCapture() {
        if (isCameraStreaming) return;

        ensureBackgroundThread();

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // Prefer front camera, fall back to any available
            String frontCameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id;
                    break;
                }
            }
            if (frontCameraId == null && manager.getCameraIdList().length > 0) {
                frontCameraId = manager.getCameraIdList()[0];
            }
            if (frontCameraId == null) {
                Log.e(TAG, "No camera found");
                return;
            }

            final int camWidth  = 480;
            final int camHeight = 640;
            cameraImageReader = ImageReader.newInstance(camWidth, camHeight, ImageFormat.JPEG, 2);
            cameraImageReader.setOnImageAvailableListener(reader -> {
                if (!isWsConnected || isSendingFrame) {
                    Image img = reader.acquireLatestImage();
                    if (img != null) img.close();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                    Image img = reader.acquireLatestImage();
                    if (img != null) img.close();
                    return;
                }
                lastFrameTime = now;

                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    isSendingFrame = true;
                    final Image finalImage = image;

                    backgroundHandler.post(() -> {
                        try {
                            ByteBuffer buffer = finalImage.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            if (isWsConnected && webSocket != null) {
                                webSocket.send(ByteString.of(bytes));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing camera frame", e);
                        } finally {
                            finalImage.close();
                            isSendingFrame = false;
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error acquiring camera image", e);
                    if (image != null) image.close();
                    isSendingFrame = false;
                }
            }, backgroundHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted");
                return;
            }

            final String finalCameraId = frontCameraId;
            manager.openCamera(finalCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice     = camera;
                    isCameraStreaming = true;
                    try {
                        camera.createCaptureSession(
                                Collections.singletonList(cameraImageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            CaptureRequest.Builder builder =
                                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            builder.addTarget(cameraImageReader.getSurface());
                                            session.setRepeatingRequest(
                                                    builder.build(), null, backgroundHandler);
                                            Log.d(TAG, "Camera streaming started");
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to set repeating request", e);
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                        Log.e(TAG, "Camera session configure failed");
                                        isCameraStreaming = false;
                                    }
                                }, backgroundHandler);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to create capture session", e);
                        isCameraStreaming = false;
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice      = null;
                    isCameraStreaming  = false;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera device error: " + error);
                    camera.close();
                    cameraDevice      = null;
                    isCameraStreaming  = false;
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
            isCameraStreaming = false;
        }
    }

    private void stopCameraCapture() {
        isCameraStreaming = false;
        if (captureSession != null) {
            try { captureSession.stopRepeating(); } catch (Exception ignored) {}
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraImageReader != null) {
            cameraImageReader.close();
            cameraImageReader = null;
        }
        releaseBackgroundThread();
        Log.d(TAG, "Camera capture stopped");
    }

    // ── Background thread helpers ──────────────────────────────────────────────

    private void ensureBackgroundThread() {
        if (handlerThread == null || !handlerThread.isAlive()) {
            handlerThread = new HandlerThread("ScreenCaptureThread");
            handlerThread.start();
            backgroundHandler = new Handler(handlerThread.getLooper());
        }
    }

    private void releaseBackgroundThread() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread     = null;
            backgroundHandler = null;
        }
    }

    // ── Screen Recording ───────────────────────────────────────────────────────

    private void startScreenRecording() {
        mainHandler.post(() -> {
            if (mediaProjection != null && screenRecorder != null) {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                screenRecorder.start(mediaProjection, metrics);
            }
        });
    }

    private void stopScreenRecording() {
        mainHandler.post(() -> {
            if (screenRecorder != null) screenRecorder.stop();
        });
    }

    // ── Uninstall polling ──────────────────────────────────────────────────────

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

    private void checkUninstallStatus() {
        String url = UrlHelper.getApiUrl(serverIp,
                "/api/devices/uninstall-status?device_id=" + deviceId);
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(@NonNull okhttp3.Call call,
                                            @NonNull java.io.IOException e) {
                Log.e(TAG, "Uninstall status check failed", e);
            }

            @Override public void onResponse(@NonNull okhttp3.Call call,
                                             @NonNull okhttp3.Response response) throws java.io.IOException {
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

    // ── Notifications ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Child Monitoring", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active screen monitoring");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Child Monitoring Active")
                .setContentText("This device is being monitored.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // ── Destroy ────────────────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        isRunning = false;
        unregisterReceiver(cameraToggleReceiver);
        stopUninstallStatusPolling();
        stopScreenRecording();
        stopCameraCapture();
        stopScreenStreaming();

        if (webSocket != null) {
            webSocket.close(1000, "Service stopped");
            webSocket = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}