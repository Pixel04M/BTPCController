package com.btpccontroller

/**
 * Maps characters to HID keyboard scancodes + modifier bytes.
 *
 * Modifier bits:
 *   0x01 = Left Control
 *   0x02 = Left Shift
 *   0x04 = Left Alt
 *   0x08 = Left GUI (Win key)
 *   0x10 = Right Control
 *   0x20 = Right Shift
 *   0x40 = Right Alt
 *   0x80 = Right GUI
 */
data class KeyInfo(val modifier: Int, val keycode: Int)

object HidKeycodes {

    // ── Modifier constants ──────────────────────────────────────────────────
    const val MOD_NONE       = 0x00
    const val MOD_LEFT_CTRL  = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT   = 0x04
    const val MOD_LEFT_GUI   = 0x08   // Windows / Command key

    // ── Letter key scancodes (a=0x04 … z=0x1D) ─────────────────────────────
    const val KEY_A = 0x04
    const val KEY_B = 0x05
    const val KEY_C = 0x06   // Ctrl+C = Copy
    const val KEY_D = 0x07
    const val KEY_E = 0x08
    const val KEY_F = 0x09
    const val KEY_G = 0x0A
    const val KEY_H = 0x0B
    const val KEY_I = 0x0C
    const val KEY_J = 0x0D
    const val KEY_K = 0x0E
    const val KEY_L = 0x0F   // Win+L = Lock
    const val KEY_M = 0x10
    const val KEY_N = 0x11
    const val KEY_O = 0x12
    const val KEY_P = 0x13
    const val KEY_Q = 0x14
    const val KEY_R = 0x15   // Win+R = Run dialog
    const val KEY_S = 0x16
    const val KEY_T = 0x17   // Ctrl+Alt+T = Linux terminal
    const val KEY_U = 0x18
    const val KEY_V = 0x19   // Ctrl+V = Paste
    const val KEY_W = 0x1A
    const val KEY_X = 0x1B
    const val KEY_Y = 0x1C
    const val KEY_Z = 0x1D   // Ctrl+Z = Undo

    // ── Control / navigation keys ───────────────────────────────────────────
    const val KEY_ENTER  = 0x28
    const val KEY_ESCAPE = 0x29
    const val KEY_BSPACE = 0x2A
    const val KEY_TAB    = 0x2B
    const val KEY_SPACE  = 0x2C

    // ── Character → KeyInfo map ─────────────────────────────────────────────
    private val charMap: Map<Char, KeyInfo> = buildMap {

        // ── Lowercase a–z ──────────────────────────────────────────────────
        val letters = "abcdefghijklmnopqrstuvwxyz"
        letters.forEachIndexed { index, ch ->
            put(ch, KeyInfo(MOD_NONE, 0x04 + index))
        }

        // ── Uppercase A–Z ──────────────────────────────────────────────────
        letters.forEachIndexed { index, ch ->
            put(ch.uppercaseChar(), KeyInfo(MOD_LEFT_SHIFT, 0x04 + index))
        }

        // ── Digits 1–9, 0 ──────────────────────────────────────────────────
        put('1', KeyInfo(MOD_NONE, 0x1E))
        put('2', KeyInfo(MOD_NONE, 0x1F))
        put('3', KeyInfo(MOD_NONE, 0x20))
        put('4', KeyInfo(MOD_NONE, 0x21))
        put('5', KeyInfo(MOD_NONE, 0x22))
        put('6', KeyInfo(MOD_NONE, 0x23))
        put('7', KeyInfo(MOD_NONE, 0x24))
        put('8', KeyInfo(MOD_NONE, 0x25))
        put('9', KeyInfo(MOD_NONE, 0x26))
        put('0', KeyInfo(MOD_NONE, 0x27))

        // ── Punctuation (no shift) ─────────────────────────────────────────
        put(' ',  KeyInfo(MOD_NONE, 0x2C))  // Space
        put('-',  KeyInfo(MOD_NONE, 0x2D))  // Minus / hyphen
        put('=',  KeyInfo(MOD_NONE, 0x2E))  // Equals
        put('[',  KeyInfo(MOD_NONE, 0x2F))  // Left bracket
        put(']',  KeyInfo(MOD_NONE, 0x30))  // Right bracket
        put('\\', KeyInfo(MOD_NONE, 0x31))  // Backslash
        put(';',  KeyInfo(MOD_NONE, 0x33))  // Semicolon
        put('\'', KeyInfo(MOD_NONE, 0x34))  // Single quote
        put('`',  KeyInfo(MOD_NONE, 0x35))  // Grave accent
        put(',',  KeyInfo(MOD_NONE, 0x36))  // Comma
        put('.',  KeyInfo(MOD_NONE, 0x37))  // Period
        put('/',  KeyInfo(MOD_NONE, 0x38))  // Forward slash

        // ── Shift + digit → symbol ─────────────────────────────────────────
        put('!',  KeyInfo(MOD_LEFT_SHIFT, 0x1E))  // !  = Shift+1
        put('@',  KeyInfo(MOD_LEFT_SHIFT, 0x1F))  // @  = Shift+2
        put('#',  KeyInfo(MOD_LEFT_SHIFT, 0x20))  // #  = Shift+3
        put('$',  KeyInfo(MOD_LEFT_SHIFT, 0x21))  // $  = Shift+4
        put('%',  KeyInfo(MOD_LEFT_SHIFT, 0x22))  // %  = Shift+5
        put('^',  KeyInfo(MOD_LEFT_SHIFT, 0x23))  // ^  = Shift+6
        put('&',  KeyInfo(MOD_LEFT_SHIFT, 0x24))  // &  = Shift+7
        put('*',  KeyInfo(MOD_LEFT_SHIFT, 0x25))  // *  = Shift+8
        put('(',  KeyInfo(MOD_LEFT_SHIFT, 0x26))  // (  = Shift+9
        put(')',  KeyInfo(MOD_LEFT_SHIFT, 0x27))  // )  = Shift+0

        // ── Shift + punctuation → symbol ───────────────────────────────────
        put('_',  KeyInfo(MOD_LEFT_SHIFT, 0x2D))  // _  = Shift+-
        put('+',  KeyInfo(MOD_LEFT_SHIFT, 0x2E))  // +  = Shift+=
        put('{',  KeyInfo(MOD_LEFT_SHIFT, 0x2F))  // {  = Shift+[
        put('}',  KeyInfo(MOD_LEFT_SHIFT, 0x30))  // }  = Shift+]
        put('|',  KeyInfo(MOD_LEFT_SHIFT, 0x31))  // |  = Shift+\
        put(':',  KeyInfo(MOD_LEFT_SHIFT, 0x33))  // :  = Shift+;
        put('"',  KeyInfo(MOD_LEFT_SHIFT, 0x34))  // "  = Shift+'
        put('~',  KeyInfo(MOD_LEFT_SHIFT, 0x35))  // ~  = Shift+`
        put('<',  KeyInfo(MOD_LEFT_SHIFT, 0x36))  // <  = Shift+,
        put('>',  KeyInfo(MOD_LEFT_SHIFT, 0x37))  // >  = Shift+.
        put('?',  KeyInfo(MOD_LEFT_SHIFT, 0x38))  // ?  = Shift+/
    }

    /**
     * Returns the [KeyInfo] for the given character, or null if unmapped.
     */
    fun charToKeycode(char: Char): KeyInfo? = charMap[char]
}
