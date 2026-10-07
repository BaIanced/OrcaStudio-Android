package app.orcaandroid.net

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Bambu account sign-in through open-bamboo-networking, following Bambu Studio's login dialog
 * (src/slic3r/GUI/WebUserLoginDialog.cpp): the sign-in page hands over a one-time ticket, which is
 * exchanged for tokens, the profile is read, and the resulting user_login JSON goes to obn
 * (change_user). obn keeps the session in obn.auth.json in [ObnCredentials.dir].
 *
 * Signed in, the account's printer list provides each printer's serial number and LAN access
 * code, so they need not be read off the printer. All calls block; use them off the main thread.
 */
object BambuAccount {
    data class Printer(val serial: String, val name: String, val accessCode: String, val model: String, val online: Boolean)

    private class HttpResult(val rc: Int, val http: Int, val body: String)

    private fun start(context: Context): String = ObnCredentials.startAgent(context)

    /** obn's plugin version (e.g. 02.08.01.99), reported to the sign-in page like Bambu Studio's client version. */
    fun clientVersion(context: Context): String = runCatching { start(context) }.getOrDefault("02.08.01.99")

    private fun parse(result: String) = JSONObject(result).let { HttpResult(it.optInt("rc", -1), it.optInt("http"), it.optString("body")) }

    /** obn's own log line for a failed request (e.g. the curl error), appended to the message. */
    private fun obnReason(context: Context, marker: String): String =
        (runCatching { ObnCredentials.lastLogLine(context, marker) }.getOrNull()?.let { "\n$it" } ?: "") +
            "\nCA file: ${ObnCredentials.caCount} certificates, ${File(ObnCredentials.dir(context), "cacert.pem").path}"

    /** Signed-in user name, or null when signed out. */
    fun userName(context: Context): String? {
        start(context)
        return ObnNative.userName().ifEmpty { null }
    }

    /** Bambu's sign-in page, as Bambu Studio opens it (<host>/<language>/sign-in). */
    fun loginUrl(context: Context): String {
        start(context)
        val host = ObnNative.loginHost().trimEnd('/').ifEmpty { "https://bambulab.com" }
        return if (host.endsWith("/sign-in")) host else "$host/en/sign-in"
    }

    /** Completes sign-in with the ticket from the sign-in page; returns the user name. */
    fun loginWithTicket(context: Context, ticket: String): String {
        start(context)
        val token = parse(ObnNative.getMyToken(ticket))
        if (token.rc != 0) throw IOException("Bambu sign-in failed (token: obn ${token.rc}, HTTP ${token.http})" + obnReason(context, "get_my_token"))
        val t = JSONObject(token.body)
        val access = t.optString("accessToken")
        if (access.isEmpty()) throw IOException("Bambu sign-in failed (no access token)")

        val profile = parse(ObnNative.getMyProfile(access))
        if (profile.rc != 0) throw IOException("Bambu sign-in failed (profile: obn ${profile.rc}, HTTP ${profile.http})")
        val p = runCatching { JSONObject(profile.body) }.getOrDefault(JSONObject())

        // Same shape as WebUserLoginDialog.cpp builds for handle_script_message("user_login").
        val user = JSONObject()
            .put("uid", p.optString("uidStr"))
            .put("name", p.optString("name"))
            .put("account", p.optString("account"))
            .put("avatar", p.optString("avatar"))
        val data = JSONObject()
            .put("token", access)
            .put("refresh_token", t.optString("refreshToken"))
            .put("expires_in", t.opt("expiresIn")?.toString() ?: "")
            .put("refresh_expires_in", t.opt("refreshExpiresIn")?.toString() ?: "")
            .put("user", user)
        return loginWithUserInfo(context, JSONObject().put("command", "user_login").put("data", data).toString())
    }

    /** Completes sign-in with a ready user_login message (older sign-in pages send this directly). */
    fun loginWithUserInfo(context: Context, userLoginJson: String): String {
        start(context)
        val rc = ObnNative.changeUser(userLoginJson)
        val name = ObnNative.userName()
        if (name.isEmpty()) throw IOException("Bambu sign-in was not accepted (obn $rc)")
        return name
    }

    fun logout(context: Context) {
        start(context)
        ObnNative.logout()
    }

    /**
     * The account's cloud presets for profile bundle [bundleVersion], as the desktop's sync loads
     * them: { name: { option or metadata key: serialized value } }.
     */
    fun cloudPresets(context: Context, bundleVersion: String): JSONObject {
        start(context)
        if (ObnNative.userName().isEmpty()) throw IOException("Sign in to your Bambu account first")
        val r = JSONObject(ObnNative.cloudPresets(bundleVersion))
        val rc = r.optInt("rc", -1)
        if (rc != 0) throw IOException("Could not load the cloud presets (obn $rc)" + obnReason(context, "preset"))
        return r.optJSONObject("presets") ?: JSONObject()
    }

    /** The printers bound to the signed-in account. */
    fun printers(context: Context): List<Printer> {
        start(context)
        val r = parse(ObnNative.userPrintInfo())
        if (r.rc != 0) throw IOException("Could not load the account's printers (obn ${r.rc}, HTTP ${r.http})" + obnReason(context, "user_print"))
        val devices = JSONObject(r.body).optJSONArray("devices") ?: return emptyList()
        return (0 until devices.length()).mapNotNull { i ->
            val d = devices.optJSONObject(i) ?: return@mapNotNull null
            val serial = d.optString("dev_id")
            if (serial.isEmpty()) null
            else Printer(
                serial = serial,
                name = d.optString("dev_name", serial),
                accessCode = if (d.isNull("dev_access_code")) "" else d.optString("dev_access_code"),
                model = d.optString("dev_product_name", d.optString("dev_model_name")),
                online = d.optBoolean("dev_online", false),
            )
        }
    }
}
