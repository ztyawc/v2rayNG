package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal root command runner backed by the `su` binary.
 *
 * Scripts are written to the app's private root runtime dir and executed with
 * `su -c sh <file>` so shell quoting stays simple. stderr is merged into stdout to
 * avoid pipe-buffer deadlocks.
 */
object RootShell {

    data class Result(val code: Int, val output: String) {
        val success: Boolean get() = code == 0
    }

    /** Write a private, per-attempt [script] and run it as root. */
    fun runScript(context: Context, name: String, script: String, timeoutMillis: Long = 30_000): Result {
        val dir = File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR).apply { mkdirs() }
        val attempt = UUID.randomUUID()
        val pidFile = File(dir, "$name.$attempt.pid")
        val safePid = pidFile.absolutePath.replace("'", "'\\''")
        val leaseFile = File(dir, "$name.$attempt.lease").apply { writeText("") }
        val safeLease = leaseFile.absolutePath.replace("'", "'\\''")
        val file = File(dir, "$name.$attempt.sh").apply {
            // The root-side lease also rejects delayed su approval after Android cancelled setup.
            writeText("""
                [ -f '$safeLease' ] || exit 1
                echo ${'$'}${'$'} > '$safePid'
                group=${'$'}${'$'}
                (
                    ticks=0
                    while [ -f '$safeLease' ] && [ ${'$'}ticks -lt ${(timeoutMillis / 100).coerceAtLeast(1)} ]; do
                        sleep 0.1
                        ticks=${'$'}((ticks + 1))
                    done
                    kill -KILL -- -${'$'}group
                ) >/dev/null 2>&1 &
                watchdog=${'$'}!
                trap 'kill ${'$'}watchdog 2>/dev/null || true' EXIT
            """.trimIndent() + "\n" + script)
            setExecutable(true, false)
        }
        // Android API 24+ ships toybox setsid. A private process group bounds script children
        // as well as su; remove this adapter when root scripts no longer spawn shell processes.
        try {
            return execute(scriptCommand(file, leaseFile), timeoutMillis) {
                leaseFile.delete()
                val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                if (pid != null && pid > 1) {
                    execute(listOf("su", "-c", "kill -KILL -- -$pid"), 500)
                }
            }
        } finally {
            pidFile.delete()
            leaseFile.delete()
            file.delete()
        }
    }

    internal fun scriptCommand(file: File, leaseFile: File): List<String> {
        val safePath = file.absolutePath.replace("'", "'\\''")
        val safeLease = leaseFile.absolutePath.replace("'", "'\\''")
        // Validate the original lease before reading the script, even after delayed su approval.
        return listOf("su", "-c", "[ -f '$safeLease' ] || exit 1; exec setsid sh '$safePath'")
    }

    internal fun execute(command: List<String>, timeoutMillis: Long, onAbort: () -> Unit = {}): Result {
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Root shell process start failed", IOException(error.javaClass.simpleName))
            return Result(-1, "")
        }
        val output = ByteArrayOutputStream()
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = 64 * 1024 - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            } catch (_: IOException) {
                // Aborting the owned process closes its output pipe.
            }
        }, "root-command-output").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var finished = false
        var interrupted = false
        var code = -1
        try {
            // Poll exitValue on API 24/25, where timed Process.waitFor is unavailable.
            while (System.nanoTime() < deadline) {
                try {
                    code = process.exitValue()
                    finished = true
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(10)
                }
            }
        } catch (_: InterruptedException) {
            interrupted = true
        } finally {
            if (!finished) {
                try { onAbort() } finally { process.destroy() }
            }
            try {
                reader.join(100)
            } catch (_: InterruptedException) {
                interrupted = true
            }
            // A descendant can inherit stdout. Closing a pipe while its reader owns the Java
            // stream monitor can itself block; the root watchdog closes the process group.
            if (!reader.isAlive) process.inputStream.close()
            process.outputStream.close()
            process.errorStream.close()
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (interrupted) throw InterruptedException("Root command cancelled")
        if (!finished || code != 0) LogUtil.w(AppConfig.TAG, "Root shell command failed exit=$code timeout=${!finished}", IOException("Root command failure"))
        return Result(code, synchronized(output) { output.toString("UTF-8") })
    }
}
