package com.zlight.sendtosmb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zlight.sendtosmb.ui.UiProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private, backup-excluded preferences; the complete profile payload is AES-GCM encrypted. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("connections", Context.MODE_PRIVATE)
    private val alias = "sendtosmb.profiles.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): Pair<List<UiProfile>, String?> {
        val payload = prefs.getString("payload", null) ?: return emptyList<UiProfile>() to null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        val root = JSONObject(String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8))
        val array = root.getJSONArray("profiles")
        val profiles = (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            UiProfile(p.getString("id"), p.getString("name"), p.getString("url"),
                p.optString("username"), p.optString("password"), p.optString("domain"), p.optBoolean("remember", true))
        }
        return profiles to root.optString("current").takeIf { it.isNotBlank() }
    }

    fun write(profiles: List<UiProfile>, current: String?) {
        val array = JSONArray()
        profiles.forEach { p -> array.put(JSONObject().put("id", p.id).put("name", p.name).put("url", p.url)
            .put("username", p.username).put("password", if (p.rememberPassword) p.password else "")
            .put("domain", p.domain).put("remember", p.rememberPassword)) }
        val content = JSONObject().put("profiles", array).put("current", current ?: "").toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("payload", Base64.encodeToString(cipher.doFinal(content.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "连接配置保存失败" }
    }
}
