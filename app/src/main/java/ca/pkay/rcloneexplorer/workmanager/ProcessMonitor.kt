package ca.pkay.rcloneexplorer.workmanager

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Drain both pipes so a full stdout pipe cannot deadlock a stderr-driven worker. */
internal object ProcessMonitor {
    @Throws(IOException::class, InterruptedException::class)
    fun await(process: Process, onErrorLine: (String) -> Unit): Int {
        val stdoutError = AtomicReference<IOException?>()
        val drainer = Thread({
            try {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (stream.read(buffer) != -1) { /* stdout is not progress */ }
                }
            } catch (e: IOException) {
                stdoutError.set(e)
                process.destroy()
            }
        }, "rclone-stdout")
        drainer.isDaemon = true
        drainer.start()
        try {
            process.errorStream.bufferedReader().use { reader ->
                val line = StringBuilder()
                while (true) {
                    val next = reader.read()
                    if (next == -1) {
                        if (line.isNotEmpty()) onErrorLine(line.toString())
                        break
                    }
                    if (next == '\n'.code) {
                        onErrorLine(line.toString().removeSuffix("\r"))
                        line.setLength(0)
                    } else {
                        if (line.length >= 65536) throw IOException("Child progress line exceeded the size limit")
                        line.append(next.toChar())
                    }
                }
            }
            val exit = process.waitFor()
            drainer.join(5000)
            if (drainer.isAlive) throw IOException("Child stdout did not close")
            stdoutError.get()?.let { throw it }
            return exit
        } finally {
            process.destroy()
            // Each pipe must be closed even when another close fails.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            if (drainer.isAlive) {
                drainer.interrupt()
                drainer.join(1000)
            }
        }
    }
}
