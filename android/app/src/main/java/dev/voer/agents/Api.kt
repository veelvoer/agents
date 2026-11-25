package dev.voer.agents

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class ApiError(message: String) : Exception(message)

data class Usage(val input: Long, val output: Long, val cache: Long, val cost: Double) { val tokens get() = input + output }
data class Spec(val name: String, val model: String, val prompt: String, val memory: String, val mcp: String, val subagents: Boolean,
                val autonomy: String = "safe", val osTools: Boolean = true, val dailyCap: Double? = null, val workdir: String = "",
                val autonomous: Boolean = false, val wakeMin: Int = 15, val maxWakes: Int = 24, val browser: Boolean = false)
data class Agent(val id: Long, val spec: Spec, val running: Boolean, val usage: Usage, val pinned: Boolean, val lastActive: Long, val messages: Int, val daily: Map<String, Double>,
                 val journal: String = "", val nextWake: Long = 0, val wakeReason: String = "", val wakes: Long = 0, val lastWake: Long = 0)
data class Msg(val role: String, val text: String, val at: Long)
data class Page(val start: Int, val total: Int, val messages: List<Msg>)
data class Parsed(val schedule: JSONObject, val human: String, val next: List<String>, val tz: String)
data class AgentList(val rev: Long, val agents: List<Agent>, val actLast: Long = 0, val paused: Boolean = false)
data class Job(val id: Long, val agentId: Long, val agent: String, val name: String, val prompt: String, val human: String, val enabled: Boolean, val fresh: Boolean, val notify: String,
               val next: String?, val lastRun: Long, val lastStatus: String?, val lastText: String, val runs: Int, val cost: Double, val hook: String?, val running: Boolean, val schedule: JSONObject, val notes: String = "")
data class Act(val id: Long, val at: Long, val kind: String, val agentId: Long, val jobId: Long, val title: String, val body: String)
data class SrvSettings(val paused: Boolean, val maxParallel: Int, val tz: String, val memory: String, val tgToken: Boolean, val tgPaired: Boolean, val tgCode: String?, val ntfyServer: String, val ntfyTopic: String, val webhook: Boolean)

class Api(val url: String, val key: String) {
    private fun call(method: String, path: String, body: String? = null, timeoutMs: Int = 20_000): String {
        val c = try { URL(url + path).openConnection() as HttpURLConnection } catch (e: Exception) { throw ApiError("That address doesn't look right.") }
        try {
            c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) { c.doOutput = true; c.setRequestProperty("content-type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code == 401) throw ApiError("The key was refused. Paste the key again from the server.")
            if (code !in 200..299) throw ApiError(text.ifBlank { "The server answered $code." })
            return text
        } catch (e: ApiError) { throw e
        } catch (e: java.io.InterruptedIOException) { throw e
        } catch (e: Exception) { throw ApiError("Can't reach $url. Check the address and your internet.") }
        finally { c.disconnect() }
    }

    fun models(): List<Pair<String, String>> = JSONArray(call("GET", "/models")).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }

    /** With [since] the server holds the request until something changed (long poll), so an idle phone costs almost nothing. */
    fun agents(since: Long? = null): AgentList {
        val d = JSONObject(call("GET", "/agents" + if (since != null) "?since=$since" else "", timeoutMs = 40_000))
        val a = d.getJSONArray("agents")
        return AgentList(d.getLong("rev"), (0 until a.length()).map {
            val o = a.getJSONObject(it); val s = o.getJSONObject("spec"); val u = o.getJSONObject("usage")
            val dl = o.optJSONArray("daily") ?: JSONArray()
            Agent(o.getLong("id"), Spec(s.getString("name"), s.getString("model"), s.optString("prompt"), s.optString("memory"), if (s.isNull("mcp_config")) "" else s.optString("mcp_config"), s.optBoolean("subagents"),
                s.optString("autonomy", "safe"), s.optBoolean("os_tools", true), if (s.isNull("daily_cap") || !s.has("daily_cap")) null else s.optDouble("daily_cap"), if (s.isNull("workdir")) "" else s.optString("workdir"),
                s.optBoolean("autonomous"), if (s.isNull("wake_min")) 15 else s.optInt("wake_min", 15), if (s.isNull("max_wakes")) 24 else s.optInt("max_wakes", 24), s.optBoolean("browser")),
                o.getString("status") == "running", Usage(u.optLong("input_tokens"), u.optLong("output_tokens"), u.optLong("cache_read_tokens"), u.optDouble("cost_usd")),
                o.optBoolean("pinned"), o.optLong("last_active"), o.optInt("messages"),
                (0 until dl.length()).associate { i -> dl.getJSONArray(i).let { p -> p.getString(0) to p.getJSONObject(1).optDouble("cost") } },
                o.optString("journal"), if (o.isNull("next_wake")) 0 else o.optLong("next_wake"), o.optString("wake_reason"), o.optLong("wakes"), o.optLong("last_wake"))
        }, d.optLong("act_last"), d.optBoolean("paused"))
    }

    fun jobs(): List<Job> = JSONArray(call("GET", "/jobs")).let { a -> (0 until a.length()).map {
        val j = a.getJSONObject(it)
        Job(j.getLong("id"), j.getLong("agent_id"), j.optString("agent"), j.getString("name"), j.optString("prompt"), j.optString("human"), j.optBoolean("enabled"), j.optBoolean("fresh", true), j.optString("notify", "always"),
            if (j.isNull("next")) null else j.optString("next"), if (j.isNull("last_run")) 0 else j.optLong("last_run"), if (j.isNull("last_status")) null else j.optString("last_status"), j.optString("last_text"),
            j.optInt("runs"), j.optDouble("cost"), if (j.isNull("hook")) null else j.optString("hook"), j.optBoolean("running"), j.getJSONObject("schedule"), j.optString("notes"))
    } }
    fun parseSchedule(text: String): Parsed { val d = JSONObject(call("POST", "/schedule/parse", JSONObject().put("text", text).toString(), 120_000)); val n = d.getJSONArray("next"); return Parsed(d.getJSONObject("schedule"), d.optString("human"), (0 until n.length()).map { n.getString(it) }, d.optString("tz")) }
    fun saveJob(id: Long?, body: JSONObject) { if (id == null) call("POST", "/jobs", body.toString()) else call("PUT", "/jobs/$id", body.toString()) }
    fun deleteJob(id: Long) { call("DELETE", "/jobs/$id") }
    fun jobEnabled(id: Long, on: Boolean) { call("POST", "/jobs/$id/enabled", JSONObject().put("enabled", on).toString()) }
    fun runJob(id: Long) { call("POST", "/jobs/$id/run", "{}") }
    fun activity(after: Long? = null, limit: Int = 80, job: Long? = null): Pair<Long, List<Act>> {
        val d = JSONObject(call("GET", "/activity?limit=$limit" + (if (after != null) "&after=$after" else "") + (if (job != null) "&job=$job" else "")))
        val a = d.getJSONArray("items")
        return d.getLong("last") to (0 until a.length()).map { i -> a.getJSONObject(i).let { e -> Act(e.getLong("id"), e.optLong("at"), e.optString("kind"), if (e.isNull("agent_id")) 0 else e.optLong("agent_id"), if (e.isNull("job_id")) 0 else e.optLong("job_id"), e.optString("title"), e.optString("body")) } }
    }
    fun settings(): SrvSettings = JSONObject(call("GET", "/settings")).let { parseSettings(it) }
    fun putSettings(body: JSONObject): SrvSettings = parseSettings(JSONObject(call("PUT", "/settings", body.toString())))
    private fun parseSettings(d: JSONObject): SrvSettings { val t = d.getJSONObject("telegram"); val n = d.getJSONObject("ntfy")
        return SrvSettings(d.optBoolean("paused"), d.optInt("max_parallel", 2), d.optString("tz", "UTC"), d.optString("global_memory"), t.optBoolean("token_set"), t.optBoolean("paired"), if (t.isNull("pair_code")) null else t.optString("pair_code"), n.optString("server"), n.optString("topic"), d.getJSONObject("webhook").optBoolean("set")) }
    fun testNotify(): String { val r = JSONObject(call("POST", "/settings/test", "{}")); return if (r.optBoolean("ok")) "" else r.optJSONArray("errors")?.let { e -> (0 until e.length()).joinToString("; ") { e.getString(it) } } ?: "failed" }
    fun telegramPair() { call("POST", "/settings/telegram/pair", "{}") }
    fun telegramUnlink() { call("DELETE", "/settings/telegram") }

    fun messages(id: Long, before: Int? = null, limit: Int = 100): Page {
        val d = JSONObject(call("GET", "/agents/$id/messages?limit=$limit" + if (before != null) "&before=$before" else ""))
        val a = d.getJSONArray("messages")
        return Page(d.getInt("start"), d.getInt("total"), (0 until a.length()).map { a.getJSONObject(it).let { m -> Msg(m.getString("role"), m.getString("text"), m.optLong("at")) } })
    }

    fun saveJournal(id: Long, text: String) { call("POST", "/agents/$id/journal", JSONObject().put("text", text).toString()) }
    fun wakeNow(id: Long) { call("POST", "/agents/$id/wake-now", "{}") }
    fun clear(id: Long) { call("DELETE", "/agents/$id/messages") }
    fun delete(id: Long) { call("DELETE", "/agents/$id") }
    fun run(id: Long, text: String) { call("POST", "/agents/$id/run", JSONObject().put("message", text).toString()) }
    fun stop(id: Long) { call("POST", "/agents/$id/stop", "{}") }
    fun pin(id: Long, pinned: Boolean) { call("POST", "/agents/$id/pin", JSONObject().put("pinned", pinned).toString()) }
    fun health(): String = JSONObject(call("GET", "/health")).optString("version")
    fun updateServer(): String = JSONObject(call("POST", "/admin/update", "{}", 150_000)).optString("to")

    private fun specJson(s: Spec) = JSONObject().put("name", s.name).put("model", s.model).put("prompt", s.prompt).put("memory", s.memory)
        .put("mcp_config", if (s.mcp.isBlank()) JSONObject.NULL else s.mcp.trim()).put("subagents", s.subagents)
        .put("browser", s.browser).put("autonomous", s.autonomous).put("wake_min", s.wakeMin).put("max_wakes", s.maxWakes).put("autonomy", s.autonomy).put("os_tools", s.osTools).put("daily_cap", s.dailyCap ?: JSONObject.NULL).put("workdir", if (s.workdir.isBlank()) JSONObject.NULL else s.workdir.trim()).toString()
    fun save(id: Long?, s: Spec): Long = if (id == null) call("POST", "/agents", specJson(s)).trim().toLong() else { call("PUT", "/agents/$id", specJson(s)); id }
}

/** Accepts "https://host#key", "https://host key" or a plain address. */
fun parsePair(text: String): Pair<String, String> {
    val t = text.trim(); var url = t; var key = ""
    Regex("^(\\S+?)(?:#|\\s+)(\\S+)$").find(t)?.let { url = it.groupValues[1]; key = it.groupValues[2] }
    if (url.isNotEmpty() && !url.startsWith("http")) url = (if (Regex("^[\\d.]+(:\\d+)?$|^localhost").containsMatchIn(url)) "http://" else "https://") + url
    return url.trimEnd('/') to key
}

fun newerVersion(a: String, b: String): Boolean {
    val x = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }; val y = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until 3) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
    return false
}
