package com.lpecom.gemglasses.bobai.sync

import kotlinx.serialization.Serializable

/** This phone's pairing with the office console. The key is a secret: never log it. */
@Serializable
data class DevicePairing(val deviceId: String, val deviceKey: String, val userName: String)

interface DeviceKeyStore {
    fun get(): DevicePairing?
    fun save(pairing: DevicePairing)
    /** Forgets the key and the removed flag (the user unpaired on purpose). */
    fun clear()
    /**
     * The console removed the phone whose key is [deviceId]: drop the key and remember it, so voice
     * does not fall back to the shared secret. Does nothing if a different pairing is stored now
     * (a late 401 for an old key must not delete a fresh re-pair).
     */
    fun markRemoved(deviceId: String)
    fun wasRemoved(): Boolean
}
