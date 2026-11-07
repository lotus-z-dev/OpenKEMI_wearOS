package com.supplices.kemimotowear.ble

import java.util.UUID

/**
 * ==============================================================================================
 * BLE GATT Protocol Implementation for Kemimoto ESP32-C6 Standalone Gateway
 * ==============================================================================================
 *
 * ARCHITECTURAL NOTICE:
 * To preserve isolation and prevent RF collisions or unauthorized control, each individual
 * gateway deployment must utilize distinct 128-bit UUIDs generated via uuidgen.
 *
 */
object GatewayProtocol {

    /** Dedicated 128-bit Service and Characteristic UUIDs for ESP32-C6 Gateway */
    val SERVICE_UUID: UUID = UUID.fromString("4335c7a4-0d9d-4f6f-ac85-08ba663a2ef2")
    val CHAR_COMMAND_UUID: UUID = UUID.fromString("a63861d5-41da-4ed8-abb9-64f60832698e")
    val CHAR_TELEMETRY_UUID: UUID = UUID.fromString("bb50c63e-8c39-48ad-92b8-cd4a2c80e1f8")
    val CHAR_GAUGE_UUID: UUID = UUID.fromString("8a8af420-4ed7-4eef-a517-22bcd703259e")
    val CHAR_CADENCE_UUID: UUID = UUID.fromString("9d8693dc-fd34-4023-9616-bd133df44839")
    val CHAR_RESET_UUID: UUID = UUID.fromString("be72f31c-b85b-4f13-99c7-64628d7aec1d")
    val CHAR_MODE_UUID: UUID = UUID.fromString("458d6a01-010d-48e7-a08a-bf3f848b9b84")

    // Movement / Cadence states
    const val CADENCE_STATIONARY: Byte = 0x00
    const val CADENCE_WALKING: Byte = 0x01
    const val CADENCE_RUNNING: Byte = 0x02
    const val CADENCE_VEHICLE: Byte = 0x03

    // Battery Reset Command value
    const val CMD_RESET_BATTERY: Byte = 0x01
    // Clear Jacket MAC Bonding command value
    const val CMD_CLEAR_JACKET_MAC: Byte = 0xFF.toByte()

    /**
     * Decoded representation of the 5-byte virtual fuel gauge notification.
     *
     * @property virtualPercent Estimated remaining battery charge (0-100%)
     * @property remainingMinutes Estimated runtime under active load in minutes
     * @property consumedMah Total energy drawn from battery in mAh
     */
    data class GaugeResult(
        val virtualPercent: Int,
        val remainingMinutes: Int,
        val consumedMah: Int
    )

    /**
     * Decodes the 5-byte virtual fuel gauge payload emitted on CHAR_GAUGE_UUID.
     *
     * Format:
     * - Byte 0: SoC % (0..100)
     * - Bytes 1-2: Remaining Minutes (uint16 Little-Endian)
     * - Bytes 3-4: Consumed mAh (uint16 Little-Endian)
     */
    fun parseGaugePayload(payload: ByteArray): GaugeResult? {
        if (payload.size < 5) return null

        val percent = (payload[0].toInt() and 0xFF).coerceIn(0, 100)
        val minsLsb = payload[1].toInt() and 0xFF
        val minsMsb = payload[2].toInt() and 0xFF
        val remainingMinutes = (minsMsb shl 8) or minsLsb

        val mahLsb = payload[3].toInt() and 0xFF
        val mahMsb = payload[4].toInt() and 0xFF
        val consumedMah = (mahMsb shl 8) or mahLsb

        return GaugeResult(
            virtualPercent = percent,
            remainingMinutes = remainingMinutes,
            consumedMah = consumedMah
        )
    }

    /**
     * Builds a 1-byte packet to update user cadence/movement state.
     */
    fun buildCadencePacket(state: Int): ByteArray {
        return byteArrayOf(state.toByte())
    }

    /**
     * Builds a 1-byte command to reset the battery fuel gauge to 100%.
     */
    fun buildResetPacket(): ByteArray {
        return byteArrayOf(CMD_RESET_BATTERY)
    }

    /**
     * Builds a 1-byte command to clear the bonded jacket MAC on the ESP32 gateway.
     */
    fun buildClearJacketMacPacket(): ByteArray {
        return byteArrayOf(CMD_CLEAR_JACKET_MAC)
    }

    /**
     * Builds a 1-byte command to enable/disable Chopped Low Mode (5 min ON / 5 min OFF).
     */
    fun buildModePacket(isChoppedLow: Boolean): ByteArray {
        return byteArrayOf(if (isChoppedLow) 0x01 else 0x00)
    }
}
