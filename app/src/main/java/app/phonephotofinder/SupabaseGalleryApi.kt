package app.phonephotofinder

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

internal data class SupabaseSession(
    val accessToken: String,
    val userId: String
)

internal object SupabaseGalleryApi {
    private const val BASE_URL = "https://qcscozdqdjjwjouxtnzf.supabase.co"
    // Publishable client key only. Never put a service-role/secret key in Android.
    private const val PUBLISHABLE_KEY = "sb_publishable_PQJeTL-aDf1RLOx8iVUAMg_3Mc2Zjmy"

    fun signIn(email: String, password: String): SupabaseSession =
        authenticate("/auth/v1/token?grant_type=password", email, password)

    fun signUp(email: String, password: String): SupabaseSession {
        val response = request(
            path = "/auth/v1/signup",
            method = "POST",
            body = JSONObject().put("email", email).put("password", password).toString()
        )
        val token = response.optString("access_token")
        val user = response.optJSONObject("user") ?: response
        val id = user.optString("id")
        if (token.isBlank() || id.isBlank()) {
            throw IllegalStateException("Account request received. If email confirmation is enabled, confirm the email and then sign in.")
        }
        return SupabaseSession(token, id)
    }

    private fun authenticate(path: String, email: String, password: String): SupabaseSession {
        val response = request(
            path = path,
            method = "POST",
            body = JSONObject().put("email", email).put("password", password).toString()
        )
        val token = response.optString("access_token")
        val user = response.optJSONObject("user")
        val id = user?.optString("id").orEmpty()
        if (token.isBlank() || id.isBlank()) throw IllegalStateException("Sign-in response did not include a session.")
        return SupabaseSession(token, id)
    }

    fun sync(session: SupabaseSession, items: List<PhotoItem>): Int {
        if (items.isEmpty()) return 0
        val syncedAt = Instant.now().toString()
        // Keep requests bounded for large galleries; never send media bytes, only searchable metadata.
        items.chunked(100).forEach { batch ->
            val rows = JSONArray()
            batch.forEach { item ->
                val row = JSONObject()
                    .put("owner_id", session.userId)
                    .put("asset_id", item.uri.toString())
                    .put("media_type", item.mediaType)
                    .put("display_name", item.name)
                    .put("mime_type", if (item.mediaType == "video") "video/*" else "image/*")
                    .put("labels", JSONArray(item.labels))
                    .put("updated_at", syncedAt)
                if (item.durationMs != null) row.put("duration_ms", item.durationMs)
                rows.put(row)
            }
            request(
                path = "/rest/v1/gallery_items?on_conflict=owner_id,asset_id",
                method = "POST",
                body = rows.toString(),
                token = session.accessToken,
                prefer = "resolution=merge-duplicates,return=minimal"
            )
        }
        return items.size
    }

    fun deleteCloudIndex(session: SupabaseSession) {
        request(
            path = "/rest/v1/gallery_items?owner_id=eq." + session.userId,
            method = "DELETE",
            body = "",
            token = session.accessToken,
            prefer = "return=minimal"
        )
    }
    private fun request(
        path: String,
        method: String,
        body: String,
        token: String? = null,
        prefer: String? = null
    ): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            else setRequestProperty("Authorization", "Bearer $PUBLISHABLE_KEY")
            if (prefer != null) setRequestProperty("Prefer", prefer)
            doOutput = body.isNotEmpty()
        }
        try {
            if (body.isNotEmpty()) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = try { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") } } catch (_: Exception) { "" }
                throw IllegalStateException(message.ifBlank { "Supabase request failed (HTTP $status)." })
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
