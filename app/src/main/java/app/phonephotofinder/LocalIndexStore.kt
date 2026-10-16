package app.phonephotofinder

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private, app-local cache. It stores searchable metadata only, never image bytes or thumbnails.
 * Call load/save off the main thread when the gallery is large.
 */
internal object LocalIndexStore {
    private const val FILE_NAME = "gallery-index.json"

    fun load(context: Context): List<PhotoItem> {
        return try {
            val raw = context.openFileInput(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uri = item.optString("uri")
                    if (uri.isBlank()) continue
                    val labelsJson = item.optJSONArray("labels") ?: JSONArray()
                    val labels = buildList {
                        for (labelIndex in 0 until labelsJson.length()) {
                            val label = labelsJson.optString(labelIndex).trim()
                            if (label.isNotEmpty()) add(label)
                        }
                    }
                    add(
                        PhotoItem(
                            uri = Uri.parse(uri),
                            name = item.optString("name", "Photo"),
                            mediaType = if (item.optString("type") == "video") "video" else "image",
                            durationMs = if (item.isNull("duration")) null else item.optLong("duration"),
                            labels = labels
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, items: List<PhotoItem>) {
        val array = JSONArray()
        items.forEach { item ->
            val json = JSONObject()
                .put("uri", item.uri.toString())
                .put("name", item.name)
                .put("type", item.mediaType)
                .put("labels", JSONArray(item.labels))
            if (item.durationMs != null) json.put("duration", item.durationMs)
            array.put(json)
        }
        val temporary = "$FILE_NAME.tmp"
        context.openFileOutput(temporary, Context.MODE_PRIVATE).bufferedWriter(Charsets.UTF_8).use {
            it.write(array.toString())
        }
        val tempFile = context.getFileStreamPath(temporary)
        val targetFile = context.getFileStreamPath(FILE_NAME)
        if (targetFile.exists() && !targetFile.delete()) {
            throw IllegalStateException("Could not replace the saved gallery index.")
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete()
            throw IllegalStateException("Could not save the gallery index.")
        }
    }
}
