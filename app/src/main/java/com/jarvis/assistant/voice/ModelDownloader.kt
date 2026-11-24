package com.jarvis.assistant.voice

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Downloads and unpacks the on-device voice models. */
object ModelDownloader {
    private val client = OkHttpClient.Builder().followSslRedirects(false).build()
    private const val WAKE_SHA256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
    private const val SPEECH_SHA256 = "c8676e5ff9ac2a85296e53ee0fd4d5fb1db6770e7a7647166eeafe349ade6834"
    private const val KOKORO_SHA256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"

    /** The "Hey Jarvis" keyword model: a 17 MB download, of which only ~5 MB of int8 files are kept. */
    fun downloadWakeWord(context: Context, progress: (Int) -> Unit) {
        download(context, WakeModel.URL, WakeModel.dir(context), WAKE_SHA256, progress, ::openTarBz2)
        prune(WakeModel.dir(context), WakeModel.KEEP_FILES)
    }

    /** Offline speech recognition for commands (57 MB download). */
    fun downloadSpeech(context: Context, progress: (Int) -> Unit) {
        download(context, SpeechModel.URL, SpeechModel.dir(context), SPEECH_SHA256, progress, ::openTarBz2)
        prune(SpeechModel.dir(context), SpeechModel.KEEP_FILES)
    }

    /** Deletes everything in [dir] except [keep] (sample audio, unused model variants). */
    internal fun prune(dir: File, keep: Set<String>) =
        dir.walkBottomUp().filter { it != dir && it.name !in keep }.forEach { it.delete() }

    /** The ~100 MB Kokoro voice. */
    fun downloadKokoro(context: Context, progress: (Int) -> Unit) =
        download(context, Kokoro.MODEL_URL, Kokoro.modelDir(context), KOKORO_SHA256, progress, ::openTarBz2)

    private fun download(
        context: Context,
        url: String,
        target: File,
        expectedSha256: String,
        progress: (Int) -> Unit,
        open: (InputStream) -> ArchiveInputStream<*>,
    ) {
        val tmp = File.createTempFile("${target.name}-", ".download", context.cacheDir)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 90 / total).toInt() else 0
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != expectedSha256) {
                throw IOException("Model download did not match the expected SHA-256")
            }
            unpack(tmp, target, open)
        } finally {
            tmp.delete()
        }
        progress(100)
    }

    /** Unpacks [file] into [target], dropping the archive's top-level folder. */
    internal fun unpack(file: File, target: File, open: (InputStream) -> ArchiveInputStream<*>) {
        // Unpack next to the target, then swap in, so a failed unpack never leaves a half-installed model.
        val previous = File(target.parentFile, target.name + ".previous")
        if (previous.exists()) {
            // Recover a swap interrupted by process death before attempting a new download install.
            if (target.exists()) {
                if (!previous.deleteRecursively()) throw IOException("Could not clear previous model")
            } else if (!previous.renameTo(target)) throw IOException("Could not restore previous model")
        }
        val staging = File(target.parentFile, target.name + ".new")
        if (!staging.deleteRecursively() || !staging.mkdirs()) throw IOException("Could not stage model")
        val root = staging.canonicalPath + File.separator
        try {
            file.inputStream().use { input -> open(input).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    // Strip the archive's top-level folder.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val file = File(staging, relative)
                    if (!file.canonicalPath.startsWith(root)) throw IOException("Bad archive entry ${entry.name}")
                    if (entry.isDirectory) file.mkdirs()
                    else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { archive.copyTo(it) }
                    }
                }
            } }
            if (target.exists() && !target.renameTo(previous)) throw IOException("Could not retain installed model")
            if (!staging.renameTo(target)) {
                if (previous.exists() && !previous.renameTo(target)) throw IOException("Model restore pending")
                throw IOException("Could not install model")
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    internal fun openTarBz2(input: InputStream): ArchiveInputStream<*> =
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered()))
}
