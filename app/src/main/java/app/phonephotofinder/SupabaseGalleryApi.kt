package app.phonephotofinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest

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
                    .put("camera_origin", item.cameraOrigin)
                    .put("updated_at", syncedAt)
                if (item.takenAt != null) row.put("taken_at", Instant.ofEpochMilli(item.takenAt).toString())
                if (item.width != null) row.put("width", item.width)
                if (item.height != null) row.put("height", item.height)
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

    fun sharePhoto(context: Context, session: SupabaseSession, item: PhotoItem): String {
        require(item.mediaType == "image") { "Only photos can be shared; videos are not uploaded." }
        val bytes = compressPreview(context, item.uri)
        val hash = MessageDigest.getInstance("SHA-256").digest(item.uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val storagePath = "${session.userId}/$hash.jpg"
        uploadObject(session, storagePath, bytes)
        try {
            val row = JSONObject()
                .put("owner_id", session.userId)
                .put("asset_id", item.uri.toString())
                .put("display_name", item.name)
                .put("storage_path", storagePath)
                .put("mime_type", "image/jpeg")
                .put("camera_origin", item.cameraOrigin)
                .put("shared_at", Instant.now().toString())
            if (item.takenAt != null) row.put("taken_at", Instant.ofEpochMilli(item.takenAt).toString())
            request(
                path = "/rest/v1/gallery_shared_media?on_conflict=owner_id,asset_id",
                method = "POST",
                body = JSONArray().put(row).toString(),
                token = session.accessToken,
                prefer = "resolution=merge-duplicates,return=minimal"
            )
        } catch (e: Exception) {
            try { deleteObject(session, storagePath) } catch (_: Exception) { }
            throw e
        }
        return "Shared compressed preview of ${item.name} with ChatGPT. The original remains on your phone."
    }

    fun sharedAssetIds(session: SupabaseSession): Set<String> {
        val array = requestArray("/rest/v1/gallery_shared_media?select=asset_id", session.accessToken)
        return buildSet {
            for (i in 0 until array.length()) {
                val id = array.optJSONObject(i)?.optString("asset_id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }
    }

    fun unsharePhoto(session: SupabaseSession, assetId: String) {
        val encodedAsset = URLEncoder.encode(assetId, "UTF-8")
        val rows = requestArray(
            "/rest/v1/gallery_shared_media?select=storage_path&asset_id=eq.$encodedAsset",
            session.accessToken
        )
        val storagePath = rows.optJSONObject(0)?.optString("storage_path").orEmpty()
        if (storagePath.isBlank()) return
        deleteObject(session, storagePath)
        request(
            path = "/rest/v1/gallery_shared_media?asset_id=eq.$encodedAsset",
            method = "DELETE",
            body = "",
            token = session.accessToken,
            prefer = "return=minimal"
        )
    }

    private fun compressPreview(context: Context, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("Could not open this photo. Scan the gallery again and retry.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("This image format could not be read.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Could not decode this photo.")
        try {
            val output = ByteArrayOutputStream()
            for (quality in listOf(82, 72, 62, 52)) {
                output.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                if (output.size() <= 1_800_000) return output.toByteArray()
            }
            throw IllegalStateException("This photo is too large to share safely. Try a smaller image.")
        } finally {
            bitmap.recycle()
        }
    }

    private fun uploadObject(session: SupabaseSession, path: String, bytes: ByteArray) {
        val connection = (URL(BASE_URL + "/storage/v1/object/gallery-shares/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("x-upsert", "true")
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val stream = connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("Photo upload failed (HTTP $status)${if (body.isBlank()) "." else ": " + body.take(180)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteObject(session: SupabaseSession, path: String) {
        request(
            path = "/storage/v1/object/gallery-shares",
            method = "DELETE",
            body = JSONObject().put("prefixes", JSONArray().put(path)).toString(),
            token = session.accessToken
        )
    }

    private fun requestArray(path: String, token: String): JSONArray {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Supabase request failed (HTTP $status).")
            return JSONArray(text)
        } finally {
            connection.disconnect()
        }
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
