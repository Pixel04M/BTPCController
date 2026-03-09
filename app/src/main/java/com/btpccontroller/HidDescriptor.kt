package com.btpccontroller

/**
 * Standard HID Keyboard Report Descriptor.
 *
 * Report format (8 bytes):
 *   Byte 0: Modifier keys (bitfield: LCtrl, LShift, LAlt, LWin, RCtrl, RShift, RAlt, RWin)
 *   Byte 1: Reserved (always 0x00)
 *   Bytes 2–7: Up to 6 simultaneous keycodes
 */
object HidDescriptor {

    @Suppress("SpellCheckingInspection")
    val DESCRIPTOR: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01,              // Usage Page (Generic Desktop Controls)
        0x09.toByte(), 0x06,              // Usage (Keyboard)
        0xA1.toByte(), 0x01,              // Collection (Application)

        // ── Modifier keys (8 bits) ──────────────────────────────────────────
        0x05.toByte(), 0x07,              //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(),     //   Usage Minimum (Left Control = 0xE0)
        0x29.toByte(), 0xE7.toByte(),     //   Usage Maximum (Right GUI    = 0xE7)
        0x15.toByte(), 0x00,              //   Logical Minimum (0)
        0x25.toByte(), 0x01,              //   Logical Maximum (1)
        0x75.toByte(), 0x01,              //   Report Size (1 bit each)
        0x95.toByte(), 0x08,              //   Report Count (8 bits)
        0x81.toByte(), 0x02,              //   Input (Data, Variable, Absolute)

        // ── Reserved byte ────────────────────────────────────────────────────
        0x95.toByte(), 0x01,              //   Report Count (1)
        0x75.toByte(), 0x08,              //   Report Size (8 bits)
        0x81.toByte(), 0x03,              //   Input (Constant)

        // ── LED output (5 bits: NumLock, CapsLock, ScrollLock, Compose, Kana) ─
        0x95.toByte(), 0x05,              //   Report Count (5)
        0x75.toByte(), 0x01,              //   Report Size (1 bit each)
        0x05.toByte(), 0x08,              //   Usage Page (LEDs)
        0x19.toByte(), 0x01,              //   Usage Minimum (Num Lock)
        0x29.toByte(), 0x05,              //   Usage Maximum (Kana)
        0x91.toByte(), 0x02,              //   Output (Data, Variable, Absolute)

        // ── LED padding (3 bits) ─────────────────────────────────────────────
        0x95.toByte(), 0x01,              //   Report Count (1)
        0x75.toByte(), 0x03,              //   Report Size (3 bits)
        0x91.toByte(), 0x03,              //   Output (Constant)

        // ── Keycodes (6 simultaneous keys) ───────────────────────────────────
        0x95.toByte(), 0x06,              //   Report Count (6)
        0x75.toByte(), 0x08,              //   Report Size (8 bits)
        0x15.toByte(), 0x00,              //   Logical Minimum (0)
        0x25.toByte(), 0x65,              //   Logical Maximum (101)
        0x05.toByte(), 0x07,              //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00,              //   Usage Minimum (0)
        0x29.toByte(), 0x65,              //   Usage Maximum (101)
        0x81.toByte(), 0x00,              //   Input (Data, Array, Absolute)

        0xC0.toByte()                     // End Collection
    )
}
