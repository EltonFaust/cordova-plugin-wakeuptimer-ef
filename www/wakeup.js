var exec = require("cordova/exec");

/**
 * This is a global variable called wakeup exposed by cordova
 */    
var Wakeup = (function (){
    function Wakeup() {
        // https://developer.android.com/reference/android/media/AudioManager.html
        this.STREAM_MUSIC = 3;
        this.STREAM_ALARM = 4;
    }

    Wakeup.prototype.bind = function (success, error) {
        exec(success, error, "WakeupPlugin", "bind", []);
    };

    Wakeup.prototype.configure = function (success, error, options) {
        exec(success, error, "WakeupPlugin", "configure", [options]);
    };

    Wakeup.prototype.checkAutoStartPrefs = function (success, error) {
        exec(success, error, "WakeupPlugin", "checkAutoStartPrefs", []);
    };

    Wakeup.prototype.openAutoStartPrefs = function (success, error) {
        exec(success, error, "WakeupPlugin", "openAutoStartPrefs", []);
    };

    Wakeup.prototype.checkNotificationPerm = function (success, error) {
        exec(success, error, "WakeupPlugin", "checkNotificationPerm", []);
    };

    Wakeup.prototype.requestNotificationPerm = function (success, error) {
        exec(success, error, "WakeupPlugin", "requestNotificationPerm", []);
    };

    Wakeup.prototype.wakeup = function (success, error, options) {
        exec(success, error, "WakeupPlugin", "wakeup", [options]);
    };

    Wakeup.prototype.stop = function (success, error) {
        exec(success, error, "WakeupPlugin", "stop", []);
    };

    return new Wakeup();
})();


module.exports = Wakeup;
