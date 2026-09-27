package com.v4atune.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

class ViperPersistence(private val context: Context) {
    companion object {
        private const val managerPkg = "com.llsl.viper4android"
        private const val appPrefs = "v4atune"
        private const val lastBackupKey = "last_backup"
    }

    data class Paths(
        val dataStore: String?,
        val roomDb: String?,
        val external: String = "/sdcard/Android/data/" + managerPkg + "/files",
    )

    suspend fun sync(plan: Plan) = withContext(Dispatchers.IO) {
        val paths = locate()
        backup(paths)
        stageWithPaths(paths, plan)
    }

    /**
     * RAW calibration must not traverse an existing ViPER AudioEffect.
     * Force-stopping the manager releases both global and per-session effects,
     * while leaving the native driver/module installed.
     */
    suspend fun beginRawBypass() = withContext(Dispatchers.IO) {
        stopManager()
        delay(450)
    }

    /**
     * Persist a calibration plan without changing the user's routing policy.
     * In particular this never changes master_enable, global_mode or auto_start.
     */
    suspend fun stage(plan: Plan) = withContext(Dispatchers.IO) {
        stageWithPaths(locate(), plan)
    }

    suspend fun resumeManager() = withContext(Dispatchers.IO) {
        launchManager()
    }

    private suspend fun stageWithPaths(paths: Paths, plan: Plan) {
        stopManager()
        try {
            paths.dataStore?.let { patchDataStore(it, plan) }
            paths.roomDb?.let { patchRoom(it, plan) }
            installBulkFiles(paths, plan)
        } finally {
            launchManager()
        }
    }

    suspend fun restoreLast(): Boolean = withContext(Dispatchers.IO) {
        val backupPath = context.getSharedPreferences(appPrefs, Context.MODE_PRIVATE)
            .getString(lastBackupKey, null) ?: return@withContext false
        val backup = File(backupPath)
        if (!backup.isDirectory) return@withContext false
        val paths = locate()

        stopManager()
        var restored = false
        try {
            val prefsFile = File(backup, "viper_preferences.preferences_pb")
            if (prefsFile.isFile && paths.dataStore != null) {
                val r = RootShell.exec(
                    "cat " + RootShell.quote(prefsFile.absolutePath) +
                        " > " + RootShell.quote(paths.dataStore) +
                        "; restorecon " + RootShell.quote(paths.dataStore) + " 2>/dev/null || true",
                )
                restored = restored || r.ok
            }

            val dbFile = File(backup, "viper4android.db")
            if (dbFile.isFile && paths.roomDb != null) {
                val r = RootShell.exec(
                    "cat " + RootShell.quote(dbFile.absolutePath) +
                        " > " + RootShell.quote(paths.roomDb) +
                        "; rm -f " + RootShell.quote(paths.roomDb + "-wal") +
                        " " + RootShell.quote(paths.roomDb + "-shm") +
                        "; restorecon " + RootShell.quote(paths.roomDb) + " 2>/dev/null || true",
                )
                restored = restored || r.ok
            }
        } finally {
            launchManager()
        }
        restored
    }

    suspend fun locate(): Paths = withContext(Dispatchers.IO) {
        val pm = RootShell.exec(
            "dumpsys package " + RootShell.quote(managerPkg) +
                " 2>/dev/null | sed -n 's/^[[:space:]]*dataDir=//p' | head -n1",
        )
        val base = pm.output.takeIf { pm.ok && it.isNotBlank() }

        val prefsCandidates = buildList {
            base?.let { add(it + "/files/datastore/viper_preferences.preferences_pb") }
            add("/data/user/0/" + managerPkg + "/files/datastore/viper_preferences.preferences_pb")
            add("/data/data/" + managerPkg + "/files/datastore/viper_preferences.preferences_pb")
        }
        var dataStore = prefsCandidates.firstOrNull { rootExists(it) }
        if (dataStore == null) {
            val found = RootShell.exec(
                "find /data/user /data/user_de /data/data /data_mirror/data_ce /data_mirror/data_de " +
                    "-maxdepth 10 -type f -name 'viper_preferences.preferences_pb' " +
                    "-path '*/" + managerPkg + "/*' 2>/dev/null | head -n1",
            )
            dataStore = found.output.takeIf { found.ok && it.isNotBlank() }
        }

        val dbCandidates = buildList {
            base?.let { add(it + "/databases/viper4android.db") }
            add("/data/user/0/" + managerPkg + "/databases/viper4android.db")
            add("/data/data/" + managerPkg + "/databases/viper4android.db")
        }
        var room = dbCandidates.firstOrNull { rootExists(it) }
        if (room == null) {
            val found = RootShell.exec(
                "find /data/user /data/user_de /data/data /data_mirror/data_ce /data_mirror/data_de " +
                    "-maxdepth 10 -type f -name 'viper4android.db' " +
                    "-path '*/" + managerPkg + "/*' 2>/dev/null | head -n1",
            )
            room = found.output.takeIf { found.ok && it.isNotBlank() }
        }

        Paths(dataStore = dataStore, roomDb = room)
    }

    private suspend fun rootExists(path: String): Boolean =
        RootShell.exec("test -f " + RootShell.quote(path) + " && echo yes")
            .output.contains("yes")

    private suspend fun backup(paths: Paths) {
        val dir = File(context.getExternalFilesDir(null), "backup/" + System.currentTimeMillis())
        dir.mkdirs()
        val uid = Process.myUid()

        paths.dataStore?.let { source ->
            val dest = File(dir, "viper_preferences.preferences_pb")
            RootShell.exec(
                "cp " + RootShell.quote(source) + " " + RootShell.quote(dest.absolutePath) +
                    " && chown " + uid + ":" + uid + " " + RootShell.quote(dest.absolutePath),
            )
        }
        paths.roomDb?.let { source ->
            val dest = File(dir, "viper4android.db")
            RootShell.exec(
                "cp " + RootShell.quote(source) + " " + RootShell.quote(dest.absolutePath) +
                    " && chown " + uid + ":" + uid + " " + RootShell.quote(dest.absolutePath),
            )
        }
        File(dir, "paths.txt").writeText(
            "dataStore=" + (paths.dataStore ?: "") + "\n" +
                "roomDb=" + (paths.roomDb ?: "") + "\n",
        )
        context.getSharedPreferences(appPrefs, Context.MODE_PRIVATE)
            .edit().putString(lastBackupKey, dir.absolutePath).apply()
    }

    private suspend fun patchDataStore(path: String, plan: Plan) {
        val local = File(context.cacheDir, "viper_preferences.preferences_pb")
        val uid = Process.myUid()
        val copy = RootShell.exec(
            "cp " + RootShell.quote(path) + " " + RootShell.quote(local.absolutePath) +
                " && chown " + uid + ":" + uid + " " + RootShell.quote(local.absolutePath),
        )
        check(copy.ok) { "无法读取 ViPER DataStore: " + copy.output }

        // Do not touch master_enable / global_mode / auto_start here.
        // Those are routing/user-policy preferences, not DSP parameters.
        val changes = linkedMapOf<String, PrefValue>()

        plan.commands.groupBy { it.param }.forEach { (param, commands) ->
            if (commands.size == 1 && commands[0].value.index == -1) {
                when (val value = commands[0].value) {
                    is WireValue.Bool -> changes[param.toString()] = PrefValue.Bool(value.value)
                    is WireValue.IntValue -> changes[param.toString()] = PrefValue.IntV(value.value)
                    is WireValue.FloatValue -> changes[param.toString()] = PrefValue.FloatV(value.value)
                    is WireValue.Floats -> changes[param.toString()] = PrefValue.StringV(
                        value.values.joinToString(";") {
                            String.format(Locale.US, "%.1f", it)
                        },
                    )
                    else -> Unit
                }
            } else if (commands.any { it.value.index >= 0 }) {
                val ordered = commands.sortedBy { it.value.index }
                val text = when (ordered.first().value) {
                    is WireValue.Bool -> ordered.joinToString(";") {
                        if ((it.value as WireValue.Bool).value) "1" else "0"
                    }
                    is WireValue.IntValue -> ordered.joinToString(";") {
                        (it.value as WireValue.IntValue).value.toString()
                    }
                    is WireValue.FloatValue -> ordered.joinToString(";") {
                        String.format(Locale.US, "%.4f", (it.value as WireValue.FloatValue).value)
                    }
                    else -> null
                }
                if (text != null) changes[param.toString()] = PrefValue.StringV(text)
            }
        }

        val eq = plan.profile.getJSONObject("equalizer")
        changes["eq_bands_" + eq.getInt("bandCount")] = PrefValue.StringV(
            plan.eqLevels.joinToString(";") { String.format(Locale.US, "%.1f", it) },
        )
        changes["equalizer_presetId"] = PrefValue.IntV(-1)
        changes["convolver_kernelFile"] = PrefValue.StringV(
            plan.profile.getJSONObject("convolver").optString("kernelFile", ""),
        )
        changes["ddc_device"] = PrefValue.StringV(
            plan.profile.getJSONObject("ddc").optString("device", ""),
        )
        changes["dynamicSystem_presetId"] = PrefValue.IntV(-1)

        PreferencesProto.patch(local, changes)

        val write = RootShell.exec(
            "cat " + RootShell.quote(local.absolutePath) + " > " + RootShell.quote(path) +
                "; restorecon " + RootShell.quote(path) + " 2>/dev/null || true",
        )
        check(write.ok) { "ViPER DataStore 写入失败: " + write.output }
    }

    private suspend fun patchRoom(path: String, plan: Plan) {
        val local = File(context.cacheDir, "viper4android.db")
        val uid = Process.myUid()
        val copied = RootShell.exec(
            "cp " + RootShell.quote(path) + " " + RootShell.quote(local.absolutePath) +
                " && chown " + uid + ":" + uid + " " + RootShell.quote(local.absolutePath),
        )
        if (!copied.ok) return

        val modified = runCatching {
            SQLiteDatabase.openDatabase(local.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val tableExists = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='device_settings'",
                    null,
                ).use { it.moveToFirst() }
                if (!tableExists) return@use false

                val values = ContentValues().apply {
                    put("device_name", "Speaker")
                    put("is_headphone", 0)
                    put("settings_json", plan.profile.toString())
                    put("last_connected", System.currentTimeMillis())
                }
                if (db.update("device_settings", values, "device_id=?", arrayOf("speaker")) == 0) {
                    values.put("device_id", "speaker")
                    db.insertOrThrow("device_settings", null, values)
                }
                true
            }
        }.getOrDefault(false)
        if (!modified) return

        RootShell.exec(
            "cat " + RootShell.quote(local.absolutePath) + " > " + RootShell.quote(path) +
                "; rm -f " + RootShell.quote(path + "-wal") + " " + RootShell.quote(path + "-shm") +
                "; restorecon " + RootShell.quote(path) + " 2>/dev/null || true",
        )
    }

    private suspend fun installBulkFiles(paths: Paths, plan: Plan) {
        plan.kernel?.let { kernel ->
            val local = File(context.cacheDir, "V4ATune_Speaker.wav")
            WavFiles.writeMonoFloat(local, kernel, ViperControl.status()?.sampleRate ?: 48000)
            val dest = paths.external + "/Kernel/V4ATune_Speaker.wav"
            RootShell.exec(
                "mkdir -p " + RootShell.quote(paths.external + "/Kernel") +
                    " && cp " + RootShell.quote(local.absolutePath) + " " + RootShell.quote(dest) +
                    " && chmod 0644 " + RootShell.quote(dest),
            )
        }

        if (plan.ddc44 != null && plan.ddc48 != null) {
            val local = File(context.cacheDir, "V4ATune_Speaker.vdc")
            VdcFiles.write(local, plan.ddc44, plan.ddc48)
            val dest = paths.external + "/DDC/V4ATune_Speaker.vdc"
            RootShell.exec(
                "mkdir -p " + RootShell.quote(paths.external + "/DDC") +
                    " && cp " + RootShell.quote(local.absolutePath) + " " + RootShell.quote(dest) +
                    " && chmod 0644 " + RootShell.quote(dest),
            )
        }
    }

    private suspend fun stopManager() {
        RootShell.exec("am force-stop " + RootShell.quote(managerPkg))
        delay(250)
    }

    private suspend fun launchManager() {
        val service = RootShell.exec(
            "am start-foreground-service -n " + managerPkg +
                "/.service.ViperService -a " +
                "com.llsl.viper4android.service.START >/dev/null 2>&1",
        )
        if (!service.ok) {
            RootShell.exec(
                "am start -n " + managerPkg + "/.ui.MainActivity >/dev/null 2>&1 || " +
                    "monkey -p " + managerPkg + " 1 >/dev/null 2>&1",
            )
        }
        delay(900)
    }
}

private sealed interface PrefValue {
    data class Bool(val value: Boolean) : PrefValue
    data class IntV(val value: Int) : PrefValue
    data class FloatV(val value: Float) : PrefValue
    data class StringV(val value: String) : PrefValue
}

private object PreferencesProto {
    private data class Field(val number: Int, val wire: Int, val raw: ByteArray)

    fun patch(file: File, changes: Map<String, PrefValue>) {
        val original = Files.readAllBytes(file.toPath())
        val seen = changes.keys.associateWith { false }.toMutableMap()
        val output = ByteArrayOutputStream()

        parse(original).forEach { field ->
            if (field.number == 1 && field.wire == 2) {
                val entry = lengthPayload(field.raw)
                val key = mapKey(entry)
                val value = changes[key]
                if (value != null) {
                    output.write(prefEntry(key, value))
                    seen[key] = true
                } else {
                    output.write(field.raw)
                }
            } else {
                output.write(field.raw)
            }
        }

        changes.forEach { (key, value) ->
            if (seen[key] != true) output.write(prefEntry(key, value))
        }

        val patched = output.toByteArray()
        parse(patched)
        Files.write(file.toPath(), patched)
    }

    private fun mapKey(entry: ByteArray): String =
        parse(entry)
            .firstOrNull { it.number == 1 && it.wire == 2 }
            ?.let { String(lengthPayload(it.raw), StandardCharsets.UTF_8) }
            .orEmpty()

    private fun prefEntry(key: String, value: PrefValue): ByteArray {
        val keyField = lenField(1, key.toByteArray(StandardCharsets.UTF_8))
        val valueMessage = when (value) {
            is PrefValue.Bool -> varintField(1, if (value.value) 1 else 0)
            is PrefValue.FloatV -> fixed32Field(2, value.value.toRawBits())
            is PrefValue.IntV -> varintField(3, value.value.toLong())
            is PrefValue.StringV -> lenField(5, value.value.toByteArray(StandardCharsets.UTF_8))
        }
        return lenField(1, concat(keyField, lenField(2, valueMessage)))
    }

    private fun parse(data: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var position = 0
        while (position < data.size) {
            val start = position
            val (tag, afterTag) = readVarint(data, position)
            position = afterTag
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()

            position = when (wire) {
                0 -> readVarint(data, position).second
                1 -> position + 8
                2 -> {
                    val (length, afterLength) = readVarint(data, position)
                    afterLength + length.toInt()
                }
                5 -> position + 4
                else -> error("Unsupported protobuf wire type " + wire)
            }
            require(position <= data.size) { "Truncated preferences protobuf" }
            out += Field(number, wire, data.copyOfRange(start, position))
        }
        return out
    }

    private fun lengthPayload(raw: ByteArray): ByteArray {
        val (_, afterTag) = readVarint(raw, 0)
        val (length, afterLength) = readVarint(raw, afterTag)
        return raw.copyOfRange(afterLength, afterLength + length.toInt())
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var position = start
        var value = 0L
        var shift = 0
        while (true) {
            require(position < data.size) { "Truncated varint" }
            val b = data[position++].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return value to position
            shift += 7
            require(shift <= 63) { "Bad varint" }
        }
    }

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        do {
            var b = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) b = b or 0x80
            out.write(b)
        } while (remaining != 0L)
        return out.toByteArray()
    }

    private fun lenField(number: Int, payload: ByteArray): ByteArray =
        concat(
            varint((number.toLong() shl 3) or 2),
            varint(payload.size.toLong()),
            payload,
        )

    private fun varintField(number: Int, value: Long): ByteArray =
        concat(varint(number.toLong() shl 3), varint(value))

    private fun fixed32Field(number: Int, bits: Int): ByteArray =
        concat(
            varint((number.toLong() shl 3) or 5),
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array(),
        )

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArray(arrays.sumOf { it.size })
        var position = 0
        arrays.forEach {
            it.copyInto(out, position)
            position += it.size
        }
        return out
    }
}
