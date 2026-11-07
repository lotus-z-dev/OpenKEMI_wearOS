package com.supplices.kemimotowear.model

/**
 * Operating mode selected by the user to control the heated apparel.
 */
enum class VestMode {
    /** Direct connection: Wear OS Watch <-> Kemimoto Jacket */
    DIRECT,

    /** Gateway mode: Wear OS Watch <-> ESP32-C6 Gateway <-> Kemimoto Jacket */
    ESP32_GATEWAY
}
