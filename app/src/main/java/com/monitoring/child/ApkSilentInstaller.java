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
                // Root চেক
                if (!isDeviceRooted()) {
                    showToast(context, "❌ Root পাওয়া যায়নি। PackageInstaller ব্যবহার করা হচ্ছে...");
                    installWithPackageInstaller(context, apkFile);
                    return;
                }

                String cmd = "pm install -r -d " + apkFile.getAbsolutePath();
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                int result = process.waitFor();

                if (result == 0) {
                    showToast(context, "✅ APK সাইলেন্টলি ইনস্টল হয়েছে (Root)");
                } else {
                    showToast(context, "Root Install Failed → PackageInstaller চেষ্টা চলছে");
                    installWithPackageInstaller(context, apkFile);
                }
            } catch (Exception e) {
                Log.e(TAG, "Root install error", e);
                showToast(context, "Root নেই → Normal Install চেষ্টা করা হচ্ছে");
                installWithPackageInstaller(context, apkFile);
            }
        }).start();
    }

    private static void installWithPackageInstaller(Context context, File apkFile) {
        Uri apkUri = Uri.fromFile(apkFile);
        if (context instanceof MainActivity) {
            ((MainActivity) context).installApkSilently(apkUri, null);
        } else {
            showToast(context, "Install failed: Invalid context");
        }
    }

    private static boolean isDeviceRooted() {
        try {
            return Runtime.getRuntime().exec("su -c id").waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void showToast(Context context, String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
    }
}