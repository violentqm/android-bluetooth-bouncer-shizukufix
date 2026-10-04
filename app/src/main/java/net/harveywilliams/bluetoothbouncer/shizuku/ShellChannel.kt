package net.harveywilliams.bluetoothbouncer.shizuku

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

/**
 * A way to reach [BluetoothPolicyBackend] running as the shell user.
 * Implemented by the Shizuku UserService (AIDL) and by the shell helper ([ShellChannel]).
 * Calls block and must not run on the main thread.
 */
interface PolicyChannel {
    /** Short name for logs and diagnostics. */
    val kind: String
    fun isAlive(): Boolean
    fun setConnectionPolicy(macAddress: String, policy: Int): IntArray
    fun connectDevice(macAddress: String): IntArray
    fun disconnectDevice(macAddress: String): IntArray
}

/** The channel's process has gone away; the caller should get a new channel and retry. */
class ChannelDeadException(message: String) : IllegalStateException(message)

/**
 * App side of the shell helper ([ShellMain]): starts our own `app_process` as the shell user via
 * Shizuku's shell-process API and exchanges one-line requests/responses over its stdin/stdout.
 *
 * Shizuku destroys the process when this app's process dies, and the helper exits when its
 * stdin closes, so no helper outlives the app.
 */
class ShellChannel private constructor(private val process: Process) : PolicyChannel {

    override val kind = "shell helper"

    private val writer: BufferedWriter = process.outputStream.bufferedWriter()
    private val lines = LinkedBlockingQueue<String>()
    private val nextId = AtomicInteger(1)
    private val stderrTail = ArrayDeque<String>()

    @Volatile private var closed = false

    init {
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith("BB\t")) lines.put(line) else Log.d(TAG, "helper stdout: $line")
                }
            } catch (e: Exception) {
                Log.w(TAG, "helper stdout read failed", e)
            } finally {
                closed = true
                lines.put(EOF)
            }
        }, "bb-shell-stdout").apply { isDaemon = true }.start()

        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Log.w(TAG, "helper stderr: $line")
                    synchronized(stderrTail) {
                        stderrTail.addLast(line)
                        while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                    }
                }
            } catch (_: Exception) {
            }
        }, "bb-shell-stderr").apply { isDaemon = true }.start()
    }

    override fun isAlive(): Boolean = !closed

    override fun setConnectionPolicy(macAddress: String, policy: Int): IntArray =
        call("policy", macAddress, policy.toString())

    override fun connectDevice(macAddress: String): IntArray = call("connect", macAddress, "")

    override fun disconnectDevice(macAddress: String): IntArray = call("disconnect", macAddress, "")

    fun destroy() {
        closed = true
        try {
            writer.close()
        } catch (_: Exception) {
        }
        try {
            process.destroy()
        } catch (_: Exception) {
        }
    }

    /** Last lines the helper wrote to stderr — usually the reason it died. */
    fun stderrSummary(): String = synchronized(stderrTail) { stderrTail.joinToString(" | ") }

    @Synchronized
    private fun call(op: String, macAddress: String, arg: String): IntArray {
        if (closed) throw ChannelDeadException("shell helper has exited")
        val id = nextId.getAndIncrement()
        try {
            writer.write("$id\t$op\t$macAddress\t$arg\n")
            writer.flush()
        } catch (e: Exception) {
            destroy()
            throw ChannelDeadException("shell helper stopped accepting requests: $e")
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CALL_TIMEOUT_MS)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val line = lines.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: run {
                    destroy()
                    throw ChannelDeadException("shell helper didn't answer within ${CALL_TIMEOUT_MS / 1000}s")
                }
            if (line == EOF) {
                lines.put(EOF) // keep the marker for later callers
                throw ChannelDeadException("shell helper exited: ${stderrSummary().ifEmpty { "no output" }}")
            }
            val parts = line.split('\t', limit = 4)
            if (parts.getOrNull(1)?.toIntOrNull() != id) continue // stale reply to a timed-out call
            val payload = parts.getOrNull(3).orEmpty()
            return when (parts.getOrNull(2)) {
                "ok" -> payload.split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }.toIntArray()
                else -> throw IllegalStateException(payload.ifEmpty { "shell helper error" })
            }
        }
    }

    /** Waits for the helper's startup line. Returns null when ready, otherwise the reason it isn't. */
    private fun awaitReady(): String? {
        val line = lines.poll(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: return "the helper didn't start within ${READY_TIMEOUT_MS / 1000}s"
        if (line == EOF) return "the helper exited at startup: ${stderrSummary().ifEmpty { "no output" }}"
        val parts = line.split('\t', limit = 4)
        return when (parts.getOrNull(2)) {
            "ready" -> {
                Log.i(TAG, "Shell helper ready (uid=${parts.getOrNull(3)})")
                null
            }
            else -> "the helper failed at startup: ${parts.getOrNull(3).orEmpty()}"
        }
    }

    companion object {
        private const val TAG = "BBShellChannel"
        private const val EOF = "\u0000EOF"
        private const val READY_TIMEOUT_MS = 20_000L

        /** The backend waits up to 8s per profile for its proxies on first use, three profiles. */
        private const val CALL_TIMEOUT_MS = 40_000L
        private const val STDERR_TAIL_LINES = 20

        /**
         * Starts the helper and waits until it reports ready. Blocking — call off the main thread.
         * Throws [IllegalStateException] with a user-presentable reason if it can't start.
         */
        fun start(context: Context): ShellChannel {
            val apk = context.applicationInfo.sourceDir
            val pkg = context.packageName
            // `sh -c` so the helper inherits the shell environment (BOOTCLASSPATH etc. that
            // app_process needs) with only CLASSPATH added.
            val command = "CLASSPATH='$apk' exec /system/bin/app_process /system/bin " +
                "--nice-name='$pkg:shell' ${ShellMain::class.java.name} '$pkg'"
            val process = try {
                newShizukuProcess(arrayOf("/system/bin/sh", "-c", command))
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                throw IllegalStateException("Shizuku couldn't start a shell process: $cause", cause)
            }
            val channel = ShellChannel(process)
            val problem = channel.awaitReady()
            if (problem != null) {
                channel.destroy()
                throw IllegalStateException(problem)
            }
            return channel
        }

        /**
         * Runs [command] as the Shizuku user (shell). `Shizuku.newProcess` is private in API 13
         * (Shizuku wants new code to use UserServices) but the server still serves it — it's
         * what Shizuku's own `rish` terminal uses — so it's called reflectively.
         */
        fun newShizukuProcess(command: Array<String>): Process {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            return method.invoke(null, command, null, null) as Process
        }
    }
}
