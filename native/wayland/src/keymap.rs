//! Key mapping: Android `KeyEvent.getKeyCode()` → Linux evdev key code.
//!
//! Android numbers keys independently of Linux (KEYCODE_A is 29 for Android and
//! 30 for evdev), so the viewer cannot forward its raw codes. Only keys a
//! hardware keyboard on a phone or tablet realistically produces are mapped;
//! everything else is dropped instead of guessed. Unmapped keys are silent
//! no-ops so an unknown vendor key cannot inject a random keystroke.

pub fn android_to_evdev(keycode: u32) -> Option<u32> {
    Some(match keycode {
        // Letters.
        29 => 30,  // A
        30 => 48,  // B
        31 => 46,  // C
        32 => 32,  // D
        33 => 18,  // E
        34 => 33,  // F
        35 => 34,  // G
        36 => 35,  // H
        37 => 23,  // I
        38 => 36,  // J
        39 => 37,  // K
        40 => 38,  // L
        41 => 50,  // M
        42 => 49,  // N
        43 => 24,  // O
        44 => 25,  // P
        45 => 16,  // Q
        46 => 19,  // R
        47 => 31,  // S
        48 => 20,  // T
        49 => 22,  // U
        50 => 47,  // V
        51 => 17,  // W
        52 => 45,  // X
        53 => 21,  // Y
        54 => 44,  // Z

        // Digits and their shifted companions.
        7 => 11,   // 0
        8 => 2,    // 1
        9 => 3,    // 2
        10 => 4,   // 3
        11 => 5,   // 4
        12 => 6,   // 5
        13 => 7,   // 6
        14 => 8,   // 7
        15 => 9,   // 8
        16 => 10,  // 9
        77 => 3,   // AT -> '2' key
        81 => 13,  // PLUS -> '=' key
        17 => 55,  // STAR
        68 => 41,  // GRAVE
        69 => 12,  // MINUS
        70 => 13,  // EQUALS
        71 => 26,  // LEFT_BRACKET
        72 => 27,  // RIGHT_BRACKET
        73 => 43,  // BACKSLASH
        74 => 39,  // SEMICOLON
        75 => 40,  // APOSTROPHE
        76 => 53,  // SLASH
        55 => 51,  // COMMA
        56 => 52,  // PERIOD

        // Editing and navigation.
        61 => 15,   // TAB
        62 => 57,   // SPACE
        66 => 28,   // ENTER
        67 => 14,   // DEL (backspace)
        112 => 111, // FORWARD_DEL
        124 => 110, // INSERT
        122 => 102, // MOVE_HOME
        123 => 107, // MOVE_END
        92 => 104,  // PAGE_UP
        93 => 109,  // PAGE_DOWN
        19 => 103,  // DPAD_UP
        20 => 108,  // DPAD_DOWN
        21 => 105,  // DPAD_LEFT
        22 => 106,  // DPAD_RIGHT
        23 => 28,   // DPAD_CENTER

        // Modifiers and locks.
        59 => 42,   // SHIFT_LEFT
        60 => 54,   // SHIFT_RIGHT
        113 => 29,  // CTRL_LEFT
        114 => 97,  // CTRL_RIGHT
        57 => 56,   // ALT_LEFT
        58 => 100,  // ALT_RIGHT
        117 => 125, // META_LEFT
        118 => 126, // META_RIGHT
        115 => 58,  // CAPS_LOCK
        143 => 69,  // NUM_LOCK

        // Function keys.
        131 => 59,
        132 => 60,
        133 => 61,
        134 => 62,
        135 => 63,
        136 => 64,
        137 => 65,
        138 => 66,
        139 => 67,
        140 => 68,
        141 => 87,
        142 => 88,

        // Numeric keypad.
        144 => 82,
        145 => 79,
        146 => 80,
        147 => 81,
        148 => 75,
        149 => 76,
        150 => 77,
        151 => 71,
        152 => 72,
        153 => 73,
        154 => 98,
        155 => 55,
        156 => 74,
        157 => 78,
        158 => 83,
        160 => 96,

        // System and media.
        3 => 102,   // HOME
        4 => 158,   // BACK
        26 => 116,  // POWER
        24 => 115,  // VOLUME_UP
        25 => 114,  // VOLUME_DOWN
        164 => 113, // VOLUME_MUTE
        82 => 139,  // MENU
        84 => 217,  // SEARCH
        111 => 1,   // ESCAPE
        85 => 164,  // MEDIA_PLAY_PAUSE
        86 => 166,  // MEDIA_STOP
        87 => 163,  // MEDIA_NEXT
        88 => 165,  // MEDIA_PREVIOUS
        89 => 168,  // MEDIA_REWIND
        90 => 208,  // MEDIA_FAST_FORWARD

        _ => return None,
    })
}
