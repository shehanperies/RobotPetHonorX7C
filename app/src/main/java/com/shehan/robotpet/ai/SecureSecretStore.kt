package com.shehan.robotpet.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("robot_pet_secrets", Context.MODE_PRIVATE)
    private val alias = "robot_pet_gemini_key_v1"

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun saveGeminiKey(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString("gemini_key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("gemini_key_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun geminiKey(): String {
        val iv = prefs.getString("gemini_key_iv", null) ?: return ""
        val data = prefs.getString("gemini_key_data", null) ?: return ""

        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)),
                Charsets.UTF_8
            )
        }.getOrDefault("")
    }

    fun hasGeminiKey(): Boolean = geminiKey().isNotBlank()

    fun clearGeminiKey() {
        prefs.edit()
            .remove("gemini_key_iv")
            .remove("gemini_key_data")
            .apply()
    }
}
