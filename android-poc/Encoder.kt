package com.shj.saxble

import java.util.UUID

/**
 * SAX-D encoder BLE facts, mirrored from the iOS app's Encoder.swift.
 * These are hard-won and hardware-verified — do not "simplify" them.
 */
object Encoder {
    /** Microchip/ISSC "transparent UART" service (NOT Nordic UART). */
    val SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")

    /** Write+Notify characteristic used for both TX (commands) and RX (replies). */
    val WRITE_NOTIFY_CHAR: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")

    /** Client Characteristic Config Descriptor — Android needs this written to turn on notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** CR+LF is REQUIRED (LF-only rejected, CR-only gets no reply). */
    const val LINE_ENDING = "\r\n"

    /** Post-login banner substring. */
    const val LOGIN_MARKER = "Welcome to Shire"

    /** The encoder drops the last byte of a bulk write, so paced sends go one
     *  byte at a time with this gap. Re-tune on real Android hardware. */
    const val SLOW_BYTE_GAP_MS = 35L

    /** Tried in order on each password prompt (will become a stored list later). */
    val passwordCandidates = listOf("MMSmms659", "studio3")
}
