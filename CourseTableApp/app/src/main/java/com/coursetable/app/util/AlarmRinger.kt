package com.coursetable.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** 闹钟铃声：内置（assets/铃声/）+ 自定义（content URI）。 */
object AlarmRinger {

    const val ASSET_DIR = "铃声"

    /** 内置铃声文件名列表（升序）。 */
    fun builtInRingtones(context: Context): List<String> =
        runCatching {
            context.assets.list(ASSET_DIR)
                ?.filter { it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".ogg") }
                ?.sorted() ?: emptyList()
        }.getOrDefault(emptyList())

    fun defaultRingtone(context: Context): String =
        builtInRingtones(context).firstOrNull() ?: ""

    /** 循环播放铃声，返回 MediaPlayer；失败返回 null。 */
    fun play(context: Context, ringtone: String): MediaPlayer? {
        return try {
            val mp = MediaPlayer()
            when {
                ringtone.startsWith("uri:") ->
                    mp.setDataSource(context, Uri.parse(ringtone.removePrefix("uri:")))
                else -> {
                    val name = when {
                        ringtone.startsWith("asset:") -> ringtone.removePrefix("asset:")
                        ringtone.isNotBlank() -> ringtone
                        else -> defaultRingtone(context)
                    }
                    val afd = context.assets.openFd("$ASSET_DIR/$name")
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
            }
            mp.isLooping = true
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            null
        }
    }

    /** 当前铃声的显示名。 */
    fun displayName(context: Context, ringtone: String, customName: String): String =
        when {
            ringtone.startsWith("uri:") -> customName.ifBlank { "自定义铃声" }
            ringtone.startsWith("asset:") -> ringtone.removePrefix("asset:")
            ringtone.isNotBlank() -> ringtone
            else -> defaultRingtone(context).ifBlank { "默认" }
        }
}
