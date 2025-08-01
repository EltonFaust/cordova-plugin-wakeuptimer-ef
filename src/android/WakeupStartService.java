package com.eltonfaust.wakeupplugin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.R;
import android.text.format.DateFormat;
import android.util.Log;

import android.os.HandlerThread;
import android.os.Handler;

import java.io.IOException;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import androidx.annotation.RequiresApi;

import android.media.AudioManager;
import android.media.AudioFocusRequest;
// import android.media.AudioAttributes;
import android.media.MediaPlayer;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

public class WakeupStartService extends Service {
    private static final String LOG_TAG = "WakeupStartService";

    // ID for the 'foreground' notification channel
    public static final String NOTIFICATION_CHANNEL_ID = "cordova-plugin-wakeuptimer";

    // ID for the 'foreground' notification
    public static final int NOTIFICATION_ID = 20220203;

    // Default text of the background notification
    private static final String NOTIFICATION_TEXT = "...";

    public enum RadioPlayerState {
        IDLE,
        PLAYING,
        STOPPED,
    }

    private static HandlerThread handlerThread = null;
    private static Handler requestHandler = null;

    // Notification manager
    private NotificationManager notificationManager;

    // Notification builder
    private Notification.Builder notificationBuilder;

    // AudioManager
    private AudioManager audioManager;

    // current intent extras
    private String extrasBundleContent;

    // current volume
    private int volume;

    // current stream type
    private int streamType;

    // AudioAttributes
    private android.media.AudioAttributes audioAttributes;

    // current streaming url
    private String streamingUrl;

    // streaming player instance
    private ExoPlayer radioPlayer;

    // player event listener
    private ExoPlayer.Listener playerEventListener;

    // current player state
    private RadioPlayerState radioPlayerState = RadioPlayerState.IDLE;

    // current ringtone url
    private String ringtoneUrl;

    // ringtone media player
    private MediaPlayer ringtoneSound;

    private boolean audioFocused = false;
    // AudioFocusRequest
    private AudioFocusRequest audioFocusRequest;

    // partial wake lock to prevent the app from going to sleep when locked
    private PowerManager.WakeLock wakeLock;

    // timer to auto stop service after a timeout
    private Timer autoStopTimer;

    // current fade in volume
    private float fadeInVolume;

    // timer to fade in volume from 0 to the volume set
    private Timer fadeInVolumeTimer;

    // receiver for destroy intent
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("wakeup-notificaion-destroy")) {
                WakeupStartService.this.stopSelf();
            }
        }
    };

    // detect changes on audi focus
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            if (
                WakeupStartService.this.radioPlayer == null
                && WakeupStartService.this.ringtoneSound == null
            ) {
                return;
            }

            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                float volume = WakeupStartService.this.volume * 0.2f * 0.01f;

                if (WakeupStartService.this.radioPlayer != null) {
                    WakeupStartService.this.radioPlayer.setVolume(volume);
                }

                if (WakeupStartService.this.ringtoneSound != null) {
                    WakeupStartService.this.ringtoneSound.setVolume(volume, volume);
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                float volume = WakeupStartService.this.volume * 0.01f;

                if (WakeupStartService.this.radioPlayer != null) {
                    WakeupStartService.this.radioPlayer.setVolume(volume);
                }

                if (WakeupStartService.this.ringtoneSound != null) {
                    WakeupStartService.this.ringtoneSound.setVolume(volume, volume);
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                WakeupStartService.this.stopSelf();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand received");

        Context context = this.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        Bundle extrasBundle = intent.getExtras();

        if (extrasBundle != null && extrasBundle.get("extra") != null) {
            this.extrasBundleContent = extrasBundle.get("extra").toString();
        }

        boolean streamingOnlyWifi = prefs.getBoolean("alarms_streaming_only_wifi", false);
        this.streamingUrl = prefs.getString("alarms_streaming_url", null);
        this.ringtoneUrl = prefs.getString("alarms_ringtone", null);
        this.volume = prefs.getInt("alarms_volume", 100);
        this.streamType = prefs.getInt("alarms_stream_type", AudioManager.STREAM_ALARM);
        String notificationText = prefs.getString("alarms_notification_text", "%time%");

        // on android 15 (SDK 35), focus requests was restricted, for a top app and should be for running foreground service,
        // but apparently there is a bug that on foreground services allways result in AUDIOFOCUS_REQUEST_FAILED
        // in case of failure on android 15+ it will not auto stop the service
        // https://developer.android.com/about/versions/15/behavior-changes-15#audio-focus
        // https://issuetracker.google.com/issues/375228130?pli=1
        if (
            !this.requestAudioFocus()
            && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
        ) {
            this.stopSelf();
            return START_NOT_STICKY;
        }

        boolean started = false;

        if (streamingUrl != null) {
            if (!streamingOnlyWifi || this.isConnectedOnWifi()) {
                started = this.startRadioPlayer();
            } else {
                log("Can't start radio, not connect to internet or required a wifi/ethernet connection");
            }
        }

        if (!started && ringtoneUrl != null) {
            started = this.startRingtone();
        }

        if (!started) {
            log("Can't start service, no options left!");
            this.stopSelf();
            return START_NOT_STICKY;
        }

        // update the notification content
        CharSequence format = null;

        if (DateFormat.is24HourFormat(context)) {
            format = "h:mm a";
        } else {
            format = "HH:mm";
        }

        // open app with notification click
        try {
            String packageName = context.getPackageName();
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            String className = launchIntent.getComponent().getClassName();
            @SuppressWarnings("rawtypes")
            Class mainActivityClass = Class.forName(className);
            Intent notifyIntent = new Intent(context, mainActivityClass);

            notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            notifyIntent.putExtra("wakeup", intent.getBooleanExtra("wakeup", true));

            if (this.extrasBundleContent != null) {
                notifyIntent.putExtra("extra", this.extrasBundleContent);
            }

            PendingIntent notifyPendingIntent = PendingIntent.getActivity(
                context, 0, notifyIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
            );
            this.notificationBuilder.setContentIntent(notifyPendingIntent);
        } catch (ClassNotFoundException e) {
            log("Can't initialize activity class");
        }

        this.notificationBuilder.setContentText(notificationText.replace("%time%", DateFormat.format(format, new Date())));
        this.notificationManager.notify(NOTIFICATION_ID, this.notificationBuilder.build());

        if (this.autoStopTimer != null) {
            this.autoStopTimer.cancel();
            this.autoStopTimer = null;
        }

        this.autoStopTimer = new Timer();
        this.autoStopTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // this code will be executed after 5 minutes
                log("Timed out, auto shuting down service");
                WakeupStartService.this.stopSelf();
            }
        }, 5 * 60 * 1000 * 1L);

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log("onCreate received");

        PowerManager powerMgr = (PowerManager) this.getSystemService(POWER_SERVICE);

        this.wakeLock = powerMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WakeupStartService.class.getName());
        this.wakeLock.acquire();

        this.notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        this.audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);

        Notification serviceNotification = this.createNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // start the service
            this.startForeground(NOTIFICATION_ID, serviceNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            // register a receiver for the destroy intent
            this.getApplicationContext().registerReceiver(this.broadcastReceiver, new IntentFilter("wakeup-notificaion-destroy"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.startForeground(NOTIFICATION_ID, serviceNotification);
            this.getApplicationContext().registerReceiver(this.broadcastReceiver, new IntentFilter("wakeup-notificaion-destroy"));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("onDestroy received");

        // already dismissed, no need trigger the wakeup event on initialize the app
        WakeupPlugin.cleaPendingWakeupResult();

        this.abandonAudioFocus();
        this.releaseRadioPlayer();

        if (this.ringtoneSound != null) {
            this.ringtoneSound.stop();
            this.ringtoneSound.release();
            this.ringtoneSound = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }

        this.notificationManager.cancel(NOTIFICATION_ID);
        this.stopSelf();

        if (this.wakeLock != null) {
            this.wakeLock.release();
            this.wakeLock = null;
        }

        if (this.autoStopTimer != null) {
            this.autoStopTimer.cancel();
            this.autoStopTimer = null;
        }

        WakeupPlugin.sendStopResult(this.extrasBundleContent);
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Alarm";
            String description = "Wake up alarm notification";
            int importance = NotificationManager.IMPORTANCE_HIGH;

            NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);

            notificationChannel.setDescription(description);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationChannel.setShowBadge(false);
            notificationChannel.setSound(null, null);
            notificationManager.createNotificationChannel(notificationChannel);
        }

        Context context = this.getApplicationContext();

        this.notificationBuilder = new Notification.Builder(context)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(0)
            .setContentTitle(this.getAppName())
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(context.getResources().getIdentifier("ic_launcher", "mipmap", context.getPackageName()))
            .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.notificationBuilder.setChannelId(NOTIFICATION_CHANNEL_ID);
        }

        int[] args = { 0 };
        this.notificationBuilder.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(args));

        // intent responsible for stop service
        Intent dismissIntent = new Intent("wakeup-notificaion-destroy");
        dismissIntent.setPackage(context.getPackageName());
        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(context, 1, dismissIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);

        // add action on dismiss notification
        this.notificationBuilder.setDeleteIntent(dismissPendingIntent);

        // add an close button on notification
        Notification.Action.Builder actionDismissBuilder = new Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "", dismissPendingIntent
        );
        this.notificationBuilder.addAction(actionDismissBuilder.build());

        return this.notificationBuilder.build();
    }

    private boolean startRadioPlayer() {
        if (this.radioPlayerState != RadioPlayerState.IDLE) {
            return this.radioPlayerState == RadioPlayerState.PLAYING;
        }

        log("Starting radio player");

        this.playerEventListener = playerEventListener = new ExoPlayer.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                WakeupStartService.this.releaseRadioPlayer();
                WakeupStartService.this.startRingtoneOrStop();
                WakeupStartService.this.log("ERROR OCCURED.");
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == ExoPlayer.STATE_IDLE && WakeupStartService.this.radioPlayerState == RadioPlayerState.PLAYING) {
                    // Player.STATE_IDLE: This is the initial state, the state when the player is stopped, and when playback failed.
                    WakeupStartService.this.log("Player state changed. Stopped");
                    WakeupStartService.this.releaseRadioPlayer();
                    WakeupStartService.this.startRingtoneOrStop();
                } else {
                    WakeupStartService.this.log("Player state changed. ExoPlayer State: " + playbackState + ", Current state: " + WakeupStartService.this.radioPlayerState);
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (
                    playWhenReady
                    && reason == ExoPlayer.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                    && WakeupStartService.this.radioPlayerState != RadioPlayerState.PLAYING
                ) {
                    // The player is only playing if the state is Player.STATE_READY and playWhenReady=true
                    WakeupStartService.this.log("Player state changed. Playing");
                    WakeupStartService.this.radioPlayerState = RadioPlayerState.PLAYING;
                    // fade in volume
                    WakeupStartService.this.startFadeInVolume(true);
                }
            }
        };

        getRequestHandler().post(new Runnable() {
            public void run() {
                int audioUsageType = WakeupStartService.this.streamType == AudioManager.STREAM_ALARM
                    ? C.USAGE_ALARM
                    : C.USAGE_MEDIA;

                DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(
                    WakeupStartService.this.getApplicationContext(),
                    new DefaultHttpDataSource.Factory()
                        .setUserAgent("CordovaWakeupPlugin")
                );

                ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();

                WakeupStartService.this.radioPlayer = new ExoPlayer.Builder(WakeupStartService.this.getApplicationContext())
                    .setLooper(getRequestHandler().getLooper())
                    .setMediaSourceFactory(
                        new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
                    )
                    .setAudioAttributes(
                        new AudioAttributes.Builder()
                            .setUsage(audioUsageType)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        false
                    )
                    .build();

                // Per MediaItem settings.
                MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(Uri.parse(WakeupStartService.this.streamingUrl))
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder()
                            .build()
                    )
                    .build();

                WakeupStartService.this.radioPlayer.setMediaItem(mediaItem);
                WakeupStartService.this.radioPlayer.addListener(WakeupStartService.this.playerEventListener);
                WakeupStartService.this.radioPlayer.prepare();
                // if the volume is 20% or higher it will fade in from 0 to the set valume
                WakeupStartService.this.radioPlayer.setVolume(WakeupStartService.this.volume >= 20 ? 0 : WakeupStartService.this.volume * 0.01f);
                WakeupStartService.this.radioPlayer.setPlayWhenReady(true);
            }
        });

        return true;
    }

    private void releaseRadioPlayer() {
        if (this.radioPlayer == null) {
            return;
        }

        this.radioPlayerState = RadioPlayerState.STOPPED;

        getRequestHandler().post(new Runnable() {
            public void run() {
                if (WakeupStartService.this.radioPlayer != null) {
                    WakeupStartService.this.radioPlayer.release();
                    WakeupStartService.this.radioPlayer = null;
                }
            }
        });
    }

    private boolean startRingtone() {
        if (this.ringtoneSound != null) {
            return true;
        }

        log("Starting ringtone");

        this.ringtoneSound = new MediaPlayer();
        this.ringtoneSound.setLooping(true);

        if (this.volume >= 20) {
            this.ringtoneSound.setVolume(0, 0);
        } else {
            this.ringtoneSound.setVolume(this.volume * 0.01f, this.volume * 0.01f);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.ringtoneSound.setAudioAttributes(this.buidAudioAttributes());
        } else {
            this.ringtoneSound.setAudioStreamType(this.streamType);
        }

        try {
            this.ringtoneSound.setDataSource(this.getApplicationContext(), Uri.parse(this.ringtoneUrl));
            this.ringtoneSound.prepare();
            this.ringtoneSound.start();
            // fade in volume
            this.startFadeInVolume(false);
            return true;
        } catch (IOException exeption) {
            log("Can't play the ringtone!");
            this.ringtoneSound = null;
            return false;
        }
    }

    private void startRingtoneOrStop() {
        if (ringtoneUrl == null || !this.startRingtone()) {
            this.stopSelf();
        }
    }

    private android.media.AudioAttributes buidAudioAttributes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }

        int audioUsageType = this.streamType == AudioManager.STREAM_ALARM
            ? C.USAGE_ALARM
            : C.USAGE_MEDIA;

        this.audioAttributes = new android.media.AudioAttributes.Builder()
            .setUsage(audioUsageType)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();

        return this.audioAttributes;
    }

    private boolean requestAudioFocus() {
        int result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;

        this.abandonAudioFocus();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(this.buidAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this.audioFocusChangeListener)
                .build();

            result = this.audioManager.requestAudioFocus(this.audioFocusRequest);
        } else {
            result = this.audioManager.requestAudioFocus(this.audioFocusChangeListener, this.streamType, AudioManager.AUDIOFOCUS_GAIN);
        }

        this.audioFocused = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

        log(this.audioFocused ? "Audio focus granted" : "Audio focus NOT granted");

        return this.audioFocused;
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (this.audioFocusRequest != null) {
                log("Audio focus abandoned");
                this.audioManager.abandonAudioFocusRequest(this.audioFocusRequest);
            }
        } else if (this.audioFocusChangeListener != null) {
            log("Audio focus abandoned");
            this.audioManager.abandonAudioFocus(this.audioFocusChangeListener);
        }

        this.audioFocused = false;
    }

    // private void retryRequestAudioFocus(String retryPoint) {
    //     if (this.audioFocused) {
    //         return;
    //     }

    //     log("Retrying to request focus at ".concat(retryPoint));
    //     this.requestAudioFocus();
    // }

    private boolean isConnectedOnWifi() {
        ConnectivityManager cm = (ConnectivityManager) this.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) {
            return false;
        }

        /* NetworkInfo is deprecated in API 29 so we have to check separately for higher API Levels */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Network network = cm.getActiveNetwork();

            if (network == null) {
                return false;
            }

            NetworkCapabilities networkCapabilities = cm.getNetworkCapabilities(network);

            if (networkCapabilities == null) {
                return false;
            }

            return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                && (
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                );
        } else {
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();

            return networkInfo != null
                && networkInfo.isConnected()
                && (
                    networkInfo.getType() == ConnectivityManager.TYPE_WIFI
                    || networkInfo.getType() == ConnectivityManager.TYPE_ETHERNET
                );
        }
    }

    private void cancelFadeInTimer() {
        if (this.fadeInVolumeTimer == null) {
            return;
        }

        this.fadeInVolumeTimer.cancel();
        this.fadeInVolumeTimer.purge();
        this.fadeInVolumeTimer = null;
    }

    private void startFadeInVolume(boolean forPlayer) {
        this.cancelFadeInTimer();

        // started to play, fade in the volume
        if (this.volume < 20) {
            return;
        }

        // initial volume
        this.fadeInVolume = 0;
        // the duration of the fade
        final int FADE_DURATION = 10000;
        // the amount of time between volume changes. The smaller this is, the smoother the fade
        final int FADE_INTERVAL = this.volume >= 50 ? 250 : 350;
        // calculate the number of fade steps
        int numberOfSteps = FADE_DURATION / FADE_INTERVAL;
        // calculate by how much the volume changes each step
        final float deltaVolume = (this.volume * 0.01f) / (float) numberOfSteps;

        // create a new Timer and Timer task to run the fading outside the main UI thread
        this.fadeInVolumeTimer = new Timer(true);

        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                // check if no longer available
                if (
                    (forPlayer && WakeupStartService.this.radioPlayer == null)
                    || (!forPlayer && WakeupStartService.this.ringtoneSound == null)
                ) {
                    WakeupStartService.this.cancelFadeInTimer();
                    return;
                }

                WakeupStartService.this.fadeInVolume += deltaVolume;

                if (forPlayer) {
                    getRequestHandler().post(new Runnable() {
                        public void run() {
                            WakeupStartService.this.radioPlayer.setVolume(WakeupStartService.this.fadeInVolume);
                        }
                    });
                } else {
                    WakeupStartService.this.ringtoneSound.setVolume(
                        WakeupStartService.this.fadeInVolume,
                        WakeupStartService.this.fadeInVolume
                    );
                }

                // cancel and purge the Timer if the desired volume has been reached
                if (WakeupStartService.this.fadeInVolume >= 1) {
                    WakeupStartService.this.cancelFadeInTimer();
                }
            }
        };

        this.fadeInVolumeTimer.schedule(timerTask, FADE_INTERVAL, FADE_INTERVAL);
    }

    private String getAppName() {
        return this.getApplicationContext().getApplicationInfo().loadLabel(this.getApplicationContext().getPackageManager()).toString();
    }

    private void log(String log) {
        Log.d(LOG_TAG, log);
    }

    public static Handler getRequestHandler() {
        if (handlerThread == null) {
            handlerThread = new HandlerThread("WakeupTimerOperation");
            handlerThread.start();
            requestHandler = new Handler(handlerThread.getLooper());
        }

        return requestHandler;
    }
}
