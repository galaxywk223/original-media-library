package com.galaxywk.originalmedialibrary.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

private val Context.settingsStore by preferencesDataStore("settings")
class SettingsRepository(private val context: Context) {
    private val directory = stringPreferencesKey("export_tree")
    val exportTree: Flow<String?> = context.settingsStore.data.map { it[directory] }
    suspend fun select(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.settingsStore.edit { it[directory] = uri.toString() }
    }
    suspend fun clear() { context.settingsStore.edit { it.remove(directory) } }
}

object MediaNames {
    fun safe(value: String) = value.replace(Regex("[<>:\"/\\\\|?*\\r\\n]+"), "_").trim().trimEnd('.', ' ').take(100).ifBlank { "作品" }
    fun kind(file: File): String? = when(file.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "avif", "bmp" -> "image"
        "mp4", "mkv", "mov", "webm", "m4v" -> "video"
        "mp3", "m4a", "wav", "aac", "ogg", "flac" -> "audio"
        else -> null
    }
    fun mime(file: File): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: when(kind(file)) { "video" -> "video/mp4"; "audio" -> "audio/mpeg"; else -> "image/jpeg" }
    fun temporary(file: File) = file.name.endsWith(".part") || file.name.contains(".part.")
}

class StorageRepository(private val context: Context, val settings: SettingsRepository) {
    val root: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "media").apply { mkdirs() }.canonicalFile
    val trashRoot: File get() = File(root.parentFile, "trash").apply { mkdirs() }
    fun checked(path: String): File = File(path).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) { "文件不在媒体目录内" } }
    fun contentUri(asset: AssetEntity) = FileProvider.getUriForFile(context, "${context.packageName}.files", checked(asset.path))
    fun open(asset: AssetEntity) {
        val uri = contentUri(asset)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, asset.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        check(intent.resolveActivity(context.packageManager) != null) { "未找到可打开此文件的应用" }
        context.startActivity(intent)
    }
    fun locate(asset: AssetEntity) {
        val path = checked(asset.path).parentFile!!
        val relative = path.relativeTo(root).invariantSeparatorsPath
        val initial = DocumentsContract.buildDocumentUri("${context.packageName}.documents", "media:$relative")
        context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    suspend fun export(asset: AssetEntity): String? = withContext(Dispatchers.IO) {
        val tree = settings.exportTree.first() ?: return@withContext null
        val directory = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: error("导出目录不可用")
        require(directory.canWrite()) { "导出目录权限失效，请重新选择" }
        val existing = directory.findFile(asset.filename)
        val target = if (asset.exportedUri != null) DocumentFile.fromSingleUri(context, Uri.parse(asset.exportedUri))
            ?.takeIf { it.exists() } else null
        require(existing == null || existing.uri == target?.uri) { "导出目录已有同名文件，未覆盖" }
        val destination = target ?: directory.createFile(asset.mimeType, asset.filename) ?: error("无法创建导出文件")
        try {
            context.contentResolver.openOutputStream(destination.uri, "wt")!!.use { output -> checked(asset.path).inputStream().use { it.copyTo(output) } }
            destination.uri.toString()
        } catch (error: Throwable) {
            if (target == null) destination.delete()
            throw error
        }
    }
}

data class LibraryQuery(val search: String = "", val kind: String = "all", val sort: String = "newest", val trash: Boolean = false)
object LibraryFilter {
    fun apply(items: List<CollectionWithAssets>, query: LibraryQuery): List<CollectionWithAssets> {
        val filtered = items.filter {
            (it.collection.trashedAt != null) == query.trash &&
            (query.kind == "all" || it.assets.any { asset -> asset.kind == query.kind }) &&
            (it.collection.title.contains(query.search, true) || it.collection.author.orEmpty().contains(query.search, true))
        }
        return when(query.sort) {
            "oldest" -> filtered.sortedBy { it.collection.createdAt }
            "name" -> filtered.sortedBy { it.collection.title.lowercase() }
            "size" -> filtered.sortedByDescending { it.assets.size }
            else -> filtered.sortedByDescending { it.collection.createdAt }
        }
    }
}

class LibraryRepository(private val context: Context, private val database: MediaDatabase, val storage: StorageRepository) {
    companion object { val mutations = Mutex() }
    private val dao = database.dao()
    val items = dao.observeLibrary()
    suspend fun detail(id: String) = CollectionWithAssets(dao.collection(id) ?: error("作品不存在"), dao.assets(id))

    suspend fun prepareAsset(collectionId: String, file: File, sequence: Int): AssetEntity = withContext(Dispatchers.IO) {
        val kind = MediaNames.kind(file) ?: error("不支持此媒体格式")
        var bitmap: Bitmap? = null
        var width: Int? = null; var height: Int? = null; var duration: Long? = null
        runCatching {
            if (kind == "image") {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                width = bounds.outWidth.takeIf { it > 0 }; height = bounds.outHeight.takeIf { it > 0 }
                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 720) }
                bitmap = BitmapFactory.decodeFile(file.path, options)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (kind == "video") bitmap = retriever.getFrameAtTime(0)
                } finally {
                    retriever.release()
                }
            }
        }
        val id = UUID.randomUUID().toString()
        val thumb = bitmap?.let { image ->
            val directory = File(context.cacheDir, "thumbnails").apply { mkdirs() }
            val target = File(directory, "$id.jpg")
            val scale = minOf(1.0, 720.0 / maxOf(image.width, image.height))
            val scaled = Bitmap.createScaledBitmap(image, maxOf(1, (image.width * scale).toInt()),
                maxOf(1, (image.height * scale).toInt()), true)
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 84, it) }
            if (scaled !== image) scaled.recycle(); image.recycle()
            target.absolutePath
        }
        AssetEntity(id, collectionId, file.canonicalPath, file.name, kind, MediaNames.mime(file), file.length(), sequence,
            thumbnail = thumb, width = width, height = height, durationMs = duration)
    }
    suspend fun register(work: ResolvedWork?, files: List<File>, id: String = UUID.randomUUID().toString()): CollectionEntity {
        val assets = files.mapIndexed { index, file -> prepareAsset(id, file, index + 1) }
        val kinds = assets.map { it.kind }.distinct()
        val collection = CollectionEntity(id, work?.awemeId, work?.sourceUrl, work?.title ?: files.first().nameWithoutExtension,
            work?.author, if (kinds.size == 1) kinds.first() else "mixed", imported = work == null)
        database.withTransaction { dao.insertCollection(collection); dao.insertAssets(assets) }
        return collection
    }
    suspend fun export(id: String) {
        for (asset in dao.assets(id)) {
            val uri = storage.export(asset)
            if (uri != null) dao.insertAssets(listOf(asset.copy(exportedUri = uri)))
        }
    }
    suspend fun rename(id: String, title: String) = mutations.withLock { withContext(Dispatchers.IO) {
        val collection = dao.collection(id) ?: error("作品不存在")
        check(collection.trashedAt == null) { "请先恢复作品" }
        val safe = MediaNames.safe(title)
        val assets = dao.assets(id)
        val changes = mutableListOf<Pair<File, File>>()
        try {
            val updated = assets.map { asset ->
                val source = storage.checked(asset.path)
                val suffix = if (assets.size > 1) "_${asset.sequence.toString().padStart(2, '0')}" else ""
                val target = File(source.parentFile, "$safe$suffix.${source.extension}")
                check(source == target || !target.exists()) { "目标文件名已存在" }
                if (source != target) { check(source.renameTo(target)) { "重命名失败" }; changes += source to target }
                asset.copy(path = target.canonicalPath, filename = target.name)
            }
            database.withTransaction { dao.insertAssets(updated); dao.rename(id, safe) }
            for (asset in updated) asset.exportedUri?.let { uri ->
                val document = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: error("导出文件不可用")
                check(document.renameTo(asset.filename)) { "导出文件重命名失败" }
                dao.insertAssets(listOf(asset.copy(exportedUri = document.uri.toString())))
            }
        } catch (error: Throwable) {
            // Roll back internal file paths and records; exported URI failures remain visible.
            changes.asReversed().forEach { (from, to) -> to.renameTo(from) }
            database.withTransaction { dao.insertAssets(assets); dao.rename(id, collection.title) }
            throw error
        }
    } }
    suspend fun trash(ids: List<String>) = mutations.withLock { withContext(Dispatchers.IO) {
        for (id in ids) {
            val collection = dao.collection(id) ?: continue
            if (collection.trashedAt != null) continue
            val moved = mutableListOf<Pair<File, File>>()
            try {
                for (asset in dao.assets(id)) {
                    val source = storage.checked(asset.path)
                    val target = File(storage.trashRoot, "$id/${asset.id}/${source.name}")
                    target.parentFile!!.mkdirs()
                    if (source.exists()) { check(!target.exists() && source.renameTo(target)) { "移入回收站失败" }; moved += source to target }
                }
                dao.trash(id, System.currentTimeMillis())
            } catch (error: Throwable) { moved.asReversed().forEach { (a,b) -> b.renameTo(a) }; throw error }
        }
    } }
    suspend fun restore(id: String) = mutations.withLock { withContext(Dispatchers.IO) {
        val moved = mutableListOf<Pair<File, File>>()
        try {
            for (asset in dao.assets(id)) {
                val target = storage.checked(asset.path)
                val source = File(storage.trashRoot, "$id/${asset.id}/${asset.filename}")
                require(source.exists()) { "回收站文件不存在" }
                require(!target.exists()) { "原位置存在同名文件，未覆盖" }
                target.parentFile!!.mkdirs()
                check(source.renameTo(target)) { "恢复失败" }; moved += source to target
            }
            dao.trash(id, null)
        } catch (error: Throwable) { moved.asReversed().forEach { (a,b) -> b.renameTo(a) }; throw error }
    } }
    suspend fun importUris(uris: List<Uri>): Int = mutations.withLock { withContext(Dispatchers.IO) {
        var count = 0
        for (uri in uris) {
            var name = "导入媒体"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
            val file = File(File(storage.root, UUID.randomUUID().toString()).apply { mkdirs() }, MediaNames.safe(name))
            require(MediaNames.kind(file) != null) { "不支持导入格式：$name" }
            val partial = File(file.path + ".part")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> partial.outputStream().use { input.copyTo(it) } } ?: error("读取文件失败")
                check(partial.renameTo(file)) { "保存文件失败" }
                register(null, listOf(file)); count++
            } catch (error: Throwable) { partial.delete(); file.delete(); throw error }
        }
        count
    } }
    suspend fun scan(): Int = mutations.withLock { withContext(Dispatchers.IO) {
        var added = 0
        val jobIds = dao.jobs().filter { it.status in listOf("queued","resolving","downloading","extracting") }.map { it.id }.toSet()
        val newFiles = storage.root.walkTopDown().onEnter { it.name !in jobIds }
            .filter { it.isFile && !MediaNames.temporary(it) && MediaNames.kind(it) != null }.toList()
            .filter { dao.assetByPath(it.canonicalPath) == null }
        for (files in newFiles.groupBy { it.parent + "/" + it.nameWithoutExtension.replace(Regex("_\\d{2,3}$"), "") }.values) {
            register(null, files.sortedBy { it.name }); added += files.size
        }
        for (item in dao.library().filter { it.collection.trashedAt == null }) {
            val remaining = item.assets.filter { File(it.path).exists() }
            database.withTransaction {
                item.assets.filter { !File(it.path).exists() }.forEach { dao.deleteAsset(it.id) }
                if (remaining.isEmpty()) dao.deleteCollection(item.collection)
                else dao.updateMediaType(item.collection.id, remaining.map { it.kind }.distinct().let { if (it.size == 1) it.first() else "mixed" })
            }
        }
        added
    } }
}
