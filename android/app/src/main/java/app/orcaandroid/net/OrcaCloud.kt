package app.orcaandroid.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Orca Cloud account, ported from upstream's OrcaCloudServiceAgent (src-orca
 * src/slic3r/Utils/OrcaCloudServiceAgent.cpp): OAuth 2.0 PKCE against Orca's Supabase auth
 * (GoTrue), with the sign-in page at cloud.orcaslicer.com/orcaslicer-login.
 *
 * The session (refresh token and user) is kept like the desktop's encrypted token file, here
 * AES-GCM with a key in the Android Keystore. The access token is never stored; it is refreshed
 * at start, like the desktop. All network calls block; use them off the main thread.
 */
object OrcaCloud {
    private const val TAG = "OrcaCloud"

    // OrcaCloudServiceAgent.cpp:85-89 (the publishable key is public, it is in upstream's source).
    const val AUTH_URL = "https://auth.orcaslicer.com"
    const val API_URL = "https://api.orcaslicer.com"
    const val CLOUD_URL = "https://cloud.orcaslicer.com"
    private const val PUB_KEY = "sb_publishable_lvVe_whOi80SU9BPSxM1kA_tbt9AbR_"

    const val LOOPBACK_PORT = 41172 // auth_constants::LOOPBACK_PORT, then +1, +2
    const val LOOPBACK_PATH = "/callback"
    private const val TOKEN_PATH = "/auth/v1/token"
    private const val LOGOUT_PATH = "/auth/v1/logout"

    /** Upstream TOKEN_REFRESH_SKEW: refresh when the access token expires within 15 minutes. */
    private const val REFRESH_SKEW_S = 900L
    private const val MAX_SYNC_PAYLOAD = 1_048_576 // ORCA_SYNC_MAX_PAYLOAD_SIZE

    private const val KEY_ALIAS = "orca_cloud_session"

    data class User(val id: String, val userName: String, val nickname: String, val avatar: String)

    data class Plugin(val id: String, val name: String, val version: String, val author: String, val types: List<String>)

    data class Pkce(val verifier: String, val challenge: String, val state: String, val port: Int) {
        val redirect get() = "http://localhost:$port$LOOPBACK_PATH"
    }

    private class Session(val access: String, val refresh: String, val user: User, val expiresAt: Long)

    private val lock = Any()
    private var session: Session? = null
    private var loaded = false
    private var pkce: Pkce? = null

    fun loginUrl(language: String = "en") = "$CLOUD_URL/orcaslicer-login?lang=$language"

    /** The signed-in user, or null. The first call reads the stored session and refreshes it. */
    fun user(context: Context): User? {
        load(context)
        return synchronized(lock) { session?.user }
    }

    /**
     * The page's `login_config` (build_login_cmd): a fresh PKCE pair and state, with the loopback
     * [port] the browser sign-in (Google, GitHub, ...) redirects to.
     */
    fun loginConfig(port: Int): String {
        val verifier = b64url(randomBytes(32))
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomBytes(16).joinToString("") { "%02x".format(it) }
        val p = Pkce(verifier, challenge, state, port)
        synchronized(lock) { pkce = p }
        return JSONObject()
            .put("action", "login_config")
            .put("backend_url", AUTH_URL)
            .put("apikey", PUB_KEY)
            .put("pkce", JSONObject()
                .put("code_challenge", p.challenge)
                .put("code_challenge_method", "S256")
                .put("state", p.state)
                .put("redirect_uri", p.redirect)
                .put("code_verifier", p.verifier)
                .put("loopback_port", p.port))
            .toString()
    }

    /** Completes a browser sign-in: exchanges the code from the loopback redirect (exchange_auth_code). */
    fun loginWithCode(context: Context, code: String, state: String): User {
        val p = synchronized(lock) { pkce } ?: throw IOException("Orca Cloud sign-in expired, please try again")
        if (state != p.state) throw IOException("Orca Cloud sign-in failed (state mismatch)")
        val body = JSONObject().put("auth_code", code).put("code_verifier", p.verifier).toString()
        val (http, resp) = post("$AUTH_URL$TOKEN_PATH?grant_type=pkce", body, bearer = null)
        if (http !in 200..299) throw IOException("Orca Cloud sign-in failed (HTTP $http)")
        return setSession(context, JSONObject(resp))
    }

    /**
     * Completes a sign-in from the page's `user_login` message: either a code to exchange, or the
     * tokens of an e-mail sign-in done by the page itself (change_user).
     */
    fun loginWithMessage(context: Context, data: JSONObject): User {
        val state = data.optString("state")
        val code = data.optString("code")
        if (code.isNotEmpty()) return loginWithCode(context, code, state)
        val expected = synchronized(lock) { pkce?.state }.orEmpty()
        if (expected.isNotEmpty() && state != expected) throw IOException("Orca Cloud sign-in failed (state mismatch)")
        return setSession(context, data)
    }

    fun logout(context: Context) {
        val s = synchronized(lock) { session }
        if (s != null && s.access.isNotEmpty()) {
            val body = JSONObject().put("refresh_token", s.refresh).toString()
            runCatching { post("$AUTH_URL$LOGOUT_PATH?scope=local", body, bearer = s.access) }
                .onFailure { Log.w(TAG, "logout request failed: ${it.message}") }
        }
        synchronized(lock) {
            session = null
            pkce = null
        }
        sessionFile(context).delete()
    }

    /** Reachability of the Orca Cloud API (connect_server: /api/v1/health). */
    fun healthy(): Boolean = runCatching { request("GET", "$API_URL/api/v1/health", null, bearer = null).first in 200..299 }.getOrDefault(false)

    /** The plugins this account subscribed to on Orca Cloud (fetch_subscribed_manifests_into_descriptors). */
    fun subscribedPlugins(context: Context): List<Plugin> {
        val root = JSONObject(apiGet(context, "/api/v1/plugins/subscriptions"))
        val data = root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            // parse_cloud_author / parse_cloud_display_types ("type_label" wins over "type").
            val author = item.optString("creator_display_name").takeUnless { it.isEmpty() || it.isUuid() }
                ?: item.optString("creator_username").takeUnless { it.isUuid() }.orEmpty()
            val types = listOf("type_label", "type").firstNotNullOfOrNull { key ->
                val list = item.optJSONArray(key)?.let { t -> (0 until t.length()).mapNotNull { t.opt(it) as? String } }
                    ?: listOfNotNull(item.opt(key) as? String)
                list.filter { it.isNotEmpty() }.distinct().ifEmpty { null }
            }.orEmpty()
            Plugin(item.optString("uuid").ifEmpty { item.optString("id") }, item.optString("name"), item.optString("version"), author, types)
        }
    }

    /**
     * Downloads a subscribed plugin's package to the cache (get_plugin_download_url +
     * CloudPluginService::download_cloud_plugin): `POST /api/v1/plugins/download?os=linux&arch=arm64`
     * returns a download link; the body is a wheel when it starts with "PK", else a single .py file.
     * Returns the file for [app.orcaandroid.plugins.Plugins.install]. Wheels with native code for
     * other platforms are not usable on Android.
     */
    fun downloadPlugin(context: Context, plugin: Plugin): File {
        val request = JSONObject().put("data", JSONArray().put(JSONObject().put("plugin_id", plugin.id)))
        val (http, response) = apiCall(context, "POST", "/api/v1/plugins/download?os=linux&arch=arm64", request.toString())
        if (http != 200) throw IOException("Orca Cloud: HTTP $http")
        val root = JSONObject(response)
        val link = root.optJSONArray("data")?.optJSONObject(0)?.optString("download_link").orEmpty()
        if (link.isEmpty()) {
            val reason = root.optJSONArray("not_found")?.optJSONObject(0)?.optString("reason").orEmpty()
            throw IOException(if (reason.isNotEmpty()) "Plugin download not found: $reason" else "No download link for this plugin")
        }
        val c = URL(link).openConnection() as HttpURLConnection
        val body = try {
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            if (c.responseCode >= 400) throw IOException("Plugin download: HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        if (body.isEmpty()) throw IOException("Plugin download returned empty data")
        val wheel = body.size >= 4 && body[0] == 'P'.code.toByte() && body[1] == 'K'.code.toByte() && body[2] == 3.toByte() && body[3] == 4.toByte()
        val dir = File(context.cacheDir, "plugin-downloads").apply { mkdirs() }
        val file = File(dir, plugin.id.filter { it.isLetterOrDigit() || it == '-' } + if (wheel) ".whl" else ".py")
        file.writeBytes(body)
        return file
    }

    /**
     * All presets synced to this account, as { name: { key: value } } like the desktop's
     * get_user_presets(): a full pull (no cursor), with setting_id, user_id and updated_time added
     * where the content lacks them (PresetCollection::load_user_preset needs them).
     */
    fun cloudPresets(context: Context): JSONObject {
        val root = JSONObject(apiGet(context, "/api/v1/sync/pull"))
        val userId = user(context)?.id.orEmpty()
        val out = JSONObject()
        val upserts = root.optJSONArray("upserts") ?: JSONArray()
        for (i in 0 until upserts.length()) {
            val item = upserts.optJSONObject(i) ?: continue
            val content = item.optJSONObject("content") ?: JSONObject()
            val values = JSONObject()
            for (key in content.keys()) values.put(key, content.get(key).let { it as? String ?: it.toString() })
            if (!values.has("setting_id")) values.put("setting_id", item.optString("id"))
            if (!values.has("user_id")) values.put("user_id", userId)
            if (!values.has("updated_time")) values.put("updated_time", item.optLong("updated_time").toString())
            val name = content.optString("name").ifEmpty { item.optString("name").ifEmpty { item.optString("id") } }
            if (name.isNotEmpty()) out.put(name, values)
        }
        return out
    }

    /** An authorised GET on the Orca Cloud API; refreshes the token first if needed, and once more on a 401. */
    fun apiGet(context: Context, path: String): String {
        val (http, body) = apiCall(context, "GET", path, null)
        if (http !in 200..299) throw IOException("Orca Cloud: HTTP $http")
        return body
    }

    /** An authorised request on the Orca Cloud API; returns (HTTP status, body). */
    private fun apiCall(context: Context, method: String, path: String, body: String?): Pair<Int, String> {
        load(context)
        ensureFresh(context)
        var token = synchronized(lock) { session?.access } ?: throw IOException("Sign in to Orca Cloud first")
        var result = request(method, "$API_URL$path", body, bearer = token)
        if (result.first == 401 && refresh(context)) {
            token = synchronized(lock) { session?.access }.orEmpty()
            result = request(method, "$API_URL$path", body, bearer = token)
        }
        return result
    }

    /** The result of a preset upload: the HTTP status, and the cloud's new updated_time on success. */
    data class PushResult(val http: Int, val updatedTime: Long, val message: String)

    /**
     * Uploads one preset (sync_push): `POST /api/v1/sync/push` with {id, name, content}, plus
     * original_updated_time for an update, so the server refuses (409) to overwrite a newer cloud
     * version. Content over 1 MB is refused locally with 413, like the desktop.
     */
    fun pushPreset(context: Context, id: String, name: String, content: JSONObject, originalUpdatedTime: String?): PushResult {
        val body = JSONObject().put("id", id).put("name", name).put("content", content)
        if (!originalUpdatedTime.isNullOrEmpty()) body.put("original_updated_time", originalUpdatedTime)
        val text = body.toString()
        if (text.toByteArray().size > MAX_SYNC_PAYLOAD) return PushResult(413, 0, "larger than 1 MB")
        val (http, response) = apiCall(context, "POST", "/api/v1/sync/push", text)
        val updated = if (http == 200) runCatching { JSONObject(response).optLong("updated_time") }.getOrDefault(0L) else 0L
        return PushResult(if (http == 200 && updated == 0L) -1 else http, updated, response.take(200))
    }

    /**
     * The cloud id of a new preset (generate_uuid_for_setting_id): a name-based (SHA-1, version 5)
     * UUID of "<user id>/<preset name>" in Orca's namespace, so every device makes the same id.
     */
    fun settingId(name: String, userId: String): String {
        val ns = java.util.UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")
        val nsBytes = java.nio.ByteBuffer.allocate(16).putLong(ns.mostSignificantBits).putLong(ns.leastSignificantBits).array()
        val hash = java.security.MessageDigest.getInstance("SHA-1").run {
            update(nsBytes)
            digest((if (userId.isEmpty()) name else "$userId/$name").toByteArray(Charsets.UTF_8))
        }.copyOf(16)
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = java.nio.ByteBuffer.wrap(hash)
        return java.util.UUID(bb.long, bb.long).toString()
    }

    // --- Session -------------------------------------------------------------------------------

    private fun load(context: Context) {
        val stored = synchronized(lock) {
            if (loaded) return
            loaded = true
            readSecret(context)
        } ?: return
        val user = User(stored.optString("user_id"), stored.optString("username"), stored.optString("nickname"), stored.optString("avatar"))
        val refresh = stored.optString("refresh_token")
        if (refresh.isEmpty() || user.id.isEmpty()) return
        // Like start(): the stored user counts as signed in; the access token is refreshed now.
        synchronized(lock) { session = Session("", refresh, user, 0) }
        refresh(context)
    }

    private fun ensureFresh(context: Context) {
        val s = synchronized(lock) { session } ?: return
        if (s.access.isEmpty() || s.expiresAt - System.currentTimeMillis() / 1000 <= REFRESH_SKEW_S) refresh(context)
    }

    /**
     * refresh_session_with_token. A rejected refresh token (400/401/403) signs out; a network or
     * server failure keeps the session for a later retry (classify_refresh_result).
     */
    private fun refresh(context: Context): Boolean {
        val s = synchronized(lock) { session } ?: return false
        val (http, body) = runCatching {
            post("$AUTH_URL$TOKEN_PATH?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh).toString(), bearer = null)
        }.getOrElse { 0 to "" }
        return when {
            http in 200..299 -> runCatching { setSession(context, JSONObject(body)); true }.getOrDefault(false)
            http == 400 || http == 401 || http == 403 -> {
                Log.w(TAG, "refresh token rejected (HTTP $http), signing out")
                synchronized(lock) { session = null }
                sessionFile(context).delete()
                false
            }
            else -> false
        }
    }

    /** set_user_session(json): the GoTrue session (nested user) or the page's flat token message. */
    private fun setSession(context: Context, j: JSONObject): User {
        val access = j.optString("access_token").ifEmpty { j.optString("token") }
        val refresh = j.optString("refresh_token")
        val u = j.optJSONObject("user")
        val user = if (u != null) {
            val meta = u.optJSONObject("user_metadata") ?: JSONObject()
            val userName = meta.optString("username")
            User(u.optString("id"), userName,
                firstNonEmpty(meta.optString("display_name"), meta.optString("nickname"), meta.optString("full_name"), meta.optString("name"), userName),
                meta.optString("avatar_url"))
        } else {
            val userName = j.optString("username")
            User(j.optString("user_id"), userName,
                firstNonEmpty(j.optString("display_name"), j.optString("nickname"), j.optString("full_name"), j.optString("name"), userName),
                j.optString("avatar"))
        }
        if (access.isEmpty() || user.id.isEmpty()) throw IOException("Orca Cloud sign-in failed (no token or user in the response)")
        synchronized(lock) {
            // A refresh response without a new refresh token keeps the old one.
            val keep = refresh.ifEmpty { session?.refresh.orEmpty() }
            session = Session(access, keep, user, jwtExpiry(access))
            loaded = true
            writeSecret(context, JSONObject()
                .put("refresh_token", keep)
                .put("user_id", user.id)
                .put("username", user.userName)
                .put("nickname", user.nickname)
                .put("avatar", user.avatar))
        }
        return user
    }

    private fun firstNonEmpty(vararg s: String) = s.firstOrNull { it.isNotEmpty() }.orEmpty()

    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private fun String.isUuid() = UUID_RE.matches(this)

    /** The JWT's "exp" (epoch seconds), or 0 (decode_jwt_expiry). */
    private fun jwtExpiry(token: String): Long = runCatching {
        val payload = token.split('.')[1]
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).optLong("exp")
    }.getOrDefault(0L)

    // --- Storage (Keystore AES-GCM) ------------------------------------------------------------

    private fun sessionFile(context: Context) = File(context.noBackupFilesDir, "orca_cloud/session.sec")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun writeSecret(context: Context, secret: JSONObject) {
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = c.iv + c.doFinal(secret.toString().toByteArray())
            val f = sessionFile(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(f)) throw IOException("rename failed")
        }.onFailure { Log.w(TAG, "could not store the session: ${it.message}") }
    }

    private fun readSecret(context: Context): JSONObject? {
        val f = sessionFile(context)
        if (!f.exists()) return null
        return runCatching {
            val bytes = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
            JSONObject(String(c.doFinal(bytes, 12, bytes.size - 12)))
        }.onFailure { Log.w(TAG, "stored session unreadable, signed out: ${it.message}") }.getOrNull()
    }

    // --- HTTP ----------------------------------------------------------------------------------

    private fun post(url: String, body: String, bearer: String?) = request("POST", url, body, bearer)

    /** One request with the apikey header (and Bearer token); returns (HTTP status, body), 0 when unreachable. */
    private fun request(method: String, url: String, body: String?, bearer: String?): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("apikey", PUB_KEY)
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray()
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
