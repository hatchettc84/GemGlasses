package com.lpecom.gemglasses.bobai.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the pairing in app-private preferences, encrypted with an AES-GCM key
 * that lives in the Android Keystore and never leaves it.
 */
@Singleton
class KeystoreDeviceKeyStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : DeviceKeyStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): DevicePairing? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            val plain = cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
            json.decodeFromString(DevicePairing.serializer(), plain.decodeToString())
        }.onFailure {
            Log.w(TAG, "stored pairing unreadable; clearing it")
            prefs.edit().remove(KEY_BLOB).commit()
        }.getOrNull()
    }

    override fun save(pairing: DevicePairing) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(json.encodeToString(DevicePairing.serializer(), pairing).encodeToByteArray())
        prefs.edit()
            .putString(KEY_BLOB, Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP))
            .remove(KEY_REMOVED)
            .commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).remove(KEY_REMOVED).commit()
    }

    override fun markRemoved() {
        prefs.edit().remove(KEY_BLOB).putBoolean(KEY_REMOVED, true).commit()
    }

    override fun wasRemoved(): Boolean = prefs.getBoolean(KEY_REMOVED, false)

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "bobai_device"
        const val KEY_BLOB = "pairing"
        const val KEY_REMOVED = "removed"
        const val ALIAS = "bobai_device_pairing"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG = "DeviceKeyStore"
    }
}
