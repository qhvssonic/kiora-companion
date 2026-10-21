package dev.kiora.companion

import android.content.Context
import android.net.Uri
import java.io.File

/** SenseVoice weights are provided by the user and kept outside the APK. */
internal class VoiceModelFiles(private val context: Context) {
    private val directory = File(context.filesDir, "sensevoice")
    val model get() = File(directory, "model.int8.onnx")
    val tokens get() = File(directory, "tokens.txt")
    val ready get() = model.isFile && model.length() > 0 && tokens.isFile && tokens.length() > 0

    fun import(uri: Uri, modelFile: Boolean) {
        directory.mkdirs()
        val target = if (modelFile) model else tokens
        val pending = File(directory, "${target.name}.pending")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                pending.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
            require(pending.length() > 0) { "文件为空" }
            check(pending.renameTo(target)) { "无法保存语音模型文件" }
        } finally {
            pending.delete()
        }
    }
}
