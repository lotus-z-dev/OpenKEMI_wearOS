package com.supplices.kemimotowear.ble

import java.util.UUID

/**
 * Low-level binary protocol implementation for Kemimoto Bluetooth Low Energy (BLE) heated apparel.
 * 
 * AUDIT & PROTOCOL DOCUMENTATION:
 * - Reverse-Engineered from official Kemimoto firmware and Android client (parseHeatedData / sendCommand).
 * - Transport: Bluetooth GATT over BLE (Service UUID 0xFF00).
 * - Commands: Dispatched via characteristic 0xFF02 using WRITE_TYPE_NO_RESPONSE for rapid execution.
 * - Telemetry: Emitted asynchronously via notifications on characteristic 0xFF01.
 * - Frame Format: Fixed 14-byte structure starting with magic byte 0xAA and ending with delimiter 0x0D.
 */
object KemimotoProtocol {

    /** Standard 16-bit UUID alias expanded into 128-bit Bluetooth SIG base UUID */
    val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    // Protocol Frame Constants
    private const val HEADER_BYTE: Byte = 0xAA.toByte() // Synchronization header
    private const val LENGTH_BYTE: Byte = 0x0E.toByte() // Total frame length (14 bytes)
    private const val CMD_WRITE: Byte = 0x01.toByte()   // Operation: Write control settings
    private const val DEVICE_VEST: Byte = 0x01.toByte() // Device Target: Heated vest / jacket
    private const val END_BYTE: Byte = 0x0D.toByte()    // Frame termination delimiter

    /**
     * Forges a 14-byte control packet for the heated vest hardware controller.
     *
     * Frame Layout:
     * [0]  0xAA : Synchronization Header
     * [1]  0x0E : Frame Length (14 bytes)
     * [2]  0x01 : Command Type (Control Write)
     * [3]  0x01 : Target Device (0x01 = Vest)
     * [4]  0x00 : Reserved / Sequence
     * [5]  0x00..0x03 : Zone 1 Level (Neck / Upper Spine)
     * [6]  0x00..0x03 : Zone 2 Level (Back / Shoulder Blades)
     * [7]  0x00..0x03 : Zone 3 Level (Belly / Lower Abdomen)
     * [8]  0x00 / 0x01: Stealth Mode LED switch (0x01 = LED Off, 0x00 = LED On)
     * [9..12] 0xFF FF FF FF : Fixed padding bytes
     * [13] 0x0D : End of Frame Delimiter
     *
     * @param neck Heating intensity level for neck (0 = Off, 1 = Low/Blue, 2 = Mid/White, 3 = High/Red)
     * @param back Heating intensity level for back (0..3)
     * @param belly Heating intensity level for belly (0..3)
     * @param isStealthMode When true, silences physical LED buttons on the garment (0x01)
     * @return 14-byte raw packet ready for BLE GATT characteristic write
     */
    fun buildCommandPacket(
        neck: Int,
        back: Int,
        belly: Int,
        isStealthMode: Boolean
    ): ByteArray {
        val clampedNeck = neck.coerceIn(0, 3).toByte()
        val clampedBack = back.coerceIn(0, 3).toByte()
        val clampedBelly = belly.coerceIn(0, 3).toByte()
        val lampSwitch = (if (isStealthMode) 0x01 else 0x00).toByte()

        return byteArrayOf(
            HEADER_BYTE,          // [0] Magic header 0xAA
            LENGTH_BYTE,          // [1] Frame length 0x0E (14 bytes)
            CMD_WRITE,            // [2] Command code: write (0x01)
            DEVICE_VEST,          // [3] Hardware class: vest (0x01)
            0x00.toByte(),        // [4] Sub-command / reserved
            clampedNeck,          // [5] Zone 1: Neck level (0-3)
            clampedBack,          // [6] Zone 2: Back level (0-3)
            clampedBelly,         // [7] Zone 3: Belly level (0-3)
            lampSwitch,           // [8] LED stealth mode (0x00=on, 0x01=off)
            0xFF.toByte(),        // [9] Padding
            0xFF.toByte(),        // [10] Padding
            0xFF.toByte(),        // [11] Padding
            0xFF.toByte(),        // [12] Padding
            END_BYTE              // [13] Frame delimiter 0x0D
        )
    }

    /**
     * Parsed telemetry decoded from incoming peripheral notification packets.
     *
     * @property batteryPercent Peripheral battery charge level (0-100%)
     * @property timerRemainingSeconds Remaining auto-shutoff countdown in seconds
     */
    data class TelemetryResult(
        val batteryPercent: Int,
        val timerRemainingSeconds: Int
    )

    /**
     * Decodes asynchronous notification payload emitted by the garment on characteristic 0xFF01.
     *
     * In the vendor protocol:
     * - Byte [0] must match 0xAA header.
     * - Byte [10] contains battery state of charge (0..100%).
     * - Bytes [11..12] contain hardware countdown timer in big-endian seconds (MSB, LSB).
     *
     * @param payload Raw byte array received from BLE notification callback
     * @return TelemetryResult containing battery and timer values, or null if malformed
     */
    fun parseTelemetry(payload: ByteArray): TelemetryResult? {
        if (payload.size < 13) return null

        val header = payload[0]
        if (header != HEADER_BYTE) return null

        val battery = (payload[10].toInt() and 0xFF).coerceIn(0, 100)
        val timerMsb = payload[11].toInt() and 0xFF
        val timerLsb = payload[12].toInt() and 0xFF
        val timerSeconds = (timerMsb shl 8) or timerLsb

        return TelemetryResult(
            batteryPercent = battery,
            timerRemainingSeconds = timerSeconds
        )
    }
}
