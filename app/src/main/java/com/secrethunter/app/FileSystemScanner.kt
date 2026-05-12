package com.secrethunter.app

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

object FileSystemScanner {

    private const val MAX_BYTES = 5 * 1024 * 1024

    private val TEXT_EXTENSIONS = setOf(
        "txt", "json", "xml", "conf", "env", "cfg", "log", "properties",
        "md", "yml", "yaml", "ini", "gradle", "pem", "key", "toml", "csv",
    )

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp",
    )

    suspend fun scan(
        context: Context,
        detector: RegexDetector,
        onStatus: (String) -> Unit,
    ): List<Secret> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val all = ArrayList<Secret>()
        val seenFiles = LinkedHashSet<String>()

        fun scanText(label: String, text: String) {
            coroutineContext.ensureActive()
            if (text.isEmpty()) return
            text.lineSequence().forEachIndexed { idx, line ->
                all.addAll(detector.matchLine(line, idx + 1, label, now))
            }
        }

        fun scanBytes(label: String, bytes: ByteArray) {
            val slice = if (bytes.size > MAX_BYTES) bytes.copyOf(MAX_BYTES) else bytes
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val text = try {
                decoder.decode(ByteBuffer.wrap(slice)).toString()
            } catch (_: Exception) {
                String(slice, Charsets.ISO_8859_1)
            }
            scanText(label, text)
        }

        fun offerFile(file: File) {
            if (!file.isFile || !file.canRead()) return
            val key = file.absolutePath
            if (!seenFiles.add(key)) return
            val ext = file.extension.lowercase()
            if (ext !in TEXT_EXTENSIONS && ext !in IMAGE_EXTENSIONS) return
            if (file.length() > MAX_BYTES * 4L) return
            onStatus(file.name)
            coroutineContext.ensureActive()
            runCatching {
                val bytes = file.readBytes()
                scanBytes(key, bytes)
            }
        }

        fun walkDir(dir: File?) {
            if (dir == null || !dir.exists()) return
            val stack = ArrayDeque<File>()
            stack.add(dir)
            var steps = 0
            while (stack.isNotEmpty()) {
                coroutineContext.ensureActive()
                val d = stack.removeLast()
                val list = d.listFiles() ?: continue
                for (f in list) {
                    if (++steps > 8000) return
                    if (f.isDirectory) stack.add(f) else offerFile(f)
                }
            }
        }

        walkDir(context.filesDir)
        walkDir(context.cacheDir)
        context.externalCacheDirs.filterNotNull().forEach { walkDir(it) }
        context.getExternalFilesDirs(null).filterNotNull().forEach { walkDir(it) }

        if (hasLegacyStorageRead(context)) {
            walkDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            walkDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot != null && extRoot.canRead()) {
                walkDir(File(extRoot, "Download"))
                walkDir(File(extRoot, "Documents"))
            }
        }

        // Téléchargements : ne pas exiger READ_MEDIA_* (images/vidéos) — sur Android 13+ ces droits
        // ne couvrent pas les .txt ; une requête directe peut quand même retourner des fichiers indexés.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStoreDownloadsUnrestricted(context, onStatus) { label, bytes ->
                scanBytes(label, bytes)
            }
        }

        queryMediaStore(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            IMAGE_EXTENSIONS,
        ) { id, name ->
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val label = "Images/$name"
            onStatus(name)
            readUri(context, uri)?.let { scanBytes(label, it) }
        }

        queryMediaStore(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            setOf("mp4", "mkv", "webm"),
        ) { id, name ->
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            val label = "Video/$name"
            onStatus(name)
            readUri(context, uri)?.let { scanBytes(label, it) }
        }

        all
    }

    /**
     * Analyse un fichier choisi via le sélecteur système (SAF) : lecture garantie même si
     * Téléchargements n’est pas exposé à MediaStore / sans permission média.
     */
    /** @return null si le fichier n’a pas pu être lu, liste (éventuellement vide) sinon. */
    suspend fun scanContentUri(
        context: Context,
        uri: Uri,
        detector: RegexDetector,
    ): List<Secret>? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val bytes = readUri(context, uri) ?: return@withContext null
        val slice = if (bytes.size > MAX_BYTES) bytes.copyOf(MAX_BYTES) else bytes
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val text = try {
            decoder.decode(ByteBuffer.wrap(slice)).toString()
        } catch (_: Exception) {
            String(slice, Charsets.ISO_8859_1)
        }
        val label = resolveDisplayName(context, uri)
        val out = ArrayList<Secret>()
        text.lineSequence().forEachIndexed { idx, line ->
            out.addAll(detector.matchLine(line, idx + 1, label, now))
        }
        out
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        val fromMeta = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromMeta?.takeIf { !it.isNullOrBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"
    }

    private fun hasLegacyStorageRead(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            false
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun readUri(context: Context, uri: Uri): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArrayOutputStreamCompat()
                val tmp = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(tmp)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        buf.write(tmp, 0, n - (total - MAX_BYTES))
                        break
                    }
                    buf.write(tmp, 0, n)
                }
                buf.toByteArray()
            }
        }.getOrNull()
    }

    private fun queryMediaStoreDownloadsUnrestricted(
        context: Context,
        onStatus: (String) -> Unit,
        onScan: (String, ByteArray) -> Unit,
    ) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val cursor = try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )
        } catch (_: SecurityException) {
            null
        } ?: return
        cursor.use {
            val idIdx = it.getColumnIndex(MediaStore.Downloads._ID)
            val nameIdx = it.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
            if (idIdx < 0 || nameIdx < 0) return
            var count = 0
            while (it.moveToNext()) {
                if (++count > 2000) break
                val name = it.getString(nameIdx) ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in TEXT_EXTENSIONS) continue
                val id = it.getLong(idIdx)
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                onStatus(name)
                readUri(context, uri)?.let { bytes -> onScan("Download/$name", bytes) }
            }
        }
    }

    private fun queryMediaStore(
        context: Context,
        collection: Uri,
        idColumn: String,
        nameColumn: String,
        allowedExt: Set<String>,
        onEach: (Long, String) -> Unit,
    ) {
        if (!canReadMedia(context)) return
        val resolver = context.contentResolver
        val projection = arrayOf(idColumn, nameColumn)
        val cursor = try {
            resolver.query(collection, projection, null, null, null)
        } catch (_: SecurityException) {
            null
        } ?: return
        cursor.use {
            val idIdx = it.getColumnIndex(idColumn)
            val nameIdx = it.getColumnIndex(nameColumn)
            if (idIdx < 0 || nameIdx < 0) return
            var count = 0
            while (it.moveToNext()) {
                if (++count > 2000) break
                val name = it.getString(nameIdx) ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in allowedExt) continue
                val id = it.getLong(idIdx)
                onEach(id, name)
            }
        }
    }

    private fun canReadMedia(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            ).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Minimal growable buffer without pulling in Okio. */
    private class ByteArrayOutputStreamCompat {
        private var buf = ByteArray(256)
        private var count = 0

        fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val newCount = count + len
            if (newCount > buf.size) {
                var newSize = buf.size * 2
                while (newSize < newCount) newSize *= 2
                buf = buf.copyOf(newSize)
            }
            System.arraycopy(b, off, buf, count, len)
            count = newCount
        }

        fun toByteArray(): ByteArray = buf.copyOf(count)
    }
}
