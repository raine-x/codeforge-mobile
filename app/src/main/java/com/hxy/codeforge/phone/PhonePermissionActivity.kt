package com.hxy.codeforge.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 手机操控透明授权桥接 Activity：
 * - 屏幕捕获 `MediaProjection` 系统弹窗授权；
 * - 运行时危险权限申请（麦克风、日历、联系人、短信等）；
 * - 系统文件选择器 `ACTION_OPEN_DOCUMENT`。
 */
internal object PhonePermissionBridge {

    data class ProjectionConsent(val resultCode: Int, val data: Intent)

    private val lock = Any()
    private var projectionWaiter: CompletableDeferred<ProjectionConsent?>? = null
    private var permWaiter: CompletableDeferred<Map<String, Boolean>>? = null
    private var pickerWaiter: CompletableDeferred<Uri?>? = null

    suspend fun requestScreenCapture(context: Context, timeoutMs: Long = 45_000L): ProjectionConsent? {
        val waiter = CompletableDeferred<ProjectionConsent?>()
        synchronized(lock) {
            projectionWaiter?.complete(null)
            projectionWaiter = waiter
        }
        val intent = Intent(context, PhonePermissionActivity::class.java)
            .putExtra(PhonePermissionActivity.EXTRA_MODE, PhonePermissionActivity.MODE_PROJECTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchHostIntent(context, intent)
        return withTimeoutOrNull(timeoutMs) { waiter.await() }
    }

    suspend fun requestPermissions(
        context: Context,
        permissions: Array<String>,
        timeoutMs: Long = 35_000L,
    ): Map<String, Boolean> {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            return permissions.associateWith { true }
        }
        val waiter = CompletableDeferred<Map<String, Boolean>>()
        synchronized(lock) {
            permWaiter?.complete(emptyMap())
            permWaiter = waiter
        }
        val intent = Intent(context, PhonePermissionActivity::class.java)
            .putExtra(PhonePermissionActivity.EXTRA_MODE, PhonePermissionActivity.MODE_PERMISSIONS)
            .putExtra(PhonePermissionActivity.EXTRA_PERMS, missing.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchHostIntent(context, intent)
        val granted = withTimeoutOrNull(timeoutMs) { waiter.await() }.orEmpty()
        return permissions.associateWith { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED ||
                (granted[perm] == true)
        }
    }

    suspend fun requestPickDocument(
        context: Context,
        mimeType: String = "*/*",
        timeoutMs: Long = 60_000L,
    ): Uri? {
        val waiter = CompletableDeferred<Uri?>()
        synchronized(lock) {
            pickerWaiter?.complete(null)
            pickerWaiter = waiter
        }
        val intent = Intent(context, PhonePermissionActivity::class.java)
            .putExtra(PhonePermissionActivity.EXTRA_MODE, PhonePermissionActivity.MODE_PICK_FILE)
            .putExtra(PhonePermissionActivity.EXTRA_MIME, mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchHostIntent(context, intent)
        return withTimeoutOrNull(timeoutMs) { waiter.await() }
    }

    private fun launchHostIntent(context: Context, intent: Intent) {
        // 若无障碍服务在线，由无障碍服务上下文拉起可绕过后台启动 Activity 限制
        val starter: Context = PhoneObservationService.active ?: context
        starter.startActivity(intent)
    }

    internal fun deliverProjection(result: ProjectionConsent?) {
        synchronized(lock) {
            projectionWaiter?.complete(result)
            projectionWaiter = null
        }
    }

    internal fun deliverPermissions(map: Map<String, Boolean>) {
        synchronized(lock) {
            permWaiter?.complete(map)
            permWaiter = null
        }
    }

    internal fun deliverPickedUri(uri: Uri?) {
        synchronized(lock) {
            pickerWaiter?.complete(uri)
            pickerWaiter = null
        }
    }
}

class PhonePermissionActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PERMS = "perms"
        const val EXTRA_MIME = "mime"
        const val MODE_PROJECTION = "projection"
        const val MODE_PERMISSIONS = "permissions"
        const val MODE_PICK_FILE = "pick_file"

        private const val REQ_PROJECTION = 5001
        private const val REQ_PERMS = 5002
        private const val REQ_PICK = 5003
    }

    private var dispatchedMode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleMode(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleMode(intent)
    }

    private fun handleMode(intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        dispatchedMode = mode
        when (mode) {
            MODE_PROJECTION -> {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                if (mgr == null) {
                    PhonePermissionBridge.deliverProjection(null)
                    finish()
                    return
                }
                @Suppress("DEPRECATION")
                startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
            }
            MODE_PERMISSIONS -> {
                val perms = intent.getStringArrayExtra(EXTRA_PERMS).orEmpty()
                if (perms.isEmpty()) {
                    PhonePermissionBridge.deliverPermissions(emptyMap())
                    finish()
                    return
                }
                ActivityCompat.requestPermissions(this, perms, REQ_PERMS)
            }
            MODE_PICK_FILE -> {
                val mime = intent.getStringExtra(EXTRA_MIME).orEmpty().ifBlank { "*/*" }
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                @Suppress("DEPRECATION")
                startActivityForResult(pick, REQ_PICK)
            }
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    PhonePermissionBridge.deliverProjection(PhonePermissionBridge.ProjectionConsent(resultCode, data))
                } else {
                    PhonePermissionBridge.deliverProjection(null)
                }
                finish()
            }
            REQ_PICK -> {
                PhonePermissionBridge.deliverPickedUri(if (resultCode == RESULT_OK) data?.data else null)
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val map = permissions.indices.associate { idx ->
                permissions[idx] to (grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED)
            }
            PhonePermissionBridge.deliverPermissions(map)
            finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            when (dispatchedMode) {
                MODE_PROJECTION -> PhonePermissionBridge.deliverProjection(null)
                MODE_PERMISSIONS -> PhonePermissionBridge.deliverPermissions(emptyMap())
                MODE_PICK_FILE -> PhonePermissionBridge.deliverPickedUri(null)
            }
        }
        super.onDestroy()
    }
}
