package com.btpccontroller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

// ── Platform enum ─────────────────────────────────────────────────────────────

enum class Platform(val displayName: String) {
    WINDOWS("Windows"),
    LINUX("Linux"),
    MAC("macOS"),
    ANDROID_OS("Android"),
    UNKNOWN("Unknown")
}

// ── Detection result ──────────────────────────────────────────────────────────

data class DetectionResult(
    val platform: Platform,
    val reason: String,
    /** true = strong name-pattern match, false = class-based guess */
    val isConfident: Boolean
)

// ── Detector ──────────────────────────────────────────────────────────────────

object PlatformDetector {

    // Common Windows device name fragments
    private val WINDOWS_PATTERNS = listOf(
        "desktop-",   // Windows auto-generated hostnames
        "laptop-",
        "windows",
        "win-",
        "win10",
        "win11",
        "pc-",
        "thinkpad",
        "ideapad",
        "pavilion",
        "spectre",
        "inspiron",
        "xps",
        "surface",
        "legion",
        "yoga",
        "vivobook",
        "zenbook",
        "gram",       // LG Gram
        "envy",
        "omen",
        "workstation",
        "aspire",
        "nitro",
        "predator"
    )

    private val MAC_PATTERNS = listOf(
        "macbook",
        "imac",
        "mac pro",
        "macmini",
        "apple",
        "mbp",
        "mba",
        "mac-"
    )

    private val LINUX_PATTERNS = listOf(
        "ubuntu",
        "linux",
        "raspberrypi",
        "raspberry",
        "debian",
        "fedora",
        "arch",
        "manjaro",
        "mint",
        "kali",
        "pop-os",
        "pop_os",
        "elementary",
        "centos",
        "rhel",
        "opensuse",
        "nixos",
        "gentoo",
        "slackware",
        "tails",
        "parrot"
    )

    private val ANDROID_PATTERNS = listOf(
        "android",
        "pixel",
        "samsung",
        "oneplus",
        "xiaomi",
        "huawei",
        "oppo",
        "vivo",
        "realme",
        "motorola",
        "redmi",
        "poco",
        "galaxy",
        "sm-",
        "moto",
        "nokia",
        "tecno",
        "infinix",
        "itel"
    )

    /**
     * Detects the OS of [device] by examining its Bluetooth name and device class.
     *
     * Strategy:
     *  1. Match device name against known OS patterns (confident).
     *  2. Fall back to Bluetooth device class (low confidence).
     *  3. Return UNKNOWN if nothing matches.
     */
    @SuppressLint("MissingPermission")
    fun detect(device: BluetoothDevice): DetectionResult {
        val name = try {
            device.name?.lowercase()?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }

        if (name.isEmpty()) {
            return DetectionResult(Platform.UNKNOWN, "Device name unavailable", false)
        }

        // ── Name-pattern matching (ordered: Mac before Linux/Windows to avoid false hits) ──

        MAC_PATTERNS.find { name.contains(it) }?.let { pattern ->
            return DetectionResult(
                Platform.MAC,
                "Name matches macOS pattern: \"$pattern\"",
                isConfident = true
            )
        }

        LINUX_PATTERNS.find { name.contains(it) }?.let { pattern ->
            return DetectionResult(
                Platform.LINUX,
                "Name matches Linux pattern: \"$pattern\"",
                isConfident = true
            )
        }

        ANDROID_PATTERNS.find { name.contains(it) }?.let { pattern ->
            return DetectionResult(
                Platform.ANDROID_OS,
                "Name matches Android pattern: \"$pattern\"",
                isConfident = true
            )
        }

        WINDOWS_PATTERNS.find { name.contains(it) }?.let { pattern ->
            return DetectionResult(
                Platform.WINDOWS,
                "Name matches Windows pattern: \"$pattern\"",
                isConfident = true
            )
        }

        // ── Bluetooth device class fallback ───────────────────────────────────
        // BluetoothClass.Device values for reference:
        //   COMPUTER_DESKTOP  = 0x0104
        //   COMPUTER_LAPTOP   = 0x0108
        //   COMPUTER_SERVER   = 0x0112
        //   PHONE_SMART       = 0x020C
        val btClass = try { device.bluetoothClass?.deviceClass ?: -1 } catch (_: Exception) { -1 }

        return when (btClass) {
            0x0104, 0x0108, 0x0112, 0x0110 ->
                DetectionResult(Platform.WINDOWS, "BT class = Computer (defaulting to Windows)", false)
            0x020C, 0x0204, 0x0208 ->
                DetectionResult(Platform.ANDROID_OS, "BT class = Phone", false)
            else ->
                DetectionResult(Platform.UNKNOWN, "No pattern matched for: \"$name\"", false)
        }
    }
}
