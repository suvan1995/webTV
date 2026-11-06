package com.example.webtv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved website logins, encrypted with an AES key that lives in the Android Keystore
 * (the key never leaves the device's secure hardware / keystore daemon).
 */
object LoginStore {
    data class Login(val origin: String, val user: String, val pass: String, val time: Long)

    private const val PREFS = "logins"
    private const val ALIAS = "webtv_logins_v1"
    private const val TAG = "WebTvLogins"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun all(c: Context): List<Login> {
        val blob = p(c).getString("data", null) ?: return emptyList()
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            val json = String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            val a = JSONArray(json)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Login(o.getString("o"), o.optString("u"), o.optString("p"), o.optLong("t"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read saved logins", e)
            emptyList()
        }
    }

    @Synchronized
    private fun write(c: Context, list: List<Login>): Boolean {
        return try {
            val a = JSONArray()
            for (l in list) a.put(JSONObject().put("o", l.origin).put("u", l.user).put("p", l.pass).put("t", l.time))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val enc = cipher.doFinal(a.toString().toByteArray(Charsets.UTF_8))
            p(c).edit().putString("data", Base64.encodeToString(iv + enc, Base64.NO_WRAP)).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not save logins", e)
            false
        }
    }

    /** Most recently used first. */
    fun forOrigin(c: Context, origin: String): List<Login> =
        all(c).filter { it.origin == origin }.sortedByDescending { it.time }

    fun find(c: Context, origin: String, user: String): Login? =
        all(c).firstOrNull { it.origin == origin && it.user == user }

    fun save(c: Context, origin: String, user: String, pass: String): Boolean {
        val rest = all(c).filterNot { it.origin == origin && it.user == user }
        return write(c, rest + Login(origin, user, pass, System.currentTimeMillis()))
    }

    fun touch(c: Context, l: Login) {
        save(c, l.origin, l.user, l.pass)
    }

    fun remove(c: Context, l: Login) {
        write(c, all(c).filterNot { it.origin == l.origin && it.user == l.user })
    }

    fun clear(c: Context) { p(c).edit().remove("data").apply() }

    // Sites where the user chose "Never save"
    fun isNever(c: Context, origin: String) = p(c).getStringSet("never", emptySet())!!.contains(origin)
    fun setNever(c: Context, origin: String) {
        val s = HashSet(p(c).getStringSet("never", emptySet())!!)
        s.add(origin)
        p(c).edit().putStringSet("never", s).apply()
    }

    fun isEnabled(c: Context) = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("enabled", on).apply()
}
