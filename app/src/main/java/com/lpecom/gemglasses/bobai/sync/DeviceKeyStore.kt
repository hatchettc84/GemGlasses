package com.lpecom.gemglasses.bobai.sync

import kotlinx.serialization.Serializable

/** This phone's pairing with the office console. The key is a secret: never log it. */
@Serializable
data class DevicePairing(val deviceId: String, val deviceKey: String, val userName: String)

interface DeviceKeyStore {
    fun get(): DevicePairing?
    fun save(pairing: DevicePairing)
    fun clear()
}
