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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

internal data class PhotoItem(
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
    var status by remember { mutableStateOf("Loading saved local index…") }
    var busy by remember { mutableStateOf(false) }
    var scanRequested by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showAccount by remember { mutableStateOf(false) }
    var confirmDeleteCloud by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<SupabaseSession?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { LocalIndexStore.load(context) }
        if (photos.isEmpty() && !busy) photos = saved
        if (!busy) status = if (saved.isNotEmpty()) "Loaded ${saved.size} saved items. Tap Scan my gallery to refresh the index." else "Grant photo access, then index your gallery."
    }

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
        val stopWords = setOf("show", "find", "me", "please", "my", "the", "a", "an", "in", "on", "at", "from", "with", "of", "is", "was", "are", "picture", "pictures", "photo", "photos", "image", "images")
        val synonyms = mapOf(
            "kutta" to "dog", "kutte" to "dog", "billi" to "cat", "gaadi" to "car", "gadi" to "car",
            "samundar" to "beach", "samudra" to "ocean", "pahad" to "mountain",
            "ped" to "tree", "phool" to "flower", "khana" to "food", "aadmi" to "person", "ladki" to "girl",
            "laal" to "red", "lal" to "red", "neela" to "blue", "nila" to "blue", "hara" to "green",
            "safed" to "white", "kala" to "black", "kapde" to "clothing", "shirt" to "clothing",
            "aasman" to "sky", "suraj" to "sun", "paani" to "water", "pani" to "water"
        )
        val rawWords = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val wantsVideo = rawWords.any { it in setOf("video", "videos", "clip", "clips") }
        val wantsImage = rawWords.any { it in setOf("photo", "photos", "image", "images", "picture", "pictures") }
        val words = rawWords.filterNot { it in stopWords || it in setOf("video", "videos", "clip", "clips") }
            .flatMap { word -> listOf(word, synonyms[word]).filterNotNull() }.distinct()
        if (q.isBlank()) photos else photos.filter { photo ->
            val correctType = when { wantsVideo -> photo.mediaType == "video"; wantsImage -> photo.mediaType == "image"; else -> true }
            val searchable = photo.name.lowercase(Locale.ROOT) + " " + photo.labels.joinToString(" ").lowercase(Locale.ROOT)
            correctType && (words.isEmpty() || searchable.contains(q) || words.any { word -> searchable.contains(word) })
        }
    }

    LaunchedEffect(scanRequested) {
        if (scanRequested && !busy) {
            if (!hasPermission()) {
                scanRequested = false
                permissionLauncher.launch(requestedPermissions())
            } else {
                busy = true
                try {
                    status = "Reading your gallery…"
                    val found = withContext(Dispatchers.IO) {
                        readGallery(context) { progress -> scope.launch { status = progress } }
                    }
                    val cachedByUri = photos.associateBy { it.uri.toString() }
                    val withCachedLabels = found.map { item ->
                        val cached = cachedByUri[item.uri.toString()]
                        if (cached != null && cached.labels.isNotEmpty()) item.copy(labels = cached.labels) else item
                    }
                    photos = withCachedLabels
                    withContext(Dispatchers.IO) { LocalIndexStore.save(context, withCachedLabels) }
                    if (found.isEmpty()) {
                        status = "No accessible photos or videos found. Check Android photo permissions and try again."
                    } else {
                        val imageCount = found.count { it.mediaType == "image" }
                        status = "Loaded $imageCount photos and ${found.size - imageCount} videos. Analyzing photos on this device…"
                        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                        try {
                            val labeled = mutableListOf<PhotoItem>()
                            for ((index, photo) in withCachedLabels.withIndex()) {
                                val labels = if (photo.labels.isNotEmpty()) photo.labels else if (photo.mediaType == "image") {
                                    try {
                                        val image = InputImage.fromFilePath(context, photo.uri)
                                        withContext(Dispatchers.IO) { Tasks.await(labeler.process(image)) }.map { it.text.lowercase(Locale.ROOT) }
                                    } catch (_: Exception) { emptyList() }
                                } else emptyList()
                                labeled.add(photo.copy(labels = labels))
                                if (index % 5 == 0 || index == withCachedLabels.lastIndex) {
                                    status = "Analyzing on this device: ${index + 1} of ${withCachedLabels.size} items. You can search the indexed results now."
                                }
                                // Publish result updates and persist in batches to keep large scans responsive.
                                if (index % 25 == 0 || index % 50 == 0 || index == withCachedLabels.lastIndex) {
                                    val current = labeled.toList() + withCachedLabels.drop(index + 1)
                                    photos = current
                                    if (index % 50 == 0 || index == withCachedLabels.lastIndex) {
                                        withContext(Dispatchers.IO) { LocalIndexStore.save(context, current) }
                                    }
                                }
                            }
                            photos = labeled
                            withContext(Dispatchers.IO) { LocalIndexStore.save(context, labeled) }
                            status = "Done. ${labeled.size} items indexed on this device. Labels are local; videos are searchable by filename/type."
                        } finally {
                            labeler.close()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = "Gallery scan failed: ${e.message ?: e.javaClass.simpleName}. Check photo permissions and tap Scan my gallery to retry."
                } finally {
                    busy = false
                    scanRequested = false
                }
            }
        }
    }

    val appColors = darkColorScheme(
        primary = Color(0xFF57D6C3),
        onPrimary = Color(0xFF09211E),
        secondary = Color(0xFFA8B8FF),
        onSecondary = Color(0xFF10172E),
        background = Color(0xFF10141C),
        onBackground = Color(0xFFF4F7FB),
        surface = Color(0xFF1A2230),
        onSurface = Color(0xFFF4F7FB),
        surfaceVariant = Color(0xFF242E3D),
        onSurfaceVariant = Color(0xFFB6C1D1),
        outline = Color(0xFF39465A)
    )

    MaterialTheme(colorScheme = appColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("PHONE PHOTO FINDER", color = Color(0xFF57D6C3), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text("Find the moment you remember.", modifier = Modifier.padding(top = 8.dp), color = Color(0xFFF4F7FB), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Search your gallery in everyday language.", modifier = Modifier.padding(top = 6.dp), color = Color(0xFFB6C1D1), style = MaterialTheme.typography.bodyMedium)
                        Row(modifier = Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(shape = RoundedCornerShape(50), colors = CardDefaults.cardColors(containerColor = Color(0xFF242E3D))) {
                                Text("  On-device labels  ", modifier = Modifier.padding(vertical = 7.dp), color = Color(0xFFA8B8FF), style = MaterialTheme.typography.labelSmall)
                            }
                            Card(shape = RoundedCornerShape(50), colors = CardDefaults.cardColors(containerColor = Color(0xFF242E3D))) {
                                Text("  Sync only when you choose  ", modifier = Modifier.padding(vertical = 7.dp), color = Color(0xFF57D6C3), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search photos and videos") },
                    placeholder = { Text("Try “blue shirt”, “dog”, “beach”…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Button(
                    onClick = {
                        if (hasPermission()) scanRequested = true
                        else permissionLauncher.launch(requestedPermissions())
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF57D6C3), contentColor = Color(0xFF09211E))
                ) {
                    Text(if (busy) "Working…" else "Scan my gallery", modifier = Modifier.padding(vertical = 4.dp), fontWeight = FontWeight.Bold)
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("PRIVATE METADATA SYNC", color = Color(0xFFA8B8FF), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text("Original media stays on your phone. Cloud metadata sync is optional.", modifier = Modifier.padding(top = 4.dp), color = Color(0xFFB6C1D1), style = MaterialTheme.typography.bodySmall)
                        if (session == null && !showAccount) {
                            Button(onClick = { showAccount = true }, modifier = Modifier.padding(top = 8.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA8B8FF), contentColor = Color(0xFF10172E)) ) {
                                Text("Connect account")
                            }
                        }
                        if (session == null && showAccount) {
                            OutlinedTextField(value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Email") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                            OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Password") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                        }
                        if (session == null && showAccount) {
                            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (email.isBlank() || password.length < 6) status = "Enter your email and password (at least 6 characters)."
                                        else scope.launch {
                                            busy = true
                                            try { session = withContext(Dispatchers.IO) { SupabaseGalleryApi.signIn(email, password) }; status = "Signed in. Your gallery stays on this phone until you choose Sync metadata." }
                                            catch (e: Exception) { status = e.message ?: "Sign-in failed." }
                                            finally { busy = false }
                                        }
                                    },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("Sign in") }
                                Button(
                                    onClick = {
                                        if (email.isBlank() || password.length < 6) status = "Enter your email and a password of at least 6 characters."
                                        else scope.launch {
                                            busy = true
                                            try { session = withContext(Dispatchers.IO) { SupabaseGalleryApi.signUp(email, password) }; status = "Account created and signed in." }
                                            catch (e: Exception) { status = e.message ?: "Sign-up failed." }
                                            finally { busy = false }
                                        }
                                    },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA8B8FF), contentColor = Color(0xFF10172E))
                                ) { Text("Create account") }
                            }
                        } else if (session != null) {
                            Text("Signed in · session lasts until the app closes", modifier = Modifier.padding(top = 8.dp), color = Color(0xFF57D6C3), style = MaterialTheme.typography.bodySmall)
                            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                val count = withContext(Dispatchers.IO) { SupabaseGalleryApi.sync(session!!, photos) }
                                                status = "Synced metadata for $count items. Original media files were not uploaded."
                                            } catch (e: Exception) { status = e.message ?: "Metadata sync failed." }
                                            finally { busy = false }
                                        }
                                    },
                                    enabled = !busy && photos.isNotEmpty(),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("Sync metadata") }
                                Button(onClick = { session = null; showAccount = false; password = ""; status = "Signed out on this device." }, enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text("Sign out") }
                            }
                            TextButton(onClick = { confirmDeleteCloud = true }, enabled = !busy, modifier = Modifier.padding(top = 2.dp)) {
                                Text("Delete my cloud index", color = Color(0xFFFFC66D))
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("YOUR RESULTS", color = Color(0xFFB6C1D1), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("${filtered.size} items", color = Color(0xFF57D6C3), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                if (busy) {
                    Row(modifier = Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp), color = Color(0xFF57D6C3))
                        Text(status, color = Color(0xFFB6C1D1), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(status, color = Color(0xFFB6C1D1), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                }
                LazyVerticalGrid(
                    modifier = Modifier.weight(1f),
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                if (confirmDeleteCloud) {
                    AlertDialog(
                        onDismissRequest = { confirmDeleteCloud = false },
                        title = { Text("Delete cloud index?") },
                        text = { Text("This permanently deletes your synced gallery metadata from your account. Photos and videos on this phone are not affected.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    confirmDeleteCloud = false
                                    val activeSession = session
                                    if (activeSession != null) scope.launch {
                                        busy = true
                                        try {
                                            withContext(Dispatchers.IO) { SupabaseGalleryApi.deleteCloudIndex(activeSession) }
                                            status = "Cloud gallery metadata deleted. Local photos and index are unchanged."
                                        } catch (e: Exception) {
                                            status = e.message ?: "Could not delete the cloud index."
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC66D), contentColor = Color(0xFF10141C))
                            ) { Text("Delete index") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDeleteCloud = false }, enabled = !busy) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}

private fun readGallery(context: android.content.Context, onProgress: (String) -> Unit): List<PhotoItem> {
    val result = mutableListOf<PhotoItem>()
    val imageGranted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val videoGranted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    if (imageGranted) {
        onProgress("Loading photos from Android MediaStore…")
        result.addAll(queryMedia(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", onProgress))
    }
    if (videoGranted) {
        onProgress("Loading videos from Android MediaStore…")
        result.addAll(queryMedia(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", onProgress))
    }
    return result
}

private fun queryMedia(context: android.content.Context, collection: Uri, mediaType: String, onProgress: (String) -> Unit): List<PhotoItem> {
    val kind = if (mediaType == "video") "videos" else "photos"
    return try {
        val projection = if (mediaType == "video") {
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION)
        } else {
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        }
        val result = mutableListOf<PhotoItem>()
        // Avoid sorting the entire library during the initial scan; it can be slow on large phones.
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
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
                if (result.size % 200 == 0) onProgress("Reading ${result.size} $kind from your phone…")
            }
        }
        result
    } catch (e: SecurityException) {
        onProgress("Android denied access to $kind. Check photo/video permissions in Settings.")
        emptyList()
    }
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
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = photo.name,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF242E3D)) {
                    Text(if (photo.mediaType == "video") "▶" else "▧", modifier = Modifier.padding(vertical = 34.dp), color = Color(0xFFA8B8FF), style = MaterialTheme.typography.headlineMedium)
                }
            }
            Text(photo.name, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp), color = Color(0xFFF4F7FB), maxLines = 1, fontWeight = FontWeight.Medium)
            if (photo.mediaType == "video") {
                val seconds = (photo.durationMs ?: 0L) / 1000
                Text("VIDEO • ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 8.dp), color = Color(0xFFA8B8FF), style = MaterialTheme.typography.labelSmall)
            }
            if (photo.labels.isNotEmpty()) {
                Text(
                    photo.labels.take(3).joinToString(" · "),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 10.dp),
                    color = Color(0xFFB6C1D1),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}
