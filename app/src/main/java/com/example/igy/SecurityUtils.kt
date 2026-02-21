package com.example.igy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecurityUtils {
    private const val KEY_ALIAS = "igy_secure_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG = "SecurityUtils"

    private fun getKeyStore(): KeyStore? {
        return try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get KeyStore", e)
            null
        }
    }

    private fun getSecretKey(): SecretKey? {
        val ks = getKeyStore() ?: return null
        try {
            if (!ks.containsAlias(KEY_ALIAS)) {
                generateNewKey()
            }
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            return entry?.secretKey ?: generateNewKey()
        } catch (e: Exception) {
            Log.e(TAG, "Key retrieval failed, attempting reset", e)
            return generateNewKey()
        }
    }

    private fun generateNewKey(): SecretKey? {
        return try {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            )
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Critical: Key generation failed", e)
            null
        }
    }

    fun encrypt(data: String): String {
        if (data.isEmpty()) return ""
        try {
            val key = getSecretKey() ?: return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(data.toByteArray())
            val combined = iv + encryptedData
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            return ""
        }
    }

    fun decrypt(encryptedData: String): String {
        if (encryptedData.isEmpty()) return ""
        try {
            val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
            if (combined.size < 13) return ""
            
            val iv = combined.sliceArray(0 until 12)
            val data = combined.sliceArray(12 until combined.size)
            
            val key = getSecretKey() ?: return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            return String(cipher.doFinal(data))
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            return ""
        }
    }
}
