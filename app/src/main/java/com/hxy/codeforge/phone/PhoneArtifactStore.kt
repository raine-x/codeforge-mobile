package com.hxy.codeforge.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.hxy.codeforge.core.EnvKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 手机操控产物管理（截图 PNG、录屏 MP4、录音 M4A）。
 *
 * 元数据持久化在 `filesDir/phone_control/artifacts.json`，实体文件优先存在当前工作区
 * `phone_artifacts/` 下，供模型直接引用渲染，也支持在「设置 → 手机操控 → 记录与产物」中
 * 预览、系统打开、分享、导出至 Download 与清除。
 */
object PhoneArtifactStore {

    private const val MAX_ARTIFACTS = 120

    private val _artifacts = MutableStateFlow<List<PhoneArtifact>>(emptyList())
    val artifacts: StateFlow<List<PhoneArtifact>> = _artifacts.asStateFlow()

    private val lock = Any()

    @Volatile
    private var metaFile: File? = null

    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, "phone_control").apply { mkdirs() }
        val f = File(dir, "artifacts.json")
        metaFile = f
        _artifacts.value = loadFromDisk(f)
    }

    fun register(
        context: Context,
        type: PhoneArtifactType,
        file: File,
        durationMs: Long = 0L,
        width: Int = 0,
        height: Int = 0,
    ): PhoneArtifact {
        val wsPath = PhoneRuntime.toWorkspaceRelativePath(context, file)
        val item = PhoneArtifact(
            id = UUID.randomUUID().toString().take(12),
            type = type,
            fileName = file.name,
            workspacePath = wsPath,
            absolutePath = file.absolutePath,
            createdAtMs = System.currentTimeMillis(),
            sizeBytes = file.length(),
            durationMs = durationMs,
            width = width,
            height = height,
        )
        synchronized(lock) {
            val next = (listOf(item) + _artifacts.value.filterNot { it.absolutePath == item.absolutePath })
                .take(MAX_ARTIFACTS)
            _artifacts.value = next
            saveToDisk(next)
        }
        return item
    }

    fun delete(id: String): Boolean {
        var removed = false
        synchronized(lock) {
            val target = _artifacts.value.firstOrNull { it.id == id } ?: return false
            runCatching { File(target.absolutePath).delete() }
            val next = _artifacts.value.filterNot { it.id == id }
            _artifacts.value = next
            saveToDisk(next)
            removed = true
        }
        return removed
    }

    fun clearAll() {
        synchronized(lock) {
            _artifacts.value.forEach { a ->
                runCatching { File(a.absolutePath).delete() }
            }
            _artifacts.value = emptyList()
            saveToDisk(emptyList())
        }
    }

    /** 清理磁盘文件已不存在的悬空元数据条目（存储空间清理后调用）。 */
    fun pruneMissing() {
        synchronized(lock) {
            val kept = _artifacts.value.filter { File(it.absolutePath).isFile }
            if (kept.size != _artifacts.value.size) {
                _artifacts.value = kept
                saveToDisk(kept)
            }
        }
    }

    fun openInSystem(context: Context, artifact: PhoneArtifact): Result<Unit> = runCatching {
        val file = File(artifact.absolutePath)
        require(file.isFile) { "文件不存在或已被清理" }
        val uri = fileUri(context, file)
        val mime = mimeForFile(file, artifact.type)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun share(context: Context, artifact: PhoneArtifact): Result<Unit> = runCatching {
        val file = File(artifact.absolutePath)
        require(file.isFile) { "文件不存在或已被清理" }
        val uri = fileUri(context, file)
        val mime = mimeForFile(file, artifact.type)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "分享${artifact.type.label}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun exportToDownloads(context: Context, artifact: PhoneArtifact): File? {
        val file = File(artifact.absolutePath)
        if (!file.isFile) return null
        return EnvKit.exportFileToDownloads(context, file)
    }

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun mimeForFile(file: File, fallbackType: PhoneArtifactType? = null): String {
        val ext = file.extension.lowercase()
        when (ext) {
            "jpg", "jpeg" -> return "image/jpeg"
            "png" -> return "image/png"
            "mp4" -> return "video/mp4"
            "m4a" -> return "audio/mp4"
        }
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (fallbackType) {
            PhoneArtifactType.SCREENSHOT -> "image/jpeg"
            PhoneArtifactType.RECORDING -> "video/mp4"
            PhoneArtifactType.AUDIO -> "audio/mp4"
            null -> "application/octet-stream"
        }
    }

    private fun loadFromDisk(file: File): List<PhoneArtifact> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val abs = o.optString("absolute_path")
                if (abs.isBlank() || !File(abs).isFile) return@mapNotNull null
                val type = runCatching {
                    PhoneArtifactType.valueOf(o.optString("type", "SCREENSHOT"))
                }.getOrDefault(PhoneArtifactType.SCREENSHOT)
                PhoneArtifact(
                    id = o.optString("id"),
                    type = type,
                    fileName = o.optString("file_name"),
                    workspacePath = o.optString("workspace_path"),
                    absolutePath = abs,
                    createdAtMs = o.optLong("created_at_ms", 0L),
                    sizeBytes = File(abs).length(),
                    durationMs = o.optLong("duration_ms", 0L),
                    width = o.optInt("width", 0),
                    height = o.optInt("height", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveToDisk(items: List<PhoneArtifact>) {
        val f = metaFile ?: return
        runCatching {
            val arr = JSONArray()
            items.forEach { a ->
                arr.put(
                    JSONObject().apply {
                        put("id", a.id)
                        put("type", a.type.name)
                        put("file_name", a.fileName)
                        put("workspace_path", a.workspacePath)
                        put("absolute_path", a.absolutePath)
                        put("created_at_ms", a.createdAtMs)
                        put("size_bytes", a.sizeBytes)
                        put("duration_ms", a.durationMs)
                        put("width", a.width)
                        put("height", a.height)
                    },
                )
            }
            f.writeText(arr.toString())
        }
    }
}
