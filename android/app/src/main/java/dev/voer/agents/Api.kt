package dev.voer.agents

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class ApiError(message: String) : Exception(message)

data class Usage(val input: Long, val output: Long, val cache: Long, val cost: Double) { val tokens get() = input + output }
data class Spec(val name: String, val model: String, val prompt: String, val memory: String, val mcp: String, val subagents: Boolean)
data class Agent(val id: Long, val spec: Spec, val running: Boolean, val usage: Usage, val pinned: Boolean, val lastActive: Long, val messages: Int, val daily: Map<String, Double>)
data class Msg(val role: String, val text: String, val at: Long)
data class Page(val start: Int, val total: Int, val messages: List<Msg>)
data class AgentList(val rev: Long, val agents: List<Agent>)

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
            Agent(o.getLong("id"), Spec(s.getString("name"), s.getString("model"), s.optString("prompt"), s.optString("memory"), if (s.isNull("mcp_config")) "" else s.optString("mcp_config"), s.optBoolean("subagents")),
                o.getString("status") == "running", Usage(u.optLong("input_tokens"), u.optLong("output_tokens"), u.optLong("cache_read_tokens"), u.optDouble("cost_usd")),
                o.optBoolean("pinned"), o.optLong("last_active"), o.optInt("messages"),
                (0 until dl.length()).associate { i -> dl.getJSONArray(i).let { p -> p.getString(0) to p.getJSONObject(1).optDouble("cost") } })
        })
    }

    fun messages(id: Long, before: Int? = null, limit: Int = 100): Page {
        val d = JSONObject(call("GET", "/agents/$id/messages?limit=$limit" + if (before != null) "&before=$before" else ""))
        val a = d.getJSONArray("messages")
        return Page(d.getInt("start"), d.getInt("total"), (0 until a.length()).map { a.getJSONObject(it).let { m -> Msg(m.getString("role"), m.getString("text"), m.optLong("at")) } })
    }

    fun clear(id: Long) { call("DELETE", "/agents/$id/messages") }
    fun delete(id: Long) { call("DELETE", "/agents/$id") }
    fun run(id: Long, text: String) { call("POST", "/agents/$id/run", JSONObject().put("message", text).toString()) }
    fun stop(id: Long) { call("POST", "/agents/$id/stop", "{}") }
    fun pin(id: Long, pinned: Boolean) { call("POST", "/agents/$id/pin", JSONObject().put("pinned", pinned).toString()) }
    fun health(): String = JSONObject(call("GET", "/health")).optString("version")
    fun updateServer(): String = JSONObject(call("POST", "/admin/update", "{}", 150_000)).optString("to")

    private fun specJson(s: Spec) = JSONObject().put("name", s.name).put("model", s.model).put("prompt", s.prompt).put("memory", s.memory)
        .put("mcp_config", if (s.mcp.isBlank()) JSONObject.NULL else s.mcp.trim()).put("subagents", s.subagents).toString()
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
