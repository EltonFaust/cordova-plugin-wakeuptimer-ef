# Wakeup/Alarm Clock PhoneGap/Cordova Plugin

### Platform Support

This plugin supports PhoneGap/Cordova apps running on both iOS and Android.

### Version Requirements

This plugin is meant to work with Cordova 3.5.0+.

## Installation

#### Automatic Installation using PhoneGap/Cordova CLI (iOS and Android)
1. Make sure you update your projects to Cordova iOS version 3.5.0+ before installing this plugin.
```sh
cordova platform update ios
cordova platform update android
```

2. Install this plugin using PhoneGap/Cordova cli:
```sh
cordova plugin add https://github.com/EltonFaust/cordova-plugin-wakeuptimer-ef.git
```

## Usage
```js
// listen to any event received from the native part
window.wakeuptimer.bind(
    function (result) {
        if (result.type == 'set') {
            console.log('wakeup alarm set: ', result);
        } else if (result.type == 'wakeup') {
            // this event is received once the alarm is trggered
            console.log('wakeup alarm detected: ', result);
        } else if (result.type == 'stopped') {
            // Android Only
            // this event is received once the alarm is stopped playing the ringtone/streaming
            console.log('alarm stopped: ', result);
        } else {
            console.log('wakeup unhandled: ', result);
        }
    },
    function (error) {}
);

// set wakeup timer
window.wakeuptimer.wakeup(
    successCallback, errorCallback,
    // a list of alarms to set
    {
        alarms: [
            {
                type: 'onetime',
                time: { hour: 14, minute: 30 },
                extra: { message: 'json containing app-specific information to be posted when alarm triggers' },
            },
            {
                type: 'daylist',
                time: { hour: 14, minute: 30 },
                // list of week days ('sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday')
                days: [ 'monday', 'wednesday', 'friday' ],
                extra: { message: 'json containing app-specific information to be posted when alarm triggers' },
            },
        ]
    }
);

// ******************************************************************/
// *** All methods below are Android Only and its use is OPTIONAL ***/
// ******************************************************************/

/*
Since Android 13 (SDK 33), android requires an user manual permission to show notifications,
as this pligin uses a notification to allow the alarm stop without entering the app,
this methods allow to know if the permission is granted and allow to dynamically request it

Note: if the user dismisses the permission modal, will result as not allowed
*/
window.wakeuptimer.checkNotificationPerm(
    function (allowed) {
        console.log('Permission already granted? ', allowed ? 'yes' : 'no');
        // do something...
    },
    errorCallback,
);

window.wakeuptimer.requestNotificationPerm(
    function (allowed) {
        console.log('Permission was granted? ', allowed ? 'yes' : 'no');
        // do something...
    },
    errorCallback,
);

/*
Q: Why use this methods?
A: The Android system does't like to launch an app without any user interaction,
  but some manufactures like Xiaomi, allows the Auto Start, but its an manual action that can't be performed programatically,
  the method `checkAutoStartPrefs` allows you to verify if the user is using an device tha allows auto start,
  the method `openAutoStartPrefs` opens the app config so the user can enable the Auto Start option

  * some brands (like Samsung), in newer devices doesn't allow auto start, but has some advanced battery resources that is required to allows
    to start a service without user interaction, without that config enabled, even the streaming/ringtone may not initialize,
    so in some devices, the `openAutoStartPrefs` will open the battery options instead
*/
// check if the current device has an Auto Start preference
window.wakeuptimer.checkAutoStartPrefs(
    function (hasAutoStartPreferences) {
        if (hasAutoStartPreferences) {
            console.log('Auto Start preference available!');
            // do something...
        }
    },
    function (error) { }
);
// open the device Auto Start preference
window.wakeuptimer.openAutoStartPrefs(
    function (openedPreferences) {
        if (openedPreferences) {
            console.log('Auto Start preference opened');
            // do something...
        }
    },
    function (error) { }
);

/*
The Android system, allows the startup of an Backgroud Service,
  the `configure` method, allows you to configure a streaming/ringtone that will play once the alarm is triggered,
  showing a notification with a stop button, if the user clicks on the notification, the app will be opened and will trigger the normal `wakeup` event

  * the streaming/ringtone and notification will be active for up to 5 minutes, after that, it will be stopped automatically
  * once the service is closed, it will trigger an 'stopped' event (can be catch by the 'bind' method)
  * if both streaming and ringtone are configured, the streaming has a higher priority,
    the ringtone will play only if the streaming can't be played
    or the streaming stops unitentionally (can be a connection problem or the streaming ended)
  * the ringtone url can be obtained by the plugin cordova-plugin-native-ringtones

Optional dependencie:
  cordova plugin add https://github.com/EltonFaust/cordova-plugin-native-ringtones
    * this plugin return all device ringtones
*/

// stop the current alarm, once it's stopped, it will trigger an 'stopped' event
window.wakeuptimer.stop(function () {}, function (error) {});

// configure the startup notification
window.wakeuptimer.configure(
    function () {
        console.log('wakeup startup service configured!');
    },
    function (error) { },
    {
        // at least one of `streamingUrl` or `ringtone` is required`;
        // when set the `streamingUrl`, if the user is not on wifi and the `streamingOnlyWifi` is set,
        // or the streaming fails, it will fallback to the `ringtone` if is set

        // play a streaming on wakeup
        streamingUrl: 'http://hayatmix.net/;yayin.mp3.m3u',
        // only play streaming on wifi (Optional, default: false)
        streamingOnlyWifi: true,
        // The ringtone that will play, can be obtained by the `cordova-plugin-native-ringtones` plugin
        ringtone: ringtoteUrl,
        // Ringtone volume, integer from 0 (0%) to 100 (100%) (Optional, default: 100)
        volume: 100,
        // Stream type (Optional, default: window.cordova.plugins.NativeRingtones.STREAM_ALARM)
        streamType: window.cordova.plugins.NativeRingtones.(STREAM_ALARM | STREAM_MUSIC),
        // The text that will be shown in the notication (Optional, default: %time%)
        //   * the '%time%' will be replaced with the active alarm time,
        //     with a format 'h:mm a' when configured a 12h clock, and a format "HH:mm" to a 24h clock
        notificationText: "Wakeup it's %time%",
    }
);

// Eg.: Simple config, obtaining the available ringtones with `cordova-plugin-native-ringtones` and configuring the first ringtone:
window.cordova.plugins.NativeRingtones.getRingtone(
    (nativeRingtones /*Array<{ Name: string, Url: string }>*/) => {
        window.wakeuptimer.configure(
            function () { }, function (error) { },
            {
                ringtone: nativeRingtones[0].Url,
            }
        );
    },
    function (error) {}, 'alarm'
);
```

## Log Debug
```sh
adb logcat -s "WakeupStartService" -s "WakeupReceiver" -s "WakeupPlugin" -s "WakeupBootReceiver" -s "WakeupAutoStartHelper"
```
