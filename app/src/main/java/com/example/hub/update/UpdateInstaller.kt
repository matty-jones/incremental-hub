package com.example.hub.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.example.hub.BuildConfig
import com.example.hub.sync.UpdateInfo
import com.example.hub.sync.WorkstationApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UpdateInstaller(
    private val context: Context,
    private val api: WorkstationApi,
) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { api.checkUpdate() }.getOrNull()
    }

    suspend fun downloadAndInstall(info: UpdateInfo): Boolean {
        val file = File(context.cacheDir, "update.apk")
        val downloaded = withContext(Dispatchers.IO) {
            runCatching { api.download(info.downloadUrl, file) }.getOrDefault(false)
        }
        if (!downloaded) return false
        return withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "installer did not start", it) }
                .isSuccess
        }
    }

    private companion object {
        const val TAG = "UpdateInstaller"
    }
}
