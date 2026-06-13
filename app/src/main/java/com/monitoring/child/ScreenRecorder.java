package com.monitoring.child;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.util.DisplayMetrics;
import android.util.Log;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;

public class ScreenRecorder {
    private static final String TAG = "ScreenRecorder";

    private final String serverIp;
    private final String deviceId;
    private final File cacheDir;
    private final OkHttpClient httpClient;

    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private File recordFile;
    private boolean isRecording = false;

    public ScreenRecorder(String serverIp, String deviceId, File cacheDir) {
        this.serverIp = serverIp;
        this.deviceId = deviceId;
        this.cacheDir = cacheDir;
        this.httpClient = new OkHttpClient();
    }

    public synchronized void start(MediaProjection mediaProjection, DisplayMetrics metrics) {
        if (isRecording) return;

        try {
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int dpi = metrics.densityDpi;

            // Scale down recording resolution to save bandwidth/processing
            if (width > 720) {
                float aspect = (float) height / width;
                width = 720;
                height = (int) (720 * aspect);
            }

            // Make width and height even (required by some encoders)
            width = (width / 2) * 2;
            height = (height / 2) * 2;

            recordFile = new File(cacheDir, "rec_" + System.currentTimeMillis() + ".mp4");
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoFrameRate(24);
            mediaRecorder.setVideoEncodingBitRate(1000 * 1024); // 1 Mbps
            mediaRecorder.setOutputFile(recordFile.getAbsolutePath());
            mediaRecorder.prepare();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenRecorder",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(),
                    null,
                    null
            );

            mediaRecorder.start();
            isRecording = true;
            Log.d(TAG, "Screen recording started, output: " + recordFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            release();
        }
    }

    public synchronized void stop() {
        if (!isRecording) return;

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }

            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }

            isRecording = false;
            Log.d(TAG, "Screen recording stopped. Uploading file...");
            uploadFile(recordFile);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
            release();
        }
    }

    private void uploadFile(final File file) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "Recording file does not exist");
            return;
        }

        String url = UrlHelper.getApiUrl(serverIp, "/api/recordings/upload");

        RequestBody fileBody = RequestBody.create(file, MediaType.parse("video/mp4"));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to upload recording", e);
                // Keep file for retry or delete
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Recording uploaded successfully: " + response.body().string());
                    // Delete temp file after successful upload
                    if (file.delete()) {
                        Log.d(TAG, "Local recording file deleted");
                    }
                } else {
                    Log.e(TAG, "Upload failed with status code: " + response.code());
                }
                response.close();
            }
        });
    }

    private void release() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing resources", e);
        }
        isRecording = false;
    }

    public boolean isRecording() {
        return isRecording;
    }
}
