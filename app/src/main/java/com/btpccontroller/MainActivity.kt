package com.btpccontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.btpccontroller.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val btExecutor = Executors.newSingleThreadExecutor()

    // ── App state ─────────────────────────────────────────────────────────────
    private var isRegistered = false
    private var selectedPlatform: Platform = Platform.WINDOWS
    private var isShiftActive = false
    private var isKeyboardVisible = false

    // ── Cached ColorStateLists — allocated once, reused on every selectPlatform call ─
    private val accentCsl    by lazy { ColorStateList.valueOf(getColor(R.color.accent)) }
    private val strokeCsl    by lazy { ColorStateList.valueOf(getColor(R.color.card_stroke)) }
    private val transparentCsl = ColorStateList.valueOf(Color.TRANSPARENT)

    // ── Single source of truth: platform → button ─────────────────────────────
    // Lazy so binding is ready; used in setupUi() and selectPlatform()
    private val platformButtonMap: Map<Platform, MaterialButton> by lazy {
        mapOf(
            Platform.WINDOWS    to binding.btnPlatformWindows,
            Platform.LINUX      to binding.btnPlatformLinux,
            Platform.MAC        to binding.btnPlatformMac,
            Platform.ANDROID_OS to binding.btnPlatformAndroid
        )
    }

    // ── Virtual keyboard ──────────────────────────────────────────────────────
    private val keyButtonRows = mutableListOf<List<MaterialButton>>()
    private var shiftBtn: MaterialButton? = null  // stored to toggle its highlight

    private val normalKeyRows = listOf(
        listOf("1","2","3","4","5","6","7","8","9","0"),
        listOf("q","w","e","r","t","y","u","i","o","p"),
        listOf("a","s","d","f","g","h","j","k","l"),
        listOf("z","x","c","v","b","n","m")
    )
    private val shiftKeyRows = listOf(
        listOf("!","@","#","$","%","^","&","*","(",")"),
        listOf("Q","W","E","R","T","Y","U","I","O","P"),
        listOf("A","S","D","F","G","H","J","K","L"),
        listOf("Z","X","C","V","B","N","M")
    )
    private val urlSpecialKeys = listOf(":", "//", ".", "/", "-", "_", "@", "?", "&", "=", "#")

    // ─────────────────────────────────────────────────────────────────────────
    // HID callback
    // ─────────────────────────────────────────────────────────────────────────

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (registered) {
                log("✓ Registered as HID keyboard")
                log("  Phone name: ${bluetoothAdapter?.name ?: "Unknown"}")
                log("→ Pair from your target device's Bluetooth settings")
                tryConnectToBondedDevices()
            } else {
                log("✗ HID app unregistered")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val name = deviceName(device)
            when (state) {
                BluetoothProfile.STATE_CONNECTING    -> log("⟳ Connecting to $name…")
                BluetoothProfile.STATE_DISCONNECTING -> log("⟳ Disconnecting from $name…")

                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    log("✓ Connected to $name")
                    val result = PlatformDetector.detect(device)
                    log("  Platform: ${result.reason}")
                    if (result.platform != Platform.UNKNOWN) selectedPlatform = result.platform
                    mainHandler.post { applyConnectedState(true, name, result) }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasOurs = connectedDevice?.address == device.address
                    if (!wasOurs) return
                    connectedDevice = null
                    log("✗ Disconnected from $name")
                    mainHandler.post { applyConnectedState(false, name, null) }

                    // ── Auto-reconnect if we're still registered (not a deliberate stop) ──
                    // This is the key fix: HID stays registered, so we can reconnect
                    // without the host needing to forget/re-pair the device.
                    if (isRegistered) {
                        log("→ Auto-reconnecting in 3 s…")
                        mainHandler.postDelayed({
                            if (connectedDevice == null && isRegistered) {
                                log("→ Retrying: $name")
                                hidDevice?.connect(device)
                            }
                        }, 3000)
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, ByteArray(8))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Profile listener
    // ─────────────────────────────────────────────────────────────────────────

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                log("✓ HID service ready — registering…")
                registerHidApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null; isRegistered = false
                log("✗ HID service disconnected")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Activity-result launchers
    // ─────────────────────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) initBluetooth()
        else log("✗ Permissions denied — cannot proceed")
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (bluetoothAdapter?.isEnabled == true) connectHidService() else log("✗ Bluetooth not enabled") }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { log("→ Discoverability request handled") }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUi()
        buildVirtualKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            connectedDevice?.let { hidDevice?.disconnect(it) }
            hidDevice?.unregisterApp()              // ← only called here, not on button press
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        }
        btExecutor.shutdown()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI wiring
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupUi() {
        binding.btnStart.isEnabled = false

        // ── Connect button: 3-mode logic ──────────────────────────────────────
        // CONNECTED   → disconnect device, keep HID registered (no re-pair needed)
        // REGISTERED  → just try connecting to bonded devices again
        // IDLE        → full init flow
        binding.btnConnect.setOnClickListener {
            when {
                connectedDevice != null -> disconnectDevice()
                isRegistered -> { log("→ Reconnecting…"); tryConnectToBondedDevices() }
                else -> checkPermissionsAndConnect()
            }
        }

        binding.btnDiscoverable.setOnClickListener { makeDiscoverable() }

        // Platform selector — wire from the single platformButtonMap
        platformButtonMap.forEach { (plat, btn) -> btn.setOnClickListener { selectPlatform(plat) } }
        selectPlatform(Platform.WINDOWS)

        // START
        binding.btnStart.setOnClickListener {
            val url = binding.etUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) { toast("Please enter a URL"); return@setOnClickListener }
            openUrlOnPlatform(url)
        }

        // Custom text
        binding.btnSendText.setOnClickListener {
            val text = binding.etCustomText.text?.toString().orEmpty()
            if (text.isEmpty()) { toast("Enter text to send"); return@setOnClickListener }
            sendCustomText(text)
        }

        // Hotkeys
        binding.btnHotkeyCopy.setOnClickListener      { sendHotkey(HidKeycodes.MOD_LEFT_CTRL, HidKeycodes.KEY_C) }
        binding.btnHotkeyPaste.setOnClickListener     { sendHotkey(HidKeycodes.MOD_LEFT_CTRL, HidKeycodes.KEY_V) }
        binding.btnHotkeyUndo.setOnClickListener      { sendHotkey(HidKeycodes.MOD_LEFT_CTRL, HidKeycodes.KEY_Z) }
        binding.btnHotkeySelectAll.setOnClickListener { sendHotkey(HidKeycodes.MOD_LEFT_CTRL, HidKeycodes.KEY_A) }
        binding.btnHotkeyEnter.setOnClickListener     { sendHotkey(HidKeycodes.MOD_NONE, HidKeycodes.KEY_ENTER) }
        binding.btnHotkeyBackspace.setOnClickListener { sendHotkey(HidKeycodes.MOD_NONE, HidKeycodes.KEY_BSPACE) }
        binding.btnHotkeyTab.setOnClickListener       { sendHotkey(HidKeycodes.MOD_NONE, HidKeycodes.KEY_TAB) }
        binding.btnHotkeyEsc.setOnClickListener       { sendHotkey(HidKeycodes.MOD_NONE, HidKeycodes.KEY_ESCAPE) }

        binding.tvKeyboardToggle.setOnClickListener { toggleKeyboard() }
        binding.tvClearLog.setOnClickListener { binding.tvStatus.text = "" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection state → UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyConnectedState(connected: Boolean, name: String, detection: DetectionResult?) {
        binding.btnConnect.text = if (connected) "Disconnect" else "Connect"
        binding.btnConnect.backgroundTintList = ColorStateList.valueOf(
            getColor(if (connected) R.color.red else R.color.connect_color)
        )
        binding.btnStart.isEnabled = connected
        binding.tvConnectionStatus.text = if (connected) "● $name" else "● Not connected"
        binding.tvConnectionStatus.setTextColor(getColor(if (connected) R.color.green else R.color.hint))

        // Badge: update on connect, but don't hide on disconnect — keeps showing last result
        if (detection != null) {
            binding.tvDetectionBadge.text = when {
                detection.platform == Platform.UNKNOWN ->
                    "⚠️  Could not detect OS — please select manually"
                detection.isConfident ->
                    "✅  Detected: ${detection.platform.displayName}"
                else ->
                    "⚠️  Likely ${detection.platform.displayName} (low confidence)"
            }
            binding.tvDetectionBadge.visibility = View.VISIBLE
            if (detection.platform != Platform.UNKNOWN) selectPlatform(detection.platform)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Platform selector
    // ─────────────────────────────────────────────────────────────────────────

    private fun selectPlatform(platform: Platform) {
        selectedPlatform = platform
        platformButtonMap.forEach { (p, btn) ->
            val sel = p == platform
            btn.backgroundTintList = if (sel) accentCsl else transparentCsl
            btn.setTextColor(getColor(if (sel) R.color.white else R.color.hint))
            btn.strokeColor = if (sel) accentCsl else strokeCsl
            btn.strokeWidth = dpToPx(1)
        }
        if (platform == Platform.ANDROID_OS) {
            log("ℹ️  Android: will try Meta+B (open Chrome).")
            log("   For best results, connect to a PC, Mac, or Linux host.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Virtual keyboard
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildVirtualKeyboard() {
        val container = binding.keyboardContainer
        keyButtonRows.clear()

        normalKeyRows.forEachIndexed { rowIdx, keys ->
            val row = newKeyRow()
            val buttons = keys.map { key ->
                newKeyButton(key) { onVirtualKeyTapped(it) }.also { row.addView(it) }
            }
            keyButtonRows.add(buttons)
            container.addView(row)
        }

        val urlRow = newKeyRow()
        urlSpecialKeys.forEach { key ->
            newKeyButton(key) { onVirtualKeyTapped(it) }.also { urlRow.addView(it) }
        }
        container.addView(urlRow)

        // Control row: store shiftBtn as field so onShiftToggle() can highlight it
        val ctrlRow = newKeyRow()
        val sb = newKeyButton("⇧") { /* click wired below via stored ref */ }.apply {
            setOnClickListener { onShiftToggle() }
        }
        shiftBtn = sb

        val spaceBtn = newKeyButton("SPACE") { onVirtualKeyTapped(" ") }.apply {
            (layoutParams as LinearLayout.LayoutParams).weight = 3f
        }
        val bkspBtn = newKeyButton("⌫") { sendHotkey(HidKeycodes.MOD_NONE, HidKeycodes.KEY_BSPACE) }

        ctrlRow.addView(sb); ctrlRow.addView(spaceBtn); ctrlRow.addView(bkspBtn)
        container.addView(ctrlRow)
    }

    private fun newKeyRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, dpToPx(2), 0, dpToPx(2)) }
    }

    private fun newKeyButton(label: String, onClick: (String) -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; textSize = 11f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            insetTop = 0; insetBottom = 0
            setPadding(dpToPx(1), dpToPx(4), dpToPx(1), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(38)).also {
                it.weight = 1f; it.setMargins(dpToPx(2), 0, dpToPx(2), 0)
            }
            setOnClickListener { onClick(text.toString()) }
        }

    private fun onVirtualKeyTapped(key: String) {
        val device = connectedDevice ?: run { toast("Not connected"); return }
        btExecutor.execute {
            key.forEach { ch ->
                HidKeycodes.charToKeycode(ch)?.let { info ->
                    sendKeyPress(device, info.modifier, info.keycode); Thread.sleep(30)
                }
            }
        }
        if (isShiftActive) { isShiftActive = false; refreshKeyboardLabels() }
    }

    private fun onShiftToggle() {
        isShiftActive = !isShiftActive
        shiftBtn?.backgroundTintList = if (isShiftActive) accentCsl else transparentCsl
        refreshKeyboardLabels()
    }

    // Symmetric null-safe row lookup — same branch for both normal and shift
    private fun refreshKeyboardLabels() {
        mainHandler.post {
            keyButtonRows.forEachIndexed { r, buttons ->
                val rowData = (if (isShiftActive) shiftKeyRows else normalKeyRows)
                    .getOrNull(r) ?: return@forEachIndexed
                buttons.forEachIndexed { k, btn -> if (k < rowData.size) btn.text = rowData[k] }
            }
        }
    }

    private fun toggleKeyboard() {
        isKeyboardVisible = !isKeyboardVisible
        binding.keyboardContainer.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        binding.tvKeyboardToggle.text = if (isKeyboardVisible) "▲ Hide Keyboard" else "▼ Show Keyboard"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bluetooth management
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkPermissionsAndConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN))    needed += Manifest.permission.BLUETOOTH_SCAN
        } else {
            if (!hasPerm(Manifest.permission.BLUETOOTH))       needed += Manifest.permission.BLUETOOTH
            if (!hasPerm(Manifest.permission.BLUETOOTH_ADMIN)) needed += Manifest.permission.BLUETOOTH_ADMIN
        }
        if (needed.isEmpty()) initBluetooth() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun initBluetooth() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = mgr.adapter
        if (bluetoothAdapter == null) { log("✗ Bluetooth not supported"); return }
        if (bluetoothAdapter?.isEnabled == false) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
        }
        connectHidService()
    }

    private fun connectHidService() {
        log("→ Opening HID Device service…")
        val ok = bluetoothAdapter!!.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        if (!ok) log("✗ getProfileProxy failed — device may not support HID Device role")
    }

    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT PC Controller", "Bluetooth HID Keyboard", "Android",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, HidDescriptor.DESCRIPTOR
        )
        val ok = hidDevice?.registerApp(sdp, null, null, btExecutor, hidCallback)
        if (ok == false) log("✗ registerApp() returned false — try restarting Bluetooth")
    }

    private fun tryConnectToBondedDevices() {
        val bonded = bluetoothAdapter?.bondedDevices ?: return
        if (bonded.isEmpty()) { log("  No paired devices — waiting for host to connect"); return }
        log("→ Trying ${bonded.size} paired device(s)…")
        bonded.forEach { hidDevice?.connect(it) }
    }

    private fun makeDiscoverable() {
        log("→ Requesting discoverable mode (300 s)…")
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
        )
    }

    /**
     * Disconnects the active device but keeps the HID app registered.
     *
     * The old `disconnectAndUnregister()` called `unregisterApp()` here, which forced
     * the host to forget and re-pair the device on every reconnect. By only calling
     * `disconnect()` and leaving the registration intact, the host can reconnect
     * seamlessly — no forget/re-pair needed.
     */
    private fun disconnectDevice() {
        connectedDevice?.let { hidDevice?.disconnect(it) }
        connectedDevice = null
        log("→ Disconnected  (HID still registered — tap Connect to reconnect)")
        mainHandler.post { applyConnectedState(false, "", null) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Platform-specific URL opening
    // ─────────────────────────────────────────────────────────────────────────

    private fun openUrlOnPlatform(url: String) {
        val device = connectedDevice ?: run { log("✗ Not connected"); return }
        binding.btnStart.isEnabled = false
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("▶ Opening on ${selectedPlatform.displayName}…")
        btExecutor.execute {
            try {
                when (selectedPlatform) {
                    Platform.WINDOWS    -> openUrlWindows(device, url)
                    Platform.LINUX      -> openUrlLinux(device, url)
                    Platform.MAC        -> openUrlMac(device, url)
                    Platform.ANDROID_OS -> openUrlAndroid(device, url)
                    Platform.UNKNOWN    -> openUrlWindows(device, url) // safe fallback
                }
                log("✓ Done!"); log("━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            } catch (e: Exception) {
                log("✗ Error: ${e.message}")
            } finally {
                mainHandler.post { binding.btnStart.isEnabled = true }
            }
        }
    }

    /**
     * Shared open-URL sequence: hotkey → wait → type text → Enter.
     * Used by Windows, Mac, Android, and Linux (with different args).
     */
    private fun openUrlSequence(
        device: BluetoothDevice,
        launchMod: Int, launchKey: Int, launchLog: String,
        waitMs: Long, text: String
    ) {
        log("  ⌨ $launchLog")
        sendKeyPress(device, launchMod, launchKey)
        Thread.sleep(waitMs)
        log("  ⌨ Typing: $text")
        typeText(device, text)
        Thread.sleep(150)
        log("  ⌨ Enter")
        sendKeyPress(device, HidKeycodes.MOD_NONE, HidKeycodes.KEY_ENTER)
    }

    /** Win + R → type URL → Enter */
    private fun openUrlWindows(device: BluetoothDevice, url: String) =
        openUrlSequence(device, HidKeycodes.MOD_LEFT_GUI, HidKeycodes.KEY_R,
            "Win + R (Run dialog)…", 900L, url)

    /** Ctrl + Alt + T → terminal opens → xdg-open <url> → Enter */
    private fun openUrlLinux(device: BluetoothDevice, url: String) =
        openUrlSequence(
            device,
            HidKeycodes.MOD_LEFT_CTRL or HidKeycodes.MOD_LEFT_ALT,  // combined modifier
            HidKeycodes.KEY_T,
            "Ctrl + Alt + T (open terminal)…",
            1600L,
            "xdg-open $url"
        )

    /** Cmd + Space (Spotlight) → type URL → Enter */
    private fun openUrlMac(device: BluetoothDevice, url: String) =
        openUrlSequence(device, HidKeycodes.MOD_LEFT_GUI, HidKeycodes.KEY_SPACE,
            "Cmd + Space (Spotlight)…", 900L, url)

    /** Meta + B → Chrome opens → type URL → Enter */
    private fun openUrlAndroid(device: BluetoothDevice, url: String) {
        log("  ⚠️  Android HID support is device-dependent.")
        openUrlSequence(device, HidKeycodes.MOD_LEFT_GUI, HidKeycodes.KEY_B,
            "Meta + B (open Chrome)…", 1500L, url)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text and hotkeys
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendCustomText(text: String) {
        val device = connectedDevice ?: run { toast("Not connected"); return }
        log("⌨ Sending: \"$text\"")
        btExecutor.execute { typeText(device, text); log("✓ Text sent") }
    }

    private fun sendHotkey(modifier: Int, keycode: Int) {
        val device = connectedDevice ?: run { toast("Not connected"); return }
        btExecutor.execute { sendKeyPress(device, modifier, keycode) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core HID
    // ─────────────────────────────────────────────────────────────────────────

    private fun typeText(device: BluetoothDevice, text: String) {
        for (ch in text) {
            val info = HidKeycodes.charToKeycode(ch)
            if (info != null) { sendKeyPress(device, info.modifier, info.keycode); Thread.sleep(40) }
            else log("  ! Skipping unmapped char: '$ch'")
        }
    }

    private fun sendKeyPress(device: BluetoothDevice, modifier: Int, keycode: Int) {
        hidDevice?.sendReport(device, 0, ByteArray(8).also { r ->
            r[0] = modifier.toByte(); r[2] = keycode.toByte()
        })
        Thread.sleep(30)
        hidDevice?.sendReport(device, 0, ByteArray(8))   // key release
        Thread.sleep(20)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun log(msg: String) = mainHandler.post {
        binding.tvStatus.append("$msg\n")
        binding.svStatus.post { binding.svStatus.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun deviceName(d: BluetoothDevice): String =
        try { d.name ?: d.address } catch (_: Exception) { d.address }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
