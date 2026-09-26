package com.v4atune.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShellResult(val code: Int, val output: String) {
    val ok: Boolean get() = code == 0
}

object RootShell {
    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            ShellResult(process.waitFor(), output)
        }.getOrElse { e ->
            ShellResult(-1, e::class.simpleName + ": " + e.message)
        }
    }

    suspend fun available(): Boolean {
        val result = exec("id")
        return result.ok && "uid=0" in result.output
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
