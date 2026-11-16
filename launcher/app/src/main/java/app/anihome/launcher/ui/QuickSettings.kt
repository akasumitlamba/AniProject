package app.anihome.launcher.ui

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.anihome.launcher.controls.RadioBridge
import app.anihome.launcher.controls.IRadioService
import kotlinx.coroutines.*

/** Only observes radios while the sheet is visible. Never persists scan results or credentials. */
// Radio operations check permissions via granted()/allowedBluetooth(); framework revocation
// is also caught at the operation boundary. Lint cannot follow these shared guards.
@android.annotation.SuppressLint("MissingPermission")
internal class QuickSettings(private val activity: AppCompatActivity, private val settingsContent: (LinearLayout) -> Unit) {
    private val wifi = activity.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetooth = activity.getSystemService(BluetoothManager::class.java)?.adapter
    private val recent = activity.getSharedPreferences("quick_connections", Context.MODE_PRIVATE)
    private val found = linkedMapOf<String, BluetoothDevice>()
    private var content: LinearLayout? = null
    private var selected: String? = null
    private var rotationTile: View? = null
    private var status = ""
    private var wifiDraft: ScanResult? = null
    private var scrollView: LimitedScroll? = null
    private val bridge: RadioBridge = RadioBridge(activity) { Handler(Looper.getMainLooper()).post { if (listening) { if (!bridge.ready) interruptBluetooth("Direct Bluetooth access disconnected. Reconnect and retry."); render() } } }
    private var radioBusy = false
    private val wifiScanRevision = OperationRevision()
    private val bluetoothAttempt = OperationRevision()
    private var connectingNetwork: String? = null
    private var needsPasswordFor: String? = null
    private val scanTimeout = Runnable { if (listening && status == "Searching…") { status = "Scan timed out. Tap refresh to retry."; render() } }
    private val handler = Handler(Looper.getMainLooper())
    private class LimitedScroll(context: Context, var limit: Int) : ScrollView(context) {
        override fun onMeasure(width: Int, height: Int) {
            super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(minOf(limit.coerceAtLeast(1), if (View.MeasureSpec.getMode(height) == View.MeasureSpec.UNSPECIFIED) limit.coerceAtLeast(1) else View.MeasureSpec.getSize(height)), View.MeasureSpec.AT_MOST))
        }
    }
    private fun privileged(work: (IRadioService) -> String, result: (String) -> Unit = { status = it; render() }) {
        if (radioBusy) return
        val service = bridge.service ?: return
        radioBusy = true
        activity.lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) { runCatching { work(service) }.onFailure { android.util.Log.w("AniHomeRadio", "${it.javaClass.simpleName} at ${it.stackTrace.firstOrNull()}") }.getOrDefault("Radio action failed. Check Shizuku access.") }
            radioBusy = false
            if (listening) result(response)
        }
    }
    /** Shizuku-backed shell toggles for the Control Center; never runs while another radio action is pending. */
    val privilegedReady: Boolean get() = bridge.ready
    val privilegedConnecting: Boolean get() = bridge.connecting
    fun systemToggle(id: String, enabled: Boolean, done: (String) -> Unit) {
        val service = bridge.service ?: run { done("Connect Shizuku in Home settings to use this control"); return }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { service.setSystemToggle(id, enabled) }.getOrDefault("Shizuku is unavailable") }
            done(result)
        }
    }
    fun connectPrivileged() = connectQuietly()
    /** Through Shizuku: the one-time permission that lets AniHome switch its own services on. */
    fun grantSecureSettings(done: () -> Unit) = grantSecureSettings(done, 6)
    private fun grantSecureSettings(done: () -> Unit, tries: Int) {
        if (!privilegedReady && tries > 0) {
            connectPrivileged()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ grantSecureSettings(done, tries - 1) }, 600)
            return
        }
        systemToggle("grant_secure_settings", true) { done() }
    }
    private fun authorizeControls() {
        if (app.anihome.launcher.controls.ShizukuGate.require(activity, "Direct Wi-Fi and Bluetooth control")) bridge.authorize()
    }
    /** Connects to Shizuku when it is already set up; never prompts. */
    fun connectQuietly() {
        if (app.anihome.launcher.controls.ShizukuGate.ready(activity)) bridge.authorize()
    }
    private var renderedSection: String? = null
    private var listening = false
    var returnToPanel = false
        private set
    fun consumeReturn(): Boolean = returnToPanel.also { returnToPanel = false }
    private val connected = mutableSetOf<String>()
    private var scanningBluetooth = false
    private var pendingBluetooth: String? = null
    private val bluetoothTimeout = Runnable { if (pendingBluetooth != null) { pendingBluetooth = null; status = "Connection timed out. Tap the device to retry."; render() } }
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (listening) {
            if (result.values.all { it }) { refreshProfiles(); scan() }
            else { status = "Permission denied. Allow discovery in app permissions to search."; render() }
        }
    }
    private val profiles = mutableMapOf<Int, BluetoothProfile>()
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!listening) { bluetooth?.closeProfileProxy(profile, proxy); return }
            profiles[profile] = proxy
            runCatching { proxy.connectedDevices.forEach { remember(it); connected.add(it.address) } }
            render()
        }
        override fun onServiceDisconnected(profile: Int) { profiles.remove(profile); interruptBluetooth("Bluetooth connection service disconnected. Tap to retry."); if (listening) render() }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { RotationControl.applyHome(activity); render() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            runCatching {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
                        interruptBluetooth("Bluetooth is off"); connected.clear(); found.clear(); scanningBluetooth = false; status = "Bluetooth is off"
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> device?.let {
                        if (pendingBluetooth == it.address && it.bondState == BluetoothDevice.BOND_BONDED) connectBluetooth(it)
                        else if (pendingBluetooth == it.address && it.bondState == BluetoothDevice.BOND_NONE) { pendingBluetooth = null; status = "Pairing was not completed. Tap to retry." }
                    }
                    BluetoothDevice.ACTION_FOUND -> device?.let { found[it.address] = it }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> device?.let { connected.add(it.address); remember(it); if (pendingBluetooth == it.address) { pendingBluetooth = null; handler.removeCallbacks(bluetoothTimeout); status = "" } }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> device?.let {
                        connected.remove(it.address)
                        if (!app.anihome.launcher.controls.BluetoothDevices.autoConnect(activity, it))
                            app.anihome.launcher.controls.BluetoothDevices.setAutoConnect(activity, profiles, it, false)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { scanningBluetooth = false; status = "Search complete" }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION") val ssid = wifi?.connectionInfo?.ssid?.removeSurrounding("\"")
                        if (ssid == connectingNetwork && intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)?.isConnected == true) { connectingNetwork = null; status = "" }
                    }
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> if (intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) {
                        needsPasswordFor = connectingNetwork; connectingNetwork = null; status = "Password rejected. Tap the network to update it."
                    }
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> { wifiScanRevision.invalidate(); handler.removeCallbacks(scanTimeout); status = if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)) "" else "Showing last results; scan was limited by Android." }
                }
            }
            render()
        }
    }
    private var expandedHeight = 0
    fun resize(sheetHeight: Int) {
        val next = dp(AdaptiveLayout.expandedHeight((sheetHeight / activity.resources.displayMetrics.density).toInt(), activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT))
        if (next == expandedHeight) return
        expandedHeight = next
        scrollView?.let { scroll ->
            scroll.limit = Int.MAX_VALUE; scroll.requestLayout()
        }
    }
    fun attach(parent: LinearLayout, sheetHeight: Int) {
        resize(sheetHeight)
        content = parent; listening = true
        bridge.reconnect()
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION); addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Bluetooth broadcasts can originate in the privileged Bluetooth process, not the system UID.
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        activity.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        refreshProfiles()
        render()
        if (selected == "Wi-Fi" && granted(Manifest.permission.ACCESS_FINE_LOCATION) && wifi?.isWifiEnabled == true) scan()
    }
    private fun refreshProfiles() {
        if (allowedBluetooth()) listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).filter { it !in profiles }.forEach {
            runCatching { bluetooth?.getProfileProxy(activity, profileListener, it) }
        }
    }
    fun detach() {
        handler.removeCallbacks(bluetoothTimeout)
        pendingBluetooth = null
        bluetoothAttempt.invalidate()
        listening = false
        handler.removeCallbacks(scanTimeout)
        runCatching { activity.unregisterReceiver(receiver) }
        activity.contentResolver.unregisterContentObserver(observer)
        if (scanningBluetooth && allowedBluetooth()) runCatching { bluetooth?.cancelDiscovery() }
        profiles.forEach { (id, proxy) -> bluetooth?.closeProfileProxy(id, proxy) }; profiles.clear()
        found.clear(); connected.clear(); content = null; wifiDraft = null; scrollView = null; renderedSection = null
    }
    private fun dp(n: Int) = ArtworkUi.dp(activity, n)
    private fun allowedBluetooth() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED
    private fun remember(device: BluetoothDevice) { recent.edit().putLong("bt:${device.address}", System.currentTimeMillis()).apply() }
    private fun open(action: String) {
        runCatching { launch(Intent(action)) }.onFailure { Toast.makeText(activity, "This setting is unavailable", Toast.LENGTH_SHORT).show() }
    }
    private fun launch(intent: Intent) {
        activity.startActivity(intent)
        returnToPanel = true
    }
    private fun row(parent: LinearLayout, title: String, detail: String = "", active: Boolean = false, icon: Int = 0,
                    longClick: ((View) -> Unit)? = null, click: () -> Unit) {
        val label = TextView(activity).apply {
            text = android.text.SpannableString(if (detail.isEmpty()) title else "$title\n$detail").apply {
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
                if (detail.isNotEmpty()) {
                    setSpan(android.text.style.RelativeSizeSpan(0.85f), title.length + 1, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(0xffbdbdbd.toInt()), title.length + 1, length, 0)
                }
            }; textSize = 13f
            setTextColor(if (active) 0xffffffff.toInt() else 0xffeeeeee.toInt())
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { setColor(if (active) 0xff383838.toInt() else 0xff242424.toInt()); cornerRadius = dp(10).toFloat() }
            foreground = ContextCompat.getDrawable(activity, app.anihome.launcher.R.drawable.bg_drawer_item)
            clipToOutline = true
            isFocusable = true; setOnClickListener { click() }
            if (icon != 0) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, if (longClick != null) app.anihome.launcher.R.drawable.ic_more_vertical else 0, 0)
                compoundDrawablePadding = dp(12)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (active) 0xff8ab4ff.toInt() else 0xffd0d3da.toInt())
            }
            longClick?.let { menu -> setOnLongClickListener { menu(this); true } }
        }
        // The ⋯ at the end of a device row opens its options too.
        if (longClick != null) label.setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && e.x > v.width - dp(48)) { longClick(v); true } else false
        }
        parent.addView(label, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
    /** Control Center owns the toggle grid; radio lists render into its slot instead of a footer. */
    var showControlRow = true
    var radioParent: LinearLayout? = null
    val section: String? get() = selected
    val rotationAutomatic: Boolean get() = rotationMode() == "Auto"
    fun toggleRotation() = cycleRotation()
    /** Same as tapping the old footer button: expand/collapse Wi-Fi or Bluetooth and scan. */
    fun openSection(name: String) {
        wifiDraft = null
        selected = if (selected == name) null else name
        status = ""
        render()
        if (selected == name && ((name == "Wi-Fi" && granted(Manifest.permission.ACCESS_FINE_LOCATION) && wifi?.isWifiEnabled == true) ||
            (name == "Bluetooth" && allowedBluetooth() && runCatching { bluetooth?.isEnabled == true }.getOrDefault(false) &&
                (Build.VERSION.SDK_INT >= 31 || granted(Manifest.permission.ACCESS_FINE_LOCATION))))) scan()
    }
    fun showSettings() { selected = "Settings"; wifiDraft = null; render() }
    fun showNotifications() { selected = null; wifiDraft = null; status = ""; render() }
    var onSectionChanged: ((String?) -> Unit)? = null
    private fun render() {
        onSectionChanged?.invoke(selected)
        val parent = content ?: return
        (rotationTile as? ImageButton)?.setImageResource(when (rotationMode()) {
            "Fixed" -> app.anihome.launcher.R.drawable.ic_rotation_locked
            "Landscape" -> app.anihome.launcher.R.drawable.ic_landscape
            else -> app.anihome.launcher.R.drawable.ic_rotation
        })
        rotationTile?.contentDescription = "Rotation, ${rotationMode()}. Tap to toggle Auto and Fixed"
        if (selected == "Settings" && renderedSection == "Settings") return
        if (wifiDraft != null) return // Radio broadcasts must not destroy an in-progress password.
        val previousScroll = if (renderedSection == selected) scrollView?.scrollY ?: 0 else 0
        // Sections open as full-height pages in the panel, so long device lists scroll freely.
        val target = parent
        // Fade pages in once laid out; never start a transition while views are being replaced.
        if (renderedSection != selected) parent.post { parent.alpha = .0f; parent.animate().alpha(1f).setDuration(MotionUi.duration(parent, 180)).start() }
        renderedSection = selected
        parent.removeAllViews(); radioParent?.removeAllViews()

        // A single expanded section shares the remaining height with notifications.
        selected?.let { section ->
            val sectionRoot = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // Pages sit directly on the panel's frosted backdrop; their cards carry the surfaces.
                background = null
                clipToOutline = true
                setPadding(0, dp(2), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            val scroll = LimitedScroll(activity, Int.MAX_VALUE).apply { isFillViewport = false; isVerticalScrollBarEnabled = true }
            scrollView = scroll
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(body)
            target.addView(sectionRoot, LinearLayout.LayoutParams(-1, 0, 1f))
            when (section) {
                "Settings" -> {
                    settingsContent(body)
                }
                "Wi-Fi" -> wifiRows(body, sectionRoot)
                "Bluetooth" -> try { bluetoothRows(body, sectionRoot) } catch (_: SecurityException) {
                    note(body, "Nearby-device access changed. Reopen this panel to grant access.")
                }
            }
            // Two-pane settings scroll each pane independently, so no outer scroll there.
            if (section == "Settings" && SettingsPanel.twoPane(activity)) {
                scroll.removeView(body); scrollView = null
                sectionRoot.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            } else sectionRoot.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            scroll.post { scroll.scrollTo(0, previousScroll) }
        }
        if (!showControlRow) return
        val controls = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("Wi-Fi", "Bluetooth", "Rotation", "Settings").forEach { name ->
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val icon = when (name) { "Wi-Fi" -> app.anihome.launcher.R.drawable.ic_wifi; "Bluetooth" -> app.anihome.launcher.R.drawable.ic_bluetooth; "Settings" -> app.anihome.launcher.R.drawable.ic_settings; else -> when (rotationMode()) { "Fixed" -> app.anihome.launcher.R.drawable.ic_rotation_locked; "Landscape" -> app.anihome.launcher.R.drawable.ic_landscape; else -> app.anihome.launcher.R.drawable.ic_rotation } }
            val tile = ImageButton(activity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(if (selected == name) -1 else 0xffbbbbbb.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12), dp(12), dp(12), dp(12)); minimumHeight = dp(48)
                background = GradientDrawable().apply { setColor(if (selected == name) 0xff383838.toInt() else android.graphics.Color.TRANSPARENT); cornerRadius = dp(12).toFloat() }
                foreground = ContextCompat.getDrawable(activity, app.anihome.launcher.R.drawable.bg_drawer_item)
                clipToOutline = true; isFocusable = true
                contentDescription = if (name == "Rotation") "Rotation, ${rotationMode()}. Tap to toggle Auto and Fixed"
                    else "$name, ${if (selected == name) "expanded" else "collapsed"}"
                setOnClickListener {
                    if (name == "Rotation") cycleRotation()
                    else {
                        wifiDraft = null
                        selected = if (selected == name) null else name
                        status = ""
                        render()
                        if (selected == name && ((name == "Wi-Fi" && granted(Manifest.permission.ACCESS_FINE_LOCATION) && wifi?.isWifiEnabled == true) ||
                            (name == "Bluetooth" && allowedBluetooth() && runCatching { bluetooth?.isEnabled == true }.getOrDefault(false) &&
                                (Build.VERSION.SDK_INT >= 31 || granted(Manifest.permission.ACCESS_FINE_LOCATION))))) scan()
                    }
                }
            }
            if (name == "Rotation") rotationTile = tile
            column.gravity = Gravity.CENTER
            column.addView(tile, LinearLayout.LayoutParams(dp(48), dp(48)))
            controls.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        parent.addView(controls)
    }
    private fun scan() {
        if (radioBusy) return
        if (selected == "Bluetooth") {
            if (!allowedBluetooth()) { permission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)); return }
            if (Build.VERSION.SDK_INT < 31 && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return
            }
            status = runCatching {
                if (bluetooth?.isEnabled != true) { open(BluetoothAdapter.ACTION_REQUEST_ENABLE); "Turn on Bluetooth, then search again" }
                else { found.clear(); bluetooth.cancelDiscovery(); scanningBluetooth = bluetooth.startDiscovery(); if (scanningBluetooth) "Searching…" else "Search unavailable; check Location and Bluetooth" }
            }.getOrDefault("Bluetooth unavailable")
        } else {
            handler.removeCallbacks(scanTimeout)
            if (bridge.ready) {
                val requestedRevision = wifiScanRevision.snapshot()
                status = "Searching…"; render()
                privileged({ if (runCatching { it.scanWifi() }.getOrDefault(false)) "Searching…" else frameworkWifiScan() }) {
                    if (wifiScanRevision.isCurrent(requestedRevision)) status = it
                    render(); if (status == "Searching…") handler.postDelayed(scanTimeout, 12000)
                }
                return
            }
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return }
            status = frameworkWifiScan()
        }
        render()
        if (status == "Searching…" && selected == "Wi-Fi") handler.postDelayed(scanTimeout, 12000)
    }
    @Suppress("DEPRECATION")
    private fun frameworkWifiScan(): String = if (runCatching { wifi?.startScan() == true }.getOrDefault(false)) "Searching…"
        else "Showing last scan. Android limits scan frequency; check Wi-Fi and Location."
    private fun radioHeader(headerParent: LinearLayout, body: LinearLayout, name: String, enabled: Boolean, toggle: (Boolean) -> Unit) {
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0) }
        header.addView(ArtworkUi.title(activity, name).apply { textSize = 14f }, LinearLayout.LayoutParams(0, dp(40), 1f))
        fun headerIcon(icon: Int, label: String, action: () -> Unit) = ArtworkUi.plainIcon(activity, icon, label) { action() }.apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        header.addView(headerIcon(app.anihome.launcher.R.drawable.ic_settings, "$name options") {
            open(if (name == "Wi-Fi") Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(headerIcon(app.anihome.launcher.R.drawable.ic_refresh, "Search $name") { scan() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(androidx.appcompat.widget.SwitchCompat(activity).apply {
            isChecked = enabled; contentDescription = "$name power"; minHeight = dp(40)
            setOnCheckedChangeListener { _, checked -> toggle(checked); render() }
            setPadding(dp(4), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(dp(56), dp(40)))
        headerParent.addView(header, LinearLayout.LayoutParams(-1, dp(40)))
        if (!bridge.ready) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(0xff252525.toInt()); cornerRadius = dp(12).toFloat() }
            }
            card.addView(TextView(activity).apply {
                text = if (bridge.connecting) "Connecting to Shizuku…" else "Optional: switch networks right here"
                textSize = 13f; setTextColor(0xffeeeeee.toInt())
            })
            card.addView(TextView(activity).apply {
                text = "With Shizuku set up, AniHome can connect and switch directly. Without it, tapping opens Android's settings."
                textSize = 12f; setTextColor(0xffbbbbbb.toInt()); setPadding(0, dp(4), 0, 0)
            })
            card.addView(ArtworkUi.textChip(activity, "Set up Shizuku") { authorizeControls() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12); marginEnd = dp(12); topMargin = dp(8); bottomMargin = dp(10) })
        }
    }
    private fun wifiRows(body: LinearLayout, header: LinearLayout) {
        radioHeader(header, body, "Wi-Fi", wifi?.isWifiEnabled == true) { enabled ->
            if (bridge.ready) {
                privileged({ if (it.setWifiPower(enabled)) "" else "Wi-Fi power change failed" }); return@radioHeader
            }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 29 && runCatching { wifi?.setWifiEnabled(enabled) == true }.getOrDefault(false)) return@radioHeader
            status = "Direct Wi-Fi access is required. Tap Enable direct controls to enable Shizuku."
            render()
        }
        if (status.isNotEmpty()) { note(body, status) }
        if (status.startsWith("Permission denied")) row(body, "App permissions") {
            runCatching { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }
        }
        val location = activity.getSystemService(android.location.LocationManager::class.java)
        val locationEnabled = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
            else location?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true || location?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
        if (!locationEnabled) row(body, "Enable Location for discovery", "Required by Android for Wi-Fi scans") { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        if (wifi?.isWifiEnabled != true) { note(body, "Wi-Fi is off. Use the switch above to enable it."); return }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { row(body, "Allow nearby network discovery", "Android requires Location permission for Wi-Fi names") { scan() }; return }
        @Suppress("DEPRECATION") val current = runCatching { wifi?.connectionInfo?.ssid?.removeSurrounding("\"") }.getOrNull()?.takeUnless { it == "<unknown ssid>" }
        if (!current.isNullOrEmpty()) recent.edit().putLong("wifi:$current", System.currentTimeMillis()).apply()
        @Suppress("DEPRECATION") val results = runCatching { wifi?.scanResults.orEmpty() }.getOrDefault(emptyList())
        val networks = results.filter { it.SSID.isNotBlank() }.groupBy { it.SSID }.map { (_, group) -> group.maxBy { it.level } }
            .sortedWith(compareByDescending<ScanResult> { it.SSID == current }.thenByDescending { recent.getLong("wifi:${it.SSID}", 0) }.thenByDescending { it.level })
        if (!current.isNullOrEmpty() && networks.none { it.SSID == current }) row(body, current, "Connected", true, app.anihome.launcher.R.drawable.ic_wifi, { wifiMenu(it, current) }) { }
        if (networks.isEmpty()) note(body, "No networks yet. Search with Wi-Fi and Location enabled.")
        networks.forEach { result -> row(body, result.SSID, if (result.SSID == current) "Connected" else if (recent.contains("wifi:${result.SSID}")) "Recently connected" else if (result.capabilities.contains("PSK") || result.capabilities.contains("SAE")) "Password protected" else "Available", result.SSID == current,
            app.anihome.launcher.R.drawable.ic_wifi, if (result.SSID == current || recent.contains("wifi:${result.SSID}")) { anchor -> wifiMenu(anchor, result.SSID) } else null) {
            if (!bridge.ready && !app.anihome.launcher.controls.ShizukuGate.ready(activity)) {
                // No Shizuku: Android's own Wi-Fi picker does the connecting.
                open(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            } else if (!bridge.ready) {
                bridge.reconnect()
                status = "Connecting to Shizuku… tap the network again in a moment."
                render()
            } else if (needsPasswordFor != result.SSID) connectWifi(body, result, null) else credentials(body, result)
        } }
    }
    private fun connectWifi(body: LinearLayout, network: ScanResult, password: String?) {
        if (radioBusy || !bridge.ready) return
        wifiDraft = network // Keep this credential/list container alive until the asynchronous response.
        privileged({ it.connectWifi(network.SSID, network.capabilities, password) }) { response ->
            when (response) {
                "PASSWORD" -> credentials(body, network)
                "CONNECTING" -> {
                    wifiDraft = null; needsPasswordFor = null; connectingNetwork = network.SSID
                    @Suppress("DEPRECATION") val current = wifi?.connectionInfo?.ssid?.removeSurrounding("\"")
                    status = if (current == network.SSID) "Connected" else "Connecting…"
                    render()
                }
                "UNSUPPORTED" -> { wifiDraft = null; status = "This network needs Android Wi-Fi settings"; render() }
                else -> { wifiDraft = null; status = "Connection could not start. Check Shizuku and network credentials."; render() }
            }
        }
    }
    private fun credentials(body: LinearLayout, result: ScanResult) {
        if (!bridge.ready) { wifiDraft = null; status = "Direct Wi-Fi access is required. Tap Enable direct controls to enable Shizuku."; render(); return }
        wifiDraft = result
        body.removeAllViews()
        note(body, result.SSID)
        val secured = result.capabilities.contains("PSK") || result.capabilities.contains("SAE")
        if (result.capabilities.contains("EAP") || result.capabilities.contains("WEP")) {
            note(body, "New enterprise and WEP profiles cannot be configured here.")
            row(body, "Back to networks") { wifiDraft = null; render() }; return
        }
        val password = EditText(activity).apply { hint = "Password"; setTextColor(-1); setHintTextColor(0xffbdbdbd.toInt()); inputType = 129; isSingleLine = true }
        if (secured) body.addView(password, LinearLayout.LayoutParams(-1, dp(48)))
        row(body, "Connect") {
            if (!bridge.ready) { wifiDraft = null; status = "Direct access was lost. Tap Enable direct controls to reconnect Shizuku."; render(); return@row }
            val entered = password.text.toString()
            if (result.capabilities.contains("PSK") && !app.anihome.launcher.controls.WifiConfigPolicy.validPsk(entered)) {
                password.error = "Use 8–63 characters or a 64-digit hexadecimal key"; return@row
            }
            connectWifi(body, result, if (secured) entered else "")
        }
        row(body, "Back to networks") { wifiDraft = null; render() }
    }
    private fun interruptBluetooth(message: String) {
        bluetoothAttempt.invalidate()
        handler.removeCallbacks(bluetoothTimeout)
        if (pendingBluetooth != null) { pendingBluetooth = null; status = message }
    }
    private fun connectBluetooth(device: BluetoothDevice) {
        bluetooth?.cancelDiscovery()
        if (!bridge.ready && !app.anihome.launcher.controls.ShizukuGate.ready(activity)) { open(Settings.ACTION_BLUETOOTH_SETTINGS); return }
        // Auto-connect off blocks Android from connecting; allow it for this tap (restored on disconnect).
        if (!app.anihome.launcher.controls.BluetoothDevices.autoConnect(activity, device)) {
            app.anihome.launcher.controls.BluetoothDevices.setAutoConnect(activity, profiles, device, true)
            activity.getSharedPreferences("quick_connections", android.content.Context.MODE_PRIVATE).edit().putBoolean("auto:${device.address}", false).apply()
        }
        if (!bridge.ready) {
            bridge.reconnect()
            status = "Connecting to Shizuku… tap the device again in a moment."
            render(); return
        }
        if (radioBusy) { status = "Please wait for the current radio action."; render(); return }
        val service = bridge.service ?: run {
            pendingBluetooth = null
            status = "Direct Bluetooth access is not ready. Reconnect Shizuku and retry."
            render(); return
        }
        val attempt = bluetoothAttempt.invalidate()
        pendingBluetooth = device.address; status = "Connecting…"; render()
        radioBusy = true
        activity.lifecycleScope.launch {
            val accepted = try { app.anihome.launcher.controls.BluetoothConnections.connect(activity, service, device.address) }
                finally { radioBusy = false }
            if (!listening || !bluetoothAttempt.isCurrent(attempt)) return@launch
            status = if (device.address in connected) "" else if (accepted) "Connecting…" else "Connection could not start. Check the device is awake and in range, then retry."
            if (!accepted) pendingBluetooth = null
            handler.removeCallbacks(bluetoothTimeout)
            if (pendingBluetooth != null) handler.postDelayed(bluetoothTimeout, 15000)
            render()
        }
    }
    private fun bluetoothRows(body: LinearLayout, header: LinearLayout) {
        radioHeader(header, body, "Bluetooth", runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)) { enabled ->
            @Suppress("DEPRECATION")
            val changed = Build.VERSION.SDK_INT < 33 && allowedBluetooth() && runCatching {
                if (enabled) bluetooth?.enable() == true else bluetooth?.disable() == true
            }.getOrDefault(false)
            if (!changed && bridge.ready) {
                privileged({ if (it.setBluetoothPower(enabled)) "" else "Bluetooth could not be changed. Try again." })
            } else if (!changed) status = "Direct Bluetooth access is required. Tap Enable direct controls to enable Shizuku."
        }
        if (status.isNotEmpty()) { note(body, status) }
        if (status.startsWith("Permission denied")) row(body, "App permissions") {
            runCatching { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }
        }
        if (!allowedBluetooth()) { row(body, "Allow nearby devices") { scan() }; return }
        val devices = runCatching {
            val all = (bluetooth?.bondedDevices.orEmpty() + found.values).associateBy { it.address }
            QuickSettingsPolicy.ordered(all.values.map { QuickSettingsPolicy.Device(it.address, it.name ?: it.address,
                it.address in connected, recent.getLong("bt:${it.address}", 0), it.bondState == BluetoothDevice.BOND_BONDED) })
                .mapNotNull { all[it.id] }
        }.getOrDefault(emptyList())
        if (devices.isEmpty()) note(body, "No devices yet. Turn on Bluetooth and search.")
        devices.forEach { device ->
            row(body, device.name ?: device.address, when {
                device.address in connected -> "Connected"
                pendingBluetooth == device.address -> "Connecting…"
                device.bondState == BluetoothDevice.BOND_BONDING -> "Pairing…"
                recent.contains("bt:${device.address}") -> if (device.bondState == BluetoothDevice.BOND_BONDED) "Recently connected · Paired" else "Previously connected · Tap to pair"
                device.bondState == BluetoothDevice.BOND_BONDED -> "Paired · Tap to connect"
                else -> "Available · Tap to pair"
            }, device.address in connected, app.anihome.launcher.controls.BluetoothDevices.icon(device),
                if (device.bondState == BluetoothDevice.BOND_BONDED) { anchor -> deviceMenu(anchor, device) } else null) {
                runCatching {
                    if (device.bondState == BluetoothDevice.BOND_NONE) {
                        bluetooth?.cancelDiscovery()
                        pendingBluetooth = device.address
                        status = if (device.createBond()) "Pairing… Confirm Android's pairing request if prompted." else "Pairing could not start. Search again."
                        render()
                    } else if (device.bondState == BluetoothDevice.BOND_BONDED) connectBluetooth(device)
                }.onFailure { Toast.makeText(activity, "Device unavailable. Search again.", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    /** Saved-network options. Auto-connect needs Android 11+ and Shizuku. */
    private fun wifiMenu(anchor: View, ssid: String) {
        val key = "wifiauto:$ssid"
        val auto = recent.getBoolean(key, true)
        HomeMenu.show(anchor, ssid, listOf(
            HomeMenu.Item(1, "Wi-Fi settings", app.anihome.launcher.R.drawable.ic_settings, inline = true, short = "Settings") { open(Settings.ACTION_WIFI_SETTINGS) },
            HomeMenu.Item(2, "Auto-connect", app.anihome.launcher.R.drawable.ic_wifi,
                if (auto) "Joins by itself when in range" else "Only joins when you tap it", checked = auto) {
                if (Build.VERSION.SDK_INT < 30) { Toast.makeText(activity, "Android 10 doesn't support per-network auto-connect.", Toast.LENGTH_LONG).show(); return@Item }
                if (!app.anihome.launcher.controls.ShizukuGate.ready(activity)) {
                    Toast.makeText(activity, "Per-network auto-connect needs Shizuku. Set it up in AniHome Settings → Shizuku.", Toast.LENGTH_LONG).show(); return@Item
                }
                if (!privilegedReady) { connectQuietly(); Toast.makeText(activity, "Connecting to Shizuku… try again in a moment", Toast.LENGTH_SHORT).show(); return@Item }
                systemToggle("wifi_autojoin:$ssid", !auto) { result ->
                    if (result.isEmpty()) recent.edit().putBoolean(key, !auto).apply()
                    else Toast.makeText(activity, result, Toast.LENGTH_SHORT).show()
                    render()
                }
            }))
    }
    /** Per-device options: connect/disconnect, auto-connect on/off, forget. */
    private fun deviceMenu(anchor: View, device: BluetoothDevice) {
        val actions = app.anihome.launcher.controls.BluetoothDevices
        val isConnected = device.address in connected
        val auto = actions.autoConnect(activity, device)
        val items = mutableListOf<HomeMenu.Item>()
        items += if (isConnected) HomeMenu.Item(1, "Disconnect", app.anihome.launcher.R.drawable.ic_close, inline = true, short = "Disconnect") {
            if (actions.disconnect(profiles, device)) { status = "Disconnecting…"; render() }
            else Toast.makeText(activity, "This Android version blocks disconnecting from other apps. Use Bluetooth settings.", Toast.LENGTH_LONG).show()
        } else HomeMenu.Item(2, "Connect", app.anihome.launcher.R.drawable.ic_bluetooth, inline = true, short = "Connect") { connectBluetooth(device) }
        items += HomeMenu.Item(3, "Forget", app.anihome.launcher.R.drawable.ic_delete, inline = true, short = "Forget") {
            if (actions.forget(device)) { connected.remove(device.address); render() }
            else Toast.makeText(activity, "Couldn't forget this device here. Use Bluetooth settings.", Toast.LENGTH_SHORT).show()
        }
        items += HomeMenu.Item(4, "Bluetooth settings", app.anihome.launcher.R.drawable.ic_settings, inline = true, short = "Settings") { open(Settings.ACTION_BLUETOOTH_SETTINGS) }
        items += HomeMenu.Item(5, "Auto-connect", app.anihome.launcher.R.drawable.ic_bluetooth,
            if (auto) "Reconnects by itself when nearby" else "Only connects when you tap it", checked = auto) {
            if (!actions.setAutoConnect(activity, profiles, device, !auto))
                Toast.makeText(activity, "Android didn't allow changing auto-connect for this device.", Toast.LENGTH_LONG).show()
            render()
        }
        HomeMenu.show(anchor, device.name ?: device.address, items)
    }
    private fun rotationMode(): String = if (RotationControl.mode(activity) == "Auto") "Auto" else "Fixed"
    private fun cycleRotation() = setAutomaticRotation(rotationMode() != "Auto")
    fun setAutomaticRotation(automatic: Boolean) {
        if (!RotationControl.setAutomatic(activity, automatic)) {
            Toast.makeText(activity, "Allow system rotation access to change Android rotation.", Toast.LENGTH_LONG).show()
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}"))) }
        }
        render()
    }
    private fun note(body: LinearLayout, value: String) {
        body.addView(TextView(activity).apply { text = value; textSize = 12f; setTextColor(0xffbdbdbd.toInt()); setPadding(dp(12), dp(8), dp(12), dp(8)) })
    }
}
