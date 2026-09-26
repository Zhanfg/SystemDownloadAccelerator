package com.v4atune.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ViperPersistence(private val context: Context) {
    private val managerExternal = "/sdcard/Android/data/com.llsl.viper4android/files"

    suspend fun sync(plan: Plan) = withContext(Dispatchers.IO) {
        val own = File(context.getExternalFilesDir(null), "active")
        own.mkdirs()
        File(own, "profile.json").writeText(plan.profile.toString(2))

        plan.kernel?.let { kernel ->
            val local = File(context.cacheDir, "V4ATune_Speaker.wav")
            WavFiles.writeMonoFloat(local, kernel, ViperControl.status()?.sampleRate ?: 48000)
            RootShell.exec(
                "mkdir -p " + RootShell.quote(managerExternal + "/Kernel") + " && " +
                    "cp " + RootShell.quote(local.absolutePath) + " " +
                    RootShell.quote(managerExternal + "/Kernel/V4ATune_Speaker.wav") + " && " +
                    "chmod 0644 " + RootShell.quote(managerExternal + "/Kernel/V4ATune_Speaker.wav"),
            )
        }

        if (plan.ddc44 != null && plan.ddc48 != null) {
            val local = File(context.cacheDir, "V4ATune_Speaker.vdc")
            VdcFiles.write(local, plan.ddc44, plan.ddc48)
            RootShell.exec(
                "mkdir -p " + RootShell.quote(managerExternal + "/DDC") + " && " +
                    "cp " + RootShell.quote(local.absolutePath) + " " +
                    RootShell.quote(managerExternal + "/DDC/V4ATune_Speaker.vdc") + " && " +
                    "chmod 0644 " + RootShell.quote(managerExternal + "/DDC/V4ATune_Speaker.vdc"),
            )
        }
    }

    suspend fun restoreLast(): Boolean = false
}
