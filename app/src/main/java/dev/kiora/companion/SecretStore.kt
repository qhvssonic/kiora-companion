package dev.kiora.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists credentials encrypted with a key that never leaves Android Keystore. */
internal class SecretStore(
    context: Context,
    preferencesName: String = "companion_secrets",
    private val keyAlias: String = "dev.kiora.companion.credentials",
) {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun apiKey(providerId: String): String = read("api_key_$providerId")

    fun bearerToken(): String = read("a2a_token")

    fun a2aToken(connectionId: String): String = read("a2a_token_$connectionId")

    fun saveApiKey(providerId: String, apiKey: String) {
        if (providerId == "litert") return
        val editor = prefs.edit()
        if (apiKey.isBlank()) editor.remove("api_key_$providerId")
        else editor.putString("api_key_$providerId", encrypt(apiKey))
        check(editor.commit()) { "无法保存模型密钥" }
    }

    fun saveA2aToken(connectionId: String, token: String) {
        require(connectionId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "无效的连接 ID" }
        val editor = prefs.edit()
        if (token.isBlank()) editor.remove("a2a_token_$connectionId")
        else editor.putString("a2a_token_$connectionId", encrypt(token))
        check(editor.commit()) { "无法保存 A2A 令牌" }
    }

    fun save(providerId: String, apiKey: String, bearerToken: String) {
        val encryptedKey = if (providerId == "litert") null
            else apiKey.takeIf(String::isNotBlank)?.let(::encrypt)
        val encryptedToken = bearerToken.takeIf(String::isNotBlank)?.let(::encrypt)
        val editor = prefs.edit()
        if (providerId != "litert") {
            if (encryptedKey == null) editor.remove("api_key_$providerId")
            else editor.putString("api_key_$providerId", encryptedKey)
        }
        if (encryptedToken == null) editor.remove("a2a_token")
        else editor.putString("a2a_token", encryptedToken)
        check(editor.commit()) { "无法保存密钥" }
    }

    private fun read(name: String): String {
        val encoded = prefs.getString(name, null) ?: return ""
        try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("已保存的密钥无法读取，请重新输入", error)
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }
}
