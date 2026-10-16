package app.phonephotofinder

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class PhotoItem(
    val uri: Uri,
    val name: String,
    val mediaType: String = "image",
    val durationMs: Long? = null,
    val labels: List<String> = emptyList()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoFinderScreen() }
    }
}

@Composable
private fun PhotoFinderScreen() {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Grant photo access, then index your gallery.") }
    var busy by remember { mutableStateOf(false) }
    var scanRequested by remember { mutableStateOf(false) }

    fun requestedPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun hasPermission(): Boolean {
        val names = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) +
                if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return names.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) scanRequested = true
        else status = "Gallery access denied. Enable photo/video access in Android Settings."
    }

    val filtered = remember(photos, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) photos else photos.filter { photo ->
            photo.name.lowercase(Locale.ROOT).contains(q) ||
                photo.labels.any { label -> q.split(Regex("\\s+")).any { word -> label.contains(word) } }
        }
    }

    LaunchedEffect(scanRequested) {
        if (scanRequested && !busy) {
            scanRequested = false
            if (!hasPermission()) {
                permissionLauncher.launch(requestedPermissions())
            } else {
                busy = true
                status = "Reading gallery…"
                val found = withContext(Dispatchers.IO) { readGallery(context) }
                photos = found
                val imageCount = found.count { it.mediaType == "image" }
                status = "Found $imageCount photos and ${found.size - imageCount} videos. Analyzing photos on this device…"
                val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                val labeled = mutableListOf<PhotoItem>()
                for ((index, photo) in found.withIndex()) {
                    val labels = if (photo.mediaType == "image") {
                        try {
                            val image = InputImage.fromFilePath(context, photo.uri)
                            withContext(Dispatchers.IO) { Tasks.await(labeler.process(image)) }.map { it.text.lowercase(Locale.ROOT) }
                        } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    labeled.add(photo.copy(labels = labels))
                    if (index % 10 == 0 || index == found.lastIndex) {
                        photos = labeled.toList() + found.drop(index + 1)
                        status = "Analyzed " + (index + 1) + " of " + found.size + " photos…"
                    }
                }
                labeler.close()
                photos = labeled
                status = "Ready. ${labeled.size} items indexed locally. Photo labels run on-device; video search currently uses filename/type."
                busy = false
            }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Phone Photo Finder", style = MaterialTheme.typography.headlineSmall)
                Text("Search photos on this phone. Labels are generated on-device.")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    label = { Text("Try: blue shirt, dog, beach…") },
                    singleLine = true
                )
                Button(onClick = {
                    if (hasPermission()) scanRequested = true
                    else permissionLauncher.launch(requestedPermissions())
                }, enabled = !busy) {
                    Text("Index my gallery")
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                Text(filtered.size.toString() + " result(s)", style = MaterialTheme.typography.labelLarge)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.uri.toString() }) { photo ->
                        PhotoCard(photo) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(photo.uri, if (photo.mediaType == "video") "video/*" else "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }
                    }
                }
            }
        }
    }
}

private fun readGallery(context: android.content.Context): List<PhotoItem> {
    val result = mutableListOf<PhotoItem>()
    val imageGranted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val videoGranted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    if (imageGranted) queryMedia(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")?.let(result::addAll)
    if (videoGranted) queryMedia(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")?.let(result::addAll)
    return result
}

private fun queryMedia(context: android.content.Context, collection: Uri, mediaType: String): List<PhotoItem>? {
    return try {
        val projection = if (mediaType == "video") arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION)
            else arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val result = mutableListOf<PhotoItem>()
        context.contentResolver.query(collection, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationColumn = if (mediaType == "video") cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            while (cursor.moveToNext()) {
                val duration = if (durationColumn >= 0 && !cursor.isNull(durationColumn)) cursor.getLong(durationColumn) else null
                result.add(PhotoItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    name = cursor.getString(nameColumn) ?: if (mediaType == "video") "Video" else "Photo",
                    mediaType = mediaType,
                    durationMs = duration
                ))
            }
        }
        result
    } catch (_: SecurityException) { emptyList() }
}

@Composable
private fun PhotoCard(photo: PhotoItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(photo.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(photo.uri) {
        bitmap = withContext(Dispatchers.IO) {
            try { context.contentResolver.loadThumbnail(photo.uri, Size(400, 400), null) }
            catch (_: Exception) { null }
        }
    }
    Card(onClick = onOpen) {
        Column {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = photo.name,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
            }
            Text(photo.name, modifier = Modifier.padding(8.dp), maxLines = 1)
            if (photo.mediaType == "video") {
                val seconds = (photo.durationMs ?: 0L) / 1000
                Text("Video • ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (photo.labels.isNotEmpty()) {
                Text(
                    photo.labels.take(3).joinToString(", "),
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}
