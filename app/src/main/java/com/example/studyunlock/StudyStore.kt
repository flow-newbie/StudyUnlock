package com.example.studyunlock

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class StudyStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "study.json"))
    private var data = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
        JSONObject(String(file.readFully(), Charsets.UTF_8))
    } else JSONObject().put("version", 1).put("balance", 0).put("days", JSONObject())
        .put("ledger", JSONArray()).put("events", JSONArray())
    val changes = MutableStateFlow(0)
    private val zone = ZoneId.of("Asia/Shanghai")

    fun date(time: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    fun endOfDay(date: LocalDate): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun today() = data.getJSONObject("days").optJSONObject(date().toString())
    fun settings() = data.optJSONObject("settings")
    fun balance() = data.getInt("balance")
    fun days() = data.getJSONObject("days")
    fun ledger() = data.getJSONArray("ledger")
    fun events() = data.getJSONArray("events")
    fun export() = data.toString(2)

    private fun commit() {
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            data = if (file.baseFile.exists()) JSONObject(String(file.readFully(), Charsets.UTF_8))
            else JSONObject().put("version", 1).put("balance", 0).put("days", JSONObject())
                .put("ledger", JSONArray()).put("events", JSONArray())
            throw error
        }
        changes.value++
    }

    fun saveSettings(minutes: Int, reward: Int, penalty: Int, initial: Int, study: Set<String>, entertainment: Set<String>) {
        require(minutes in 1..1440 && reward in 0..1000 && penalty in 0..1000 && initial in 0..1000)
        require(study.isNotEmpty() && entertainment.isNotEmpty() && study.intersect(entertainment).isEmpty())
        if (settings() == null) {
            data.put("starts", date().plusDays(1).toString())
            points(initial, "初始积分", date().toString())
        }
        val effective = if (today() == null && settings() == null) date() else date().plusDays(1)
        val versions = data.optJSONArray("templates") ?: JSONArray().also { data.put("templates", it) }
        val template = JSONObject().put("minutes", minutes).put("reward", reward).put("penalty", penalty)
            .put("study", JSONArray(study.toList().sorted())).put("entertainment", JSONArray(entertainment.toList().sorted()))
            .put("effective", effective.toString())
        if (versions.length() > 0 && versions.getJSONObject(versions.length() - 1).getString("effective") == effective.toString()) {
            versions.put(versions.length() - 1, template)
        } else versions.put(template)
        data.put("settings", template)
        commit()
    }

    fun openDay(key: LocalDate = date()): JSONObject? {
        val days = days()
        days.optJSONObject(key.toString())?.let { return it }
        val versions = data.optJSONArray("templates") ?: return null
        val template = (versions.length() - 1 downTo 0).map { versions.getJSONObject(it) }
            .firstOrNull { it.getString("effective") <= key.toString() } ?: return null
        if (days.keys().asSequence().any { it > key.toString() }) return null
        val day = JSONObject(template.toString()).put("seconds", 0).put("completion", 0)
            .put("status", "active").put("passes", 0).put("pauseUntil", 0)
            .put("practice", key.toString() < data.getString("starts"))
        days.put(key.toString(), day)
        commit()
        return day
    }

    fun settle(now: LocalDate = date()) {
        var changed = false
        for (key in days().keys().asSequence().sorted().toList()) {
            val day = days().getJSONObject(key)
            if (key >= now.toString() || day.getString("status") != "active") continue
            when {
                day.optBoolean("practice") -> day.put("status", "practice")
                day.optBoolean("incomplete") -> day.put("status", "incomplete")
                else -> {
                    val penalty = day.getInt("penalty")
                    val deducted = minOf(penalty, balance())
                    points(-deducted, if (deducted < penalty) "未足额扣除（目标扣 $penalty）" else "未完成", key)
                    day.put("status", "failed")
                }
            }
            changed = true
        }
        if (changed) commit()
    }

    fun markMonitoringGap(lastHeartbeat: Long, now: Long = System.currentTimeMillis()) {
        if (lastHeartbeat <= 0 || now - lastHeartbeat in 0..90_000) return
        val start = date(minOf(lastHeartbeat, now))
        val end = date(maxOf(lastHeartbeat, now))
        for (key in days().keys().asSequence().map(LocalDate::parse).toList()) {
            if (key in start..end) incomplete("监测中断", key)
        }
    }

    fun closePass(day: JSONObject) {
        if (remainingPass(day) == 0L && day.optLong("pauseUntil") != 0L) {
            day.put("pauseUntil", 0).put("pauseBoot", 0)
            commit()
        }
    }

    fun incomplete(reason: String, key: LocalDate = date()) {
        val day = days().optJSONObject(key.toString()) ?: return
        if (day.getString("status") != "active" || day.optBoolean("incomplete")) return
        day.put("incomplete", true)
        appendEvent(reason)
        commit()
    }

    fun changeCompletion(value: Int, ready: Boolean) {
        val day = today() ?: return
        if (day.getString("status") != "active") return
        day.put("completion", value.coerceIn(0, 100)).put("completionAt", System.currentTimeMillis())
        unlock(day, date(), ready)
        commit()
    }

    fun record(key: LocalDate, app: String, seconds: Int, study: Boolean, ready: Boolean, endedAt: Long) {
        val day = days().optJSONObject(key.toString()) ?: return
        val field = if (study) "studyUsage" else "usage"
        val usage = day.optJSONObject(field) ?: JSONObject().also { day.put(field, it) }
        usage.put(app, usage.optInt(app) + seconds)
        val sessions = day.optJSONArray("sessions") ?: JSONArray().also { day.put("sessions", it) }
        val previous = sessions.optJSONObject(sessions.length() - 1)
        val start = endedAt - seconds * 1000L
        if (previous?.optString("app") == app && previous.optBoolean("study") == study &&
            start - previous.optLong("end") in -1_000..1_000) {
            previous.put("end", endedAt).put("seconds", previous.getInt("seconds") + seconds)
        } else {
            sessions.put(JSONObject().put("app", app).put("study", study).put("start", start)
                .put("end", endedAt).put("seconds", seconds))
        }
        if (study) {
            day.put("seconds", day.getInt("seconds") + seconds)
            unlock(day, key, ready)
        }
        commit()
    }

    private fun unlock(day: JSONObject, key: LocalDate, ready: Boolean) {
        if (!ready || day.getString("status") != "active" || day.getInt("seconds") < day.getInt("minutes") * 60 ||
            day.getInt("completion") != 100) return
        day.put("status", "unlocked").put("unlockedAt", System.currentTimeMillis())
        if (!day.optBoolean("practice")) {
            val reward = day.getInt("reward")
            val credited = minOf(reward, 1000 - balance())
            points(credited, if (credited < reward) "学习达标（积分封顶）" else "学习达标", key.toString())
        }
    }

    fun recheck() {
        val day = today() ?: return
        if (day.getString("status") == "active" && day.getInt("completion") == 100 &&
            day.getInt("seconds") >= day.getInt("minutes") * 60) {
            unlock(day, date(), true)
            commit()
        }
    }

    fun pass(day: JSONObject) {
        if (day.getInt("passes") >= 2 || day.getString("status") != "active") return
        day.put("passes", day.getInt("passes") + 1)
            .put("pauseUntil", SystemClock.elapsedRealtime() + 300_000)
            .put("pauseBoot", System.currentTimeMillis() - SystemClock.elapsedRealtime())
        appendEvent("紧急放行")
        commit()
    }

    fun remainingPass(day: JSONObject): Long {
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (kotlin.math.abs(boot - day.optLong("pauseBoot")) > 5_000) return 0
        return (day.optLong("pauseUntil") - SystemClock.elapsedRealtime()).coerceIn(0, 300_000)
    }

    fun adjust(delta: Int, note: String) {
        require(note.isNotBlank() && delta != 0 && balance() + delta in 0..1000)
        points(delta, note.trim(), date().toString())
        commit()
    }

    fun event(note: String) {
        appendEvent(note)
        commit()
    }

    private fun appendEvent(note: String) {
        val events = events()
        if (events.length() >= 500) events.remove(0)
        events.put(JSONObject().put("time", System.currentTimeMillis()).put("note", note))
    }

    private fun points(delta: Int, note: String, key: String) {
        val amount = balance() + delta
        data.put("balance", amount)
        ledger().put(JSONObject().put("date", key).put("time", System.currentTimeMillis())
            .put("note", note).put("delta", delta).put("balance", amount))
    }

    fun import(source: String) {
        val incoming = JSONObject(source)
        require(incoming.getInt("version") == 1 && incoming.getInt("balance") in 0..1000)
        fun checkTemplate(item: JSONObject) {
            require(item.getInt("minutes") in 1..1440 && item.getInt("reward") in 0..1000 &&
                item.getInt("penalty") in 0..1000)
            LocalDate.parse(item.getString("effective"))
            val study = item.packages("study")
            val entertainment = item.packages("entertainment")
            require(study.isNotEmpty() && entertainment.isNotEmpty() && study.intersect(entertainment).isEmpty())
        }
        val incomingDays = incoming.getJSONObject("days")
        val incomingLedger = incoming.getJSONArray("ledger")
        val incomingEvents = incoming.getJSONArray("events")
        val incomingTemplates = incoming.optJSONArray("templates")
        for (index in 0 until (incomingTemplates?.length() ?: 0)) checkTemplate(incomingTemplates!!.getJSONObject(index))
        incoming.optJSONObject("settings")?.let {
            checkTemplate(it)
            require(incomingTemplates != null && incomingTemplates.length() > 0)
            LocalDate.parse(incoming.getString("starts"))
        }
        for (key in incomingDays.keys()) {
            LocalDate.parse(key)
            val day = incomingDays.getJSONObject(key)
            checkTemplate(day)
            require(day.getInt("seconds") >= 0 && day.getInt("completion") in 0..100 && day.getInt("passes") in 0..2)
            require(day.getString("status") in setOf("active", "unlocked", "failed", "incomplete", "practice"))
            for (field in listOf("usage", "studyUsage")) {
                day.optJSONObject(field)?.let { usage ->
                    require(usage.keys().asSequence().all { usage.getInt(it) >= 0 })
                }
            }
            day.put("pauseUntil", 0)
        }
        var sum = 0
        for (index in 0 until incomingLedger.length()) {
            val row = incomingLedger.getJSONObject(index)
            LocalDate.parse(row.getString("date"))
            require(row.getInt("delta") in -1000..1000)
            sum += row.getInt("delta")
            require(sum in 0..1000 && row.getInt("balance") == sum)
            row.getString("note")
        }
        require(sum == incoming.getInt("balance"))
        for (index in 0 until incomingEvents.length()) incomingEvents.getJSONObject(index).getString("note")
        data = incoming
        appendEvent("导入备份")
        commit()
    }

    companion object {
        private var instance: StudyStore? = null
        fun get(context: Context): StudyStore = instance ?: StudyStore(context.applicationContext).also { instance = it }
    }
}

internal fun JSONObject.packages(key: String): Set<String> {
    val array = getJSONArray(key)
    return (0 until array.length()).map { array.getString(it) }.toSet()
}
