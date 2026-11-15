package com.nomadlouie.desktop.selkies

import android.view.KeyEvent

/**
 * Android key events to X11 keysyms, following the same rules as the Selkies
 * web client (lib/input.ts): a printable Latin-1 codepoint is its own keysym,
 * known codepoints above Latin-1 use their legacy keysym, anything else uses
 * the Unicode keysym range 0x01000000 | codepoint.
 */
object Keysyms {

    const val SHIFT_L = 0xFFE1
    const val CTRL_L = 0xFFE3
    const val ALT_L = 0xFFE9
    const val SUPER_L = 0xFFEB

    fun fromUnicode(cp: Int): Int? {
        if (cp <= 0) return null
        if (cp in 0x20..0xFF) return cp
        return 0x01000000 or cp
    }

    /** Keysym for a KeyEvent, or null when the key has no server-side meaning. */
    fun fromKeyEvent(event: KeyEvent): Int? {
        val meta = event.metaState
        val unicode = event.getUnicodeChar(meta)
        if (unicode > 0) return fromUnicode(unicode)
        // Dead key or no character: try the base character.
        val base = event.getUnicodeChar(0)
        if (base > 0) return fromUnicode(base)
        return BY_KEYCODE[event.keyCode]
    }

    private val BY_KEYCODE = mapOf(
        KeyEvent.KEYCODE_ENTER to 0xFF0D,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 0xFF8D,
        KeyEvent.KEYCODE_TAB to 0xFF09,
        KeyEvent.KEYCODE_DEL to 0xFF08,          // backspace
        KeyEvent.KEYCODE_FORWARD_DEL to 0xFFFF,  // delete
        KeyEvent.KEYCODE_ESCAPE to 0xFF1B,
        KeyEvent.KEYCODE_SPACE to 0x0020,
        KeyEvent.KEYCODE_SHIFT_LEFT to 0xFFE1,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xFFE2,
        KeyEvent.KEYCODE_CTRL_LEFT to 0xFFE3,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xFFE4,
        KeyEvent.KEYCODE_ALT_LEFT to 0xFFE9,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xFFEA,
        KeyEvent.KEYCODE_META_LEFT to 0xFFEB,
        KeyEvent.KEYCODE_META_RIGHT to 0xFFEC,
        KeyEvent.KEYCODE_CAPS_LOCK to 0xFFE5,
        KeyEvent.KEYCODE_SCROLL_LOCK to 0xFF14,
        KeyEvent.KEYCODE_NUM_LOCK to 0xFF7F,
        KeyEvent.KEYCODE_PAUSE to 0xFF13,
        KeyEvent.KEYCODE_BREAK to 0xFF6B,
        KeyEvent.KEYCODE_PRINTSCREEN to 0xFF61,
        KeyEvent.KEYCODE_INSERT to 0xFF63,
        KeyEvent.KEYCODE_HOME to 0xFF50,
        KeyEvent.KEYCODE_MOVE_HOME to 0xFF50,
        KeyEvent.KEYCODE_MOVE_END to 0xFF57,
        KeyEvent.KEYCODE_PAGE_UP to 0xFF55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xFF56,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xFF51,
        KeyEvent.KEYCODE_DPAD_UP to 0xFF52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xFF53,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xFF54,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT to 0xFF51,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP to 0xFF52,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT to 0xFF53,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN to 0xFF54,
        KeyEvent.KEYCODE_F1 to 0xFFBE,
        KeyEvent.KEYCODE_F2 to 0xFFBF,
        KeyEvent.KEYCODE_F3 to 0xFFC0,
        KeyEvent.KEYCODE_F4 to 0xFFC1,
        KeyEvent.KEYCODE_F5 to 0xFFC2,
        KeyEvent.KEYCODE_F6 to 0xFFC3,
        KeyEvent.KEYCODE_F7 to 0xFFC4,
        KeyEvent.KEYCODE_F8 to 0xFFC5,
        KeyEvent.KEYCODE_F9 to 0xFFC6,
        KeyEvent.KEYCODE_F10 to 0xFFC7,
        KeyEvent.KEYCODE_F11 to 0xFFC8,
        KeyEvent.KEYCODE_F12 to 0xFFC9,
        KeyEvent.KEYCODE_NUMPAD_0 to 0xFFB0,
        KeyEvent.KEYCODE_NUMPAD_1 to 0xFFB1,
        KeyEvent.KEYCODE_NUMPAD_2 to 0xFFB2,
        KeyEvent.KEYCODE_NUMPAD_3 to 0xFFB3,
        KeyEvent.KEYCODE_NUMPAD_4 to 0xFFB4,
        KeyEvent.KEYCODE_NUMPAD_5 to 0xFFB5,
        KeyEvent.KEYCODE_NUMPAD_6 to 0xFFB6,
        KeyEvent.KEYCODE_NUMPAD_7 to 0xFFB7,
        KeyEvent.KEYCODE_NUMPAD_8 to 0xFFB8,
        KeyEvent.KEYCODE_NUMPAD_9 to 0xFFB9,
        KeyEvent.KEYCODE_NUMPAD_ADD to 0xFFAB,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 0xFFAD,
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 0xFFAA,
        KeyEvent.KEYCODE_NUMPAD_DIVIDE to 0xFFAF,
        KeyEvent.KEYCODE_NUMPAD_DOT to 0xFFAE,
        KeyEvent.KEYCODE_NUMPAD_COMMA to 0xFFAC,
        KeyEvent.KEYCODE_MENU to 0xFF67,
        KeyEvent.KEYCODE_VOLUME_MUTE to 0x1008FF12,
        KeyEvent.KEYCODE_VOLUME_UP to 0x1008FF13,
        KeyEvent.KEYCODE_VOLUME_DOWN to 0x1008FF11,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 0x1008FF14,
        KeyEvent.KEYCODE_MEDIA_STOP to 0x1008FF15,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to 0x1008FF16,
        KeyEvent.KEYCODE_MEDIA_NEXT to 0x1008FF17,
        KeyEvent.KEYCODE_MEDIA_EJECT to 0x1008FF2C,
    )
}
