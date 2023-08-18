package com.eltonfaust.wakeupplugin;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import android.net.Uri;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.Manifest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;

public class WakeupPlugin extends CordovaPlugin {

    protected static final String LOG_TAG = "WakeupPlugin";

    private static final int ID_DAYLIST_OFFSET = 10010;
    private static final int ID_ONETIME_OFFSET = 10000;
    private static final int ID_PERMISSION_REQUEST_CODE = 684981;

    private static CallbackContext connectionCallbackContext = null;
    private static String pendingWakeupResult = null;

    private static CallbackContext notificatioPermCallback;

    public static Map<String, Integer> daysOfWeek = new HashMap<String, Integer>() {
        private static final long serialVersionUID = 1L;
        {
            put("sunday", 0);
            put("monday", 1);
            put("tuesday", 2);
            put("wednesday", 3);
            put("thursday", 4);
            put("friday", 5);
            put("saturday", 6);
        }
    };

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        // app startup
        log("Wakeup Plugin onReset");
        Bundle extras = cordova.getActivity().getIntent().getExtras();

        if (extras != null && !extras.getBoolean("wakeup", false)) {
            setAlarmsFromPrefs(cordova.getActivity().getApplicationContext());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        connectionCallbackContext = null;
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        try {
            log("Processing action " + action);

            if (action.equals("bind")) {
                connectionCallbackContext = callbackContext;

                if (pendingWakeupResult != null) {
                    sendWakeupResult(pendingWakeupResult);
                    cleaPendingWakeupResult();
                }

                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK);
                pluginResult.setKeepCallback(true);
                connectionCallbackContext.sendPluginResult(pluginResult);
            } else if (action.equals("configure")) {
                // save the new configs to preferences
                saveOptionsToPrefs(cordova.getActivity().getApplicationContext(), args.getJSONObject(0));
                callbackContext.success();
            } else if (action.equals("checkAutoStartPrefs")) {
                // check if the manufacturer allows AutoStart
                boolean hasAutoStartPreferences = WakeupAutoStartHelper.getInstance().canOpenPreferences(cordova.getContext());
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, hasAutoStartPreferences);
                callbackContext.sendPluginResult(pluginResult);
            } else if (action.equals("openAutoStartPrefs")) {
                // open manufacturer AutoStart preferences
                boolean openedPreferences = WakeupAutoStartHelper.getInstance().openAutoStartPreferences(cordova.getContext());
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, openedPreferences);
                callbackContext.sendPluginResult(pluginResult);
            } else if (action.equals("checkNotificationPerm")) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, hasNotificationPermission());
                callbackContext.sendPluginResult(pluginResult);
            } else if (action.equals("shouldRequestNotificationPermRat")) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, shouldRequestNotificationPermissionRationale());
                callbackContext.sendPluginResult(pluginResult);
            } else if (action.equals("requestNotificationPerm")) {
                requestNotificationPermission(callbackContext);
            } else if (action.equals("openAppNotificationSettings")) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, openAppNotificationSettings());
                callbackContext.sendPluginResult(pluginResult);
            } else if (action.equals("wakeup")) {
                cleaPendingWakeupResult();

                Context content = cordova.getActivity().getApplicationContext();
                JSONObject options = args.getJSONObject(0);
                JSONArray alarms;

                if (options.has("alarms")) {
                    alarms = options.getJSONArray("alarms");
                } else {
                    alarms = new JSONArray(); // default to empty array
                }

                saveAlarmsToPrefs(content, alarms);
                setAlarms(content, alarms, true);

                if (alarms.length() > 0) {

                }

                callbackContext.success();
            } else if (action.equals("stop")) {
                cleaPendingWakeupResult();
                cordova.getContext().stopService(new Intent(cordova.getActivity(), WakeupStartService.class));
            } else {
                callbackContext.error("Error: invalid action (" + action + ")");
                return false;
            }

            return true;
        } catch (JSONException e) {
            callbackContext.error("Error: invalid json");
        } catch (Exception e) {
            callbackContext.error("Error: " + e.getMessage());
        }

        return false;
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (
            notificatioPermCallback == null
            || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || requestCode != ID_PERMISSION_REQUEST_CODE
        ) {
            return;
        }

        if (permissions == null || permissions.length == 0) {
            return;
        }

        for (String permission : permissions) {
            if (permission.equals(Manifest.permission.POST_NOTIFICATIONS)) {
                log("Notification permission changed");
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, cordova.hasPermission(Manifest.permission.POST_NOTIFICATIONS));
                notificatioPermCallback.sendPluginResult(pluginResult);
                notificatioPermCallback = null;
                return;
            }
        }
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || cordova.hasPermission(Manifest.permission.POST_NOTIFICATIONS);
    }

    private boolean shouldRequestNotificationPermissionRationale() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !cordova.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            && ActivityCompat.shouldShowRequestPermissionRationale(
                cordova.getActivity(),
                Manifest.permission.POST_NOTIFICATIONS
            );
    }

    private void requestNotificationPermission(CallbackContext callbackContext) {
        if (hasNotificationPermission()) {
            // alreaty requested, send the previous fallback an "allowed" status
            if (notificatioPermCallback != null) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, true);
                notificatioPermCallback.sendPluginResult(pluginResult);
                notificatioPermCallback = null;
            }

            if (callbackContext != null) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, true);
                callbackContext.sendPluginResult(pluginResult);
            }
        } else {
            cordova.requestPermission(this, ID_PERMISSION_REQUEST_CODE, Manifest.permission.POST_NOTIFICATIONS);
            notificatioPermCallback = callbackContext;
            log("Post Notifications permission required");
        }
    }

    private boolean openAppNotificationSettings() {
        try {
            Intent intent = new Intent();
            Context context = cordova.getActivity().getApplicationContext();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", context.getPackageName());
                intent.putExtra("app_uid", context.getApplicationInfo().uid);
            } else {
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
            }

            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            cordova.getActivity().startActivity(intent);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void sendWakeupResult(String extras) {
        if (connectionCallbackContext != null) {
            cleaPendingWakeupResult();

            JSONObject o = new JSONObject();

            try {
                o.put("type", "wakeup");

                if (extras != null) {
                    o.put("extra", extras);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, o);
            pluginResult.setKeepCallback(true);
            connectionCallbackContext.sendPluginResult(pluginResult);
        } else {
            pendingWakeupResult = extras;
        }
    }

    public static void sendStopResult(String extras) {
        cleaPendingWakeupResult();

        if (connectionCallbackContext != null) {
            JSONObject o = new JSONObject();

            try {
                o.put("type", "stopped");

                if (extras != null) {
                    o.put("extra", extras);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, o);
            pluginResult.setKeepCallback(true);
            connectionCallbackContext.sendPluginResult(pluginResult);
        }
    }

    public static void cleaPendingWakeupResult() {
        pendingWakeupResult = null;
    }

    public static boolean isConnectionCallbackSet() {
        return connectionCallbackContext != null;
    }

    public static void setAlarmsFromPrefs(Context context) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String serializedAlarms = prefs.getString("alarms", "[]");

            log("Setting alarms:\n" + serializedAlarms);

            JSONArray alarms = new JSONArray(serializedAlarms);
            WakeupPlugin.setAlarms(context, alarms, true);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint({ "SimpleDateFormat", "NewApi" })
    private static void setAlarms(Context context, JSONArray alarms, boolean cancelAlarms) throws JSONException{
        if (cancelAlarms) {
            cancelAlarms(context);
        }

        for (int i = 0; i < alarms.length(); i++) {
            JSONObject alarm = alarms.getJSONObject(i);
            String type = "onetime";

            if (alarm.has("type")) {
                type = alarm.getString("type");
            }

            if (!alarm.has("time")) {
                throw new JSONException("alarm missing time: " + alarm.toString());
            }

            JSONObject time = alarm.getJSONObject("time");

            if (type.equals("onetime")) {
                Calendar alarmDate = getOneTimeAlarmDate(time);
                Intent intent = new Intent(context, WakeupReceiver.class);

                if (alarm.has("extra")) {
                    intent.putExtra("extra", alarm.getJSONObject("extra").toString());
                    intent.putExtra("type", type);
                }

                setNotification(context, type, alarmDate, intent, ID_ONETIME_OFFSET);
            } else if (type.equals("daylist")) {
                JSONArray days = alarm.getJSONArray("days");

                for (int j = 0; j < days.length(); j++) {
                    Calendar alarmDate = getAlarmDate(time, daysOfWeek.get(days.getString(j)));
                    Intent intent = new Intent(context, WakeupReceiver.class);

                    if (alarm.has("extra")) {
                        intent.putExtra("extra", alarm.getJSONObject("extra").toString());
                        intent.putExtra("type", type);
                        intent.putExtra("time", time.toString());
                        intent.putExtra("day", days.getString(j));
                    }

                    setNotification(context, type, alarmDate, intent, ID_DAYLIST_OFFSET + daysOfWeek.get(days.getString(j)));
                }
            }
        }

        // enable/disable boot receiver
        ComponentName receiver = new ComponentName(context, WakeupBootReceiver.class);
        PackageManager pm = context.getPackageManager();

        if (alarms.length() > 0) {
            log("Enabling WakeupBootReceiver");
            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } else {
            log("Disabling WakeupBootReceiver");
            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    private static void setNotification(Context context, String type, Calendar alarmDate, Intent intent, int id) throws JSONException{
        if (alarmDate != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            log("Setting alarm at " + sdf.format(alarmDate.getTime()) + "; id " + id);

            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent sender = PendingIntent.getBroadcast(
                context, id, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
            );
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmDate.getTimeInMillis(), sender);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(alarmDate.getTimeInMillis(), sender);
                alarmManager.setAlarmClock(alarmClockInfo, sender);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmDate.getTimeInMillis(), sender);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, alarmDate.getTimeInMillis(), sender);
            }

            if (connectionCallbackContext != null) {
                JSONObject o = new JSONObject();
                o.put("type", "set");
                o.put("alarm_type", type);
                o.put("alarm_date", alarmDate.getTimeInMillis());

                log("Alarm time in millis: " + alarmDate.getTimeInMillis());

                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, o);
                pluginResult.setKeepCallback(true);
                connectionCallbackContext.sendPluginResult(pluginResult);
            }
        }
    }

    private static void cancelAlarms(Context context) {
        log("Canceling alarms");
        Intent intent = new Intent(context, WakeupReceiver.class);
        PendingIntent sender = PendingIntent.getBroadcast(
            context, ID_ONETIME_OFFSET, intent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        log("Cancelling alarm id " + ID_ONETIME_OFFSET);
        alarmManager.cancel(sender);

        for (int i = 0; i < 7; i++) {
            intent = new Intent(context, WakeupReceiver.class);
            log("Cancelling alarm id " + (ID_DAYLIST_OFFSET+i));
            sender = PendingIntent.getBroadcast(
                context, ID_DAYLIST_OFFSET + i, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
            );
            alarmManager.cancel(sender);
        }
    }

    private static Calendar getOneTimeAlarmDate(JSONObject time) throws JSONException {
        TimeZone defaultz = TimeZone.getDefault();
        Calendar calendar = new GregorianCalendar(defaultz);
        Calendar now = new GregorianCalendar(defaultz);

        now.setTime(new Date());
        calendar.setTime(new Date());

        int hour = (time.has("hour")) ? time.getInt("hour") : -1;
        int minute = (time.has("minute")) ? time.getInt("minute") : 0;

        if (hour >= 0) {
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND,0);

            if (calendar.before(now)) {
                calendar.set(Calendar.DATE, calendar.get(Calendar.DATE) + 1);
            }
        } else {
            calendar = null;
        }

        return calendar;
    }

    private static Calendar getAlarmDate(JSONObject time, int dayOfWeek) throws JSONException {
        TimeZone defaultz = TimeZone.getDefault();
        Calendar calendar = new GregorianCalendar(defaultz);
        Calendar now = new GregorianCalendar(defaultz);
        now.setTime(new Date());
        calendar.setTime(new Date());

        int hour = (time.has("hour")) ? time.getInt("hour") : -1;
        int minute = (time.has("minute")) ? time.getInt("minute") : 0;

        if (hour >= 0) {
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND,0);

            int currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK); // 1-7 = Sunday-Saturday
            currentDayOfWeek--; // make zero-based

            // add number of days until 'dayOfWeek' occurs
            int daysUntilAlarm = 0;

            if (currentDayOfWeek > dayOfWeek) {
                // currentDayOfWeek=thursday (4); alarm=monday (1) -- add 4 days
                daysUntilAlarm = (6 - currentDayOfWeek) + dayOfWeek + 1; // (days until the end of week) + dayOfWeek + 1
            } else if (currentDayOfWeek < dayOfWeek) {
                // example: currentDayOfWeek=monday (1); dayOfWeek=thursday (4) -- add three days
                daysUntilAlarm = dayOfWeek - currentDayOfWeek;
            } else {
                if (now.after(calendar)) {
                    daysUntilAlarm = 7;
                } else {
                    daysUntilAlarm = 0;
                }
            }

            calendar.set(Calendar.DATE, now.get(Calendar.DATE) + daysUntilAlarm);
        } else {
            calendar = null;
        }

        return calendar;
    }

    private static Calendar getTimeFromNow(JSONObject time) throws JSONException {
        TimeZone defaultz = TimeZone.getDefault();
        Calendar calendar = new GregorianCalendar(defaultz);
        calendar.setTime(new Date());

        int seconds = (time.has("seconds")) ? time.getInt("seconds") : -1;

        if (seconds >= 0) {
            calendar.add(Calendar.SECOND, seconds);
        } else {
            calendar = null;
        }

        return calendar;
    }

    private static void saveAlarmsToPrefs(Context context, JSONArray alarms) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("alarms", alarms.toString());
        editor.apply();
    }

    private static void saveOptionsToPrefs(Context context, JSONObject options) throws JSONException {
        if (!options.has("streamingUrl") && !options.has("ringtone")) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();

        if (options.has("streamingUrl")) {
            editor.putString("alarms_streaming_url", options.getString("streamingUrl"));
        } else {
            editor.remove("alarms_streaming_url");
        }

        if (options.has("streamingOnlyWifi")) {
            editor.putBoolean("alarms_streaming_only_wifi", options.getBoolean("streamingOnlyWifi"));
        } else {
            editor.remove("alarms_streaming_only_wifi");
        }

        if (options.has("ringtone")) {
            editor.putString("alarms_ringtone", options.getString("ringtone"));
        } else {
            editor.remove("alarms_ringtone");
        }

        if (options.has("volume")) {
            editor.putInt("alarms_volume", options.getInt("volume"));
        } else {
            editor.remove("alarms_volume");
        }

        if (options.has("streamType")) {
            editor.putInt("alarms_stream_type", options.getInt("streamType"));
        } else {
            editor.remove("alarms_stream_type");
        }

        if (options.has("notificationText")) {
            editor.putString("alarms_notification_text", options.getString("notificationText"));
        } else {
            editor.remove("alarms_notification_text");
        }

        editor.apply();
    }

    private static void log(String log) {
        Log.d(LOG_TAG, log);
    }
}
