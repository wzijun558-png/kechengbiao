package com.coursetable.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.coursetable.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 联网自检测更新（GitHub Releases 托管，兼顾成本）：
 *  - 更新清单 = 仓库根目录的静态 update.json（raw 链接，免费、几乎零流量）；
 *  - APK = GitHub Release 资源（release 直链，免费 CDN）；
 *  - 清单字段：{ "versionCode":2, "versionName":"1.1", "note":"...", "apkUrl":"...", "size":7017122, "md5":"..." }
 *  - size/md5 可选：用于下载前提示大小、下载后校验完整性。
 */
object UpdateChecker {

    // GitHub 仓库
    private const val GITHUB_OWNER = "wzijun558-png"
    private const val GITHUB_REPO = "kechengbiao"
    private const val MANIFEST_BRANCH = "main"

    /** 更新清单地址：仓库根目录 update.json 的 raw 链接。 */
    const val DEFAULT_UPDATE_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$MANIFEST_BRANCH/update.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val note: String,
        val apkUrl: String,
        val size: Long = 0L,
        val md5: String = ""
    ) {
        fun isNewer(): Boolean = versionCode > BuildConfig.VERSION_CODE

        val sizeText: String
            get() = if (size > 0) formatSize(size) else ""

        /** 弹窗文案：更新说明 + 安装包大小 + 下载询问。 */
        fun promptText(): String = buildString {
            if (note.isNotBlank()) append(note).append("\n\n")
            if (sizeText.isNotEmpty()) append("安装包大小：").append(sizeText).append('\n')
            append("是否下载更新？")
        }
    }

    /** 在线检查；失败抛出异常。 */
    @Throws(Exception::class)
    fun fetch(): UpdateInfo {
        if (DEFAULT_UPDATE_URL.isBlank()) throw IllegalStateException("未配置更新服务器地址（UpdateChecker.DEFAULT_UPDATE_URL）")
        val conn = URL(DEFAULT_UPDATE_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "CourseTable/" + BuildConfig.VERSION_NAME)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("更新服务器返回 $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            return UpdateInfo(
                versionCode = o.optInt("versionCode", BuildConfig.VERSION_CODE),
                versionName = o.optString("versionName", BuildConfig.VERSION_NAME),
                note = o.optString("note", ""),
                apkUrl = o.optString("apkUrl", ""),
                size = o.optLong("size", 0L),
                md5 = o.optString("md5", "").trim()
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 后台线程检查，结果回调主线程。 */
    fun checkAsync(context: Context, onResult: (UpdateInfo?) -> Unit) {
        Thread {
            val info = runCatching { fetch() }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(info) }
        }.start()
    }

    /** 下载、校验(md5，可选)并拉起安装（需用户允许“安装未知应用”）。 */
    fun downloadAndInstall(context: Context, info: UpdateInfo): Boolean {
        val url = info.apkUrl
        if (url.isBlank()) return false
        return try {
            val target = File(context.cacheDir, "coursetable-update.apk")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "CourseTable/" + BuildConfig.VERSION_NAME)
                if (conn.responseCode !in 200..299) return false
                conn.inputStream.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            } finally {
                conn.disconnect()
            }
            if (info.md5.isNotBlank()) {
                val actual = md5Of(target)
                if (!actual.equals(info.md5, ignoreCase = true)) {
                    target.delete()
                    Toast.makeText(context, "下载校验失败（md5 不一致），请重试", Toast.LENGTH_LONG).show()
                    return false
                }
            }
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                target
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "下载/安装失败：${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun md5Of(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun formatSize(bytes: Long): String =
        when {
            bytes >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1 shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
}
