package app.anihome.launcher.controls;
interface IRadioService {
    boolean scanWifi() = 0;
    boolean setWifiPower(boolean enabled) = 1;
    String connectWifi(String ssid, String capabilities, String password) = 2;
    boolean connectBluetooth(IBinder profileBinder, int profile, String address) = 3;
    boolean setBluetoothPower(boolean enabled) = 4;
    int bluetoothConnectTransaction(int profile) = 5;
    String applyRotation(int mode, int angle) = 6;
    void destroy() = 16777114;
}
