package net.harveywilliams.bluetoothbouncer.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.Log
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Entry point for the "shell helper": [BluetoothPolicyBackend] running in our own
 * `app_process`, started as the shell user through a Shizuku shell process
 * (see [ShellChannel]). This is the fallback for phones where Shizuku can't start a
 * UserService.
 *
 * Why a UserService can fail where this works — Shizuku's UserService starter, before any of
 * our code runs:
 *  1. calls `LoadedApk.makeApplication()`, which NPEs on some vendor builds (Xiaomi/HyperOS on
 *     MediaTek, TCL UI — RikkaApps/Shizuku#1198, #2541);
 *  2. hands the service binder back through the Shizuku app's content provider, which fails
 *     ("provider is null") when the ROM won't start that provider, and stays broken after a
 *     UserService has been removed once (thedjchi/Shizuku#201);
 *  3. starts the process in the background with `&`.
 * This helper does none of that: no Application is created, and it talks to the app over
 * stdin/stdout of the process Shizuku started.
 *
 * Protocol (one line each, tab-separated):
 *  - helper → app at startup: `BB 0 ready <uid>` or `BB 0 fatal <message>`
 *  - app → helper: `<id> <op> <mac> <arg>` where op is policy | connect | disconnect | ping
 *  - helper → app: `BB <id> ok <r1,r2,r3>` or `BB <id> err <message>`
 * Only lines starting with `BB` are protocol; anything else on stdout is ignored by the app.
 * The helper exits when stdin closes (the app closed it or died — Shizuku also kills the
 * process when the app that started it dies).
 */
object ShellMain {

    private const val TAG = "BBShellHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        // Write protocol straight to fd 1 so nothing (e.g. a System.out redirect) can swallow it.
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true)
        val packageName = args.firstOrNull()
        try {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
            val context = createContext(packageName)
            Log.i(TAG, "Shell helper starting (uid=${Process.myUid()}, context=${context?.packageName})")
            val backend = BluetoothPolicyBackend(context)

            out.println("BB\t0\tready\t${Process.myUid()}")
            Thread({
                serve(backend, out)
                Log.i(TAG, "stdin closed — exiting")
                exitProcess(0)
            }, "bb-shell-io").start()

            // Profile-proxy callbacks are delivered on the main looper.
            Looper.loop()
        } catch (t: Throwable) {
            Log.e(TAG, "Shell helper failed to start", t)
            out.println("BB\t0\tfatal\t${oneLine(t)}")
            exitProcess(1)
        }
    }

    private fun serve(backend: BluetoothPolicyBackend, out: PrintStream) {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) continue
            val parts = line.split('\t')
            val id = parts.getOrNull(0) ?: continue
            val response = try {
                val op = parts.getOrNull(1)
                val mac = parts.getOrNull(2).orEmpty()
                val results = when (op) {
                    "policy" -> backend.setConnectionPolicy(mac, parts[3].toInt())
                    "connect" -> backend.connectDevice(mac)
                    "disconnect" -> backend.disconnectDevice(mac)
                    "ping" -> intArrayOf(1)
                    else -> throw IllegalArgumentException("unknown op: $op")
                }
                "BB\t$id\tok\t${results.joinToString(",")}"
            } catch (t: Throwable) {
                Log.e(TAG, "Request failed: $line", t)
                "BB\t$id\terr\t${oneLine(t)}"
            }
            out.println(response)
        }
    }

    /**
     * Builds a Context without creating an Application (which is what crashes Shizuku's own
     * UserService starter on some ROMs): a bare ActivityThread's system context, then a package
     * context for our app so `getProfileProxy` sees a modern targetSdkVersion. Falls back to
     * `ActivityThread.systemMain()` if the bare thread can't produce a context.
     *
     * With no Application bound, `AttributionSource.myAttributionSource()` resolves to the
     * shell UID's own package (com.android.shell), which is what the Bluetooth stack checks.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun createContext(packageName: String?): Context? {
        val atClass = Class.forName("android.app.ActivityThread")
        val systemContext: Context? = try {
            val thread = atClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            atClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
        } catch (t: Throwable) {
            Log.w(TAG, "Bare ActivityThread context failed, trying systemMain()", t)
            try {
                val thread = atClass.getDeclaredMethod("systemMain").invoke(null)
                atClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
            } catch (t2: Throwable) {
                Log.e(TAG, "systemMain() context failed", t2)
                null
            }
        }
        if (systemContext == null || packageName.isNullOrEmpty()) return systemContext
        return try {
            // Not createPackageContext(): the system context has no user (ContextImpl
            // .createSystemContext passes null), so that NPEs on user.getIdentifier().
            // Shizuku's own starter passes the user explicitly for the same reason.
            Context::class.java
                .getMethod("createPackageContextAsUser", String::class.java, Int::class.javaPrimitiveType, UserHandle::class.java)
                .invoke(systemContext, packageName, Context.CONTEXT_IGNORE_SECURITY, Process.myUserHandle()) as Context
        } catch (t: Throwable) {
            Log.w(TAG, "createPackageContext($packageName) failed — using system context", t)
            systemContext
        }
    }

    private fun oneLine(t: Throwable): String =
        t.toString().replace('\n', ' ').replace('\t', ' ')
}
