package com.zlight.sendtosmb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zlight.sendtosmb.ui.UiFile
import com.zlight.sendtosmb.ui.UiTransfer
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps terminal transfer records in private, backup-excluded, encrypted preferences. */
class TransferStore(context: Context) {
    private val prefs = context.getSharedPreferences("transfer_history", Context.MODE_PRIVATE)
    private val alias = "sendtosmb.transfers.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): List<UiTransfer> {
        val payload = prefs.getString("payload", null) ?: return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        val array = JSONArray(String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val source = item.optJSONObject("source")?.let {
                UiFile(it.getString("name"), it.getString("path"), it.getBoolean("directory"),
                    it.optLong("size"), it.optLong("modified"))
            }
            UiTransfer(
                id = item.getString("id"),
                name = item.getString("name"),
                direction = item.getString("direction"),
                done = item.optLong("done"),
                total = item.optLong("total"),
                status = item.optString("status", "completed"),
                error = item.optString("error").takeIf { it.isNotBlank() },
                createdMillis = item.optLong("created", System.currentTimeMillis()),
                sourceFile = source,
                profileId = item.optString("profileId").takeIf { it.isNotBlank() },
            )
        }
    }

    fun write(transfers: List<UiTransfer>) {
        val array = JSONArray()
        transfers.filterNot { it.status == "running" || it.status == "queued" }
            .sortedBy { it.createdMillis }.takeLast(MAX_HISTORY).forEach { transfer ->
                val item = JSONObject()
                    .put("id", transfer.id)
                    .put("name", transfer.name)
                    .put("direction", transfer.direction)
                    .put("done", transfer.done)
                    .put("total", transfer.total)
                    .put("status", transfer.status)
                    .put("error", transfer.error ?: "")
                    .put("created", transfer.createdMillis)
                    .put("profileId", transfer.profileId ?: "")
                transfer.sourceFile?.let { source ->
                    item.put("source", JSONObject()
                        .put("name", source.name)
                        .put("path", source.path)
                        .put("directory", source.isDirectory)
                        .put("size", source.size)
                        .put("modified", source.modifiedMillis))
                }
                array.put(item)
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit()
            .putString("payload", Base64.encodeToString(cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "传输记录保存失败" }
    }

    private companion object { const val MAX_HISTORY = 200 }
}
