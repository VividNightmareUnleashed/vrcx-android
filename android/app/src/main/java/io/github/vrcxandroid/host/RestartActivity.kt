package io.github.vrcxandroid.host

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import kotlin.system.exitProcess

/**
 * Restart trampoline. Runs in the separate `:restart` process: it makes sure the old main
 * process is gone, starts MainActivity (which then runs in a fresh main process, so every native singleton, the proxy
 * and the database are re-initialised) and ends its own process.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        if (mainPid > 0 && mainPid != Process.myPid()) Process.killProcess(mainPid)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    companion object {
        private const val EXTRA_MAIN_PID = "main_pid"
        private const val PROCESS_SUFFIX = ":restart"

        /** Called by the main process right before it exits. */
        fun start(context: Context, mainPid: Int) {
            context.startActivity(
                Intent(context, RestartActivity::class.java)
                    .putExtra(EXTRA_MAIN_PID, mainPid)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** True in the `:restart` process, where Application.onCreate must not initialise the app. */
        fun isRestartProcess(context: Context): Boolean {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else currentProcessName(context)
            return name?.endsWith(PROCESS_SUFFIX) == true
        }

        private fun currentProcessName(context: Context): String? {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
            val pid = Process.myPid()
            return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
    }
}
