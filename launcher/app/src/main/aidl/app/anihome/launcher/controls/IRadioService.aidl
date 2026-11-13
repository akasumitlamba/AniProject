package app.anihome.launcher.controls;
interface IRadioService {
    boolean scanWifi() = 0;
    boolean setWifiPower(boolean enabled) = 1;
    String connectWifi(String ssid, String capabilities, String password) = 2;
    boolean connectBluetooth(IBinder profileBinder, int profile, String address) = 3;
    boolean setBluetoothPower(boolean enabled) = 4;
    int bluetoothConnectTransaction(int profile) = 5;
    String applyRotation(int mode, int angle) = 6;
    /** Allowlisted shell-level toggles only: dark, battery_saver, location, airplane. */
    String setSystemToggle(String id, boolean enabled) = 7;
    /** JSON array of apps currently playing audio: [{"uid":…,"package":"…"}]. */
    String activePlayers() = 8;
    /** Sets the volume (0..1) of every active player owned by uid; false if unsupported. */
    boolean setAppVolume(int uid, float volume) = 9;
    void destroy() = 16777114;
}
