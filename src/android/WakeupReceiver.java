package com.eltonfaust.wakeupplugin;

import java.text.SimpleDateFormat;
import java.util.Date;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

public class WakeupReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "WakeupReceiver";

    @SuppressLint({ "SimpleDateFormat", "NewApi" })
    @Override
    public void onReceive(Context context, Intent intent) {
        long now = new Date().getTime();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        log("Wakeuptimer expired at " + sdf.format(now));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        Bundle extrasBundle = intent.getExtras();
        String extras = null;

        if (extrasBundle != null && extrasBundle.get("extra") != null) {
            extras = extrasBundle.get("extra").toString();
        }

        // check if some ringtone is configured
        if (
            preferences.getString("alarms_streaming_url", null) != null
            || preferences.getString("alarms_ringtone", null) != null
        ) {
            log("Launching service for wakeup fallback");
            Intent serviceIntent = new Intent(context, WakeupStartService.class);

            if (extras != null) {
                serviceIntent.putExtra("extra", extras);
            }

            serviceIntent.putExtra("wakeup", true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } else {
            log("Can't lauch wakeup fallback service, not configured");
        }

        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        String className = launchIntent.getComponent().getClassName();
        log("Launching activity for class " + className);

        try {
            @SuppressWarnings("rawtypes")
            Class c = Class.forName(className);
            Intent activityIntent = new Intent(context, c);

            activityIntent.setPackage(context.getPackageName());
            activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activityIntent.putExtra("wakeup", intent.getBooleanExtra("wakeup", true));
            activityIntent.putExtra("triggerAt", now);

            if (extras != null) {
                activityIntent.putExtra("extra", extras);
            }

            context.startActivity(activityIntent);
        } catch (ClassNotFoundException e) {
            log("Can't initialize activity class, shuting down service");
        }

        WakeupPlugin.sendWakeupResult(extras);

        if (extrasBundle != null && extrasBundle.getString("type") != null && extrasBundle.getString("type").equals("daylist")) {
            // repeat in one week
            Date next = new Date(new Date().getTime() + (7 * 24 * 60 * 60 * 1000));
            log("Resetting alarm at " + sdf.format(next));

            Intent reschedule = new Intent(context, WakeupReceiver.class);

            if (extras != null) {
                reschedule.putExtra("extra", intent.getExtras().get("extra").toString());
            }

            reschedule.putExtra("day", WakeupPlugin.daysOfWeek.get(intent.getExtras().get("day")));

            PendingIntent sender = PendingIntent.getBroadcast(
                context, 19999 + WakeupPlugin.daysOfWeek.get(intent.getExtras().get("day")), intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
            );
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTime(), sender);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(next.getTime(), sender);
                alarmManager.setAlarmClock(alarmClockInfo, sender);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, next.getTime(), sender);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, next.getTime(), sender);
            }
        }
    }

    private void log(String log) {
        Log.d(LOG_TAG, log);
    }
}
