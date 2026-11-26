package dev.voer.agents

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

// ---- schedule builder ----
data class SchedState(var type: String = "daily", var h: Int = 8, var m: Int = 0, var dow: Int = 1, var every: String = "30", var unit: String = "minutes", var inN: String = "30", var cron: String = "")

fun fromSchedule(j: JSONObject?): SchedState {
    val st = SchedState(); if (j == null) return st
    when (j.optString("kind")) {
        "every" -> { val m = j.optLong("minutes", 30); st.type = "every"; when { m % 1440 == 0L -> { st.every = (m / 1440).toString(); st.unit = "days" }; m % 60 == 0L -> { st.every = (m / 60).toString(); st.unit = "hours" }; else -> st.every = m.toString() } }
        "once" -> st.type = "once"
        "hook" -> st.type = "hook"
        "manual" -> st.type = "manual"
        else -> {
            val f = j.optString("expr").trim().split(Regex("\\s+"))
            if (f.size == 5 && f[2] == "*" && f[3] == "*" && f[0].toIntOrNull() != null && f[1].toIntOrNull() != null) {
                st.m = f[0].toInt(); st.h = f[1].toInt()
                when { f[4] == "*" -> st.type = "daily"; f[4] == "1-5" -> st.type = "weekdays"; f[4] == "0,6" || f[4] == "6,0" -> st.type = "weekends"; f[4].toIntOrNull() != null -> { st.type = "weekly"; st.dow = f[4].toInt() % 7 }; else -> { st.type = "cron"; st.cron = j.optString("expr") } }
            } else { st.type = "cron"; st.cron = j.optString("expr") }
        }
    }
    return st
}

fun toSchedule(st: SchedState, tz: String): JSONObject {
    fun cron(dow: String) = JSONObject().put("kind", "cron").put("expr", "${st.m} ${st.h} * * $dow").put("tz", tz)
    return when (st.type) {
        "daily" -> cron("*"); "weekdays" -> cron("1-5"); "weekends" -> cron("0,6"); "weekly" -> cron(st.dow.toString())
        "every" -> { val n = (st.every.toLongOrNull() ?: 0L).coerceAtLeast(1); JSONObject().put("kind", "every").put("minutes", n * when (st.unit) { "days" -> 1440; "hours" -> 60; else -> 1 }) }
        "once" -> { val n = (st.inN.toLongOrNull() ?: 0L).coerceAtLeast(1); JSONObject().put("kind", "once").put("at", System.currentTimeMillis() / 1000 + n * 60 * when (st.unit) { "days" -> 1440; "hours" -> 60; else -> 1 }) }
        "hook" -> JSONObject().put("kind", "hook"); "manual" -> JSONObject().put("kind", "manual")
        else -> { if (st.cron.isBlank()) throw ApiError("Type a cron schedule, like 0 8 * * 1-5."); JSONObject().put("kind", "cron").put("expr", st.cron.trim()).put("tz", tz) }
    }
}

// ---- repeat: an agent that runs by itself ----
fun repeatText(jobs: List<Job>): String? { val j = jobs.firstOrNull { it.enabled } ?: jobs.firstOrNull() ?: return null; return if (j.enabled) j.human + (j.next?.let { " · next $it" } ?: "") else "${j.human} (off)" }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepeatSheet(agent: Agent, jobs: List<Job>, tz: String, api: Api, onClose: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf(jobs.firstOrNull { it.enabled }?.id ?: jobs.firstOrNull()?.id ?: 0L) }   // 0 = a new repeat
    val job = jobs.firstOrNull { it.id == pick }
    var prompt by remember(pick) { mutableStateOf(job?.prompt ?: "") }
    var notes by remember(pick) { mutableStateOf(job?.notes ?: "") }
    var whenText by remember(pick) { mutableStateOf("") }
    var parsed by remember(pick) { mutableStateOf<Parsed?>(null) }
    var parsedFor by remember(pick) { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    val st = remember(pick) { mutableStateOf(fromSchedule(job?.schedule)) }
    var notify by remember(pick) { mutableStateOf(job?.notify ?: "always") }
    var fresh by remember(pick) { mutableStateOf(job?.fresh ?: true) }
    var enabled by remember(pick) { mutableStateOf(job?.enabled ?: true) }
    var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    fun upd(f: SchedState.() -> Unit) { val c = st.value.copy(); c.f(); st.value = c }
    val types = listOf("daily" to "Every day", "weekdays" to "Weekdays", "weekly" to "Weekly", "every" to "Every…", "once" to "Once", "hook" to "Web link", "manual" to "Manual", "cron" to "Cron")
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Repeat ${agent.spec.name}", style = MaterialTheme.typography.headlineMedium)
            Muted("The agent does this by itself on the server, even when the app is closed, and tells you the result.")
            if (jobs.size > 1 || (jobs.isNotEmpty() && pick == 0L)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                jobs.forEachIndexed { n, j -> FilterChip(pick == j.id, { pick = j.id }, { Text(if (j.name == agent.spec.name) "Repeat ${n + 1}" else j.name) }, Modifier.heightIn(min = 48.dp), shape = CircleShape, colors = chipColors) }
                FilterChip(pick == 0L, { pick = 0L }, { Text("+ Another") }, Modifier.heightIn(min = 48.dp), shape = CircleShape, colors = chipColors)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (enabled) "Repeating" else "Not repeating", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Switch(enabled, { enabled = it }) }
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Message to send each time (optional)") }, placeholder = { Text(if (agent.spec.prompt.isNotBlank()) "Leave empty: the agent just follows its own instructions." else "For example: Check the news about my topics and give me the three most important things.") }, minLines = 3, shape = RoundedCornerShape(12.dp))
            Muted(if (agent.spec.prompt.isNotBlank()) "This agent already has instructions, so you can leave this empty and only choose when." else "This agent has no instructions yet, so say here what it should do each time.")
            Label("When: say it in your own words")
            OutlinedTextField(whenText, { whenText = it; if (parsed != null && parsedFor != it.trim()) parsed = null }, Modifier.fillMaxWidth(), placeholder = { Text("For example: every Monday and Thursday at 7:30, and on the 1st of each month at 9. Or: weekdays at 8:15, but not in August.") }, minLines = 2, shape = RoundedCornerShape(12.dp))
            fun understand(text: String) { reading = true; err = ""; scope.launch { try { val p = withContext(Dispatchers.IO) { api.parseSchedule(text) }; parsed = p; parsedFor = whenText.trim() } catch (e: ApiError) { parsed = null; err = e.message ?: "" }; reading = false } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ if (whenText.isBlank()) err = "Write when it should run, for example: every Monday and Thursday at 7:30." else understand(whenText) }, Modifier.heightIn(min = 48.dp), enabled = !reading, shape = RoundedCornerShape(12.dp)) { Text(if (reading) "Reading…" else "Understand") }
                if (agent.spec.prompt.isNotBlank() && timingHint(agent.spec.prompt)) TextButton({ whenText = agent.spec.prompt; understand(agent.spec.prompt) }, Modifier.heightIn(min = 48.dp), enabled = !reading) { Text("Read it from the agent's instructions") }
                if (job != null) Muted("Now: ${job.human}")
            }
            parsed?.let { p -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = cardBorder()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text("Understood: ${p.human}", fontWeight = FontWeight.SemiBold); Muted("Next: ${p.next.joinToString(" · ")} (${p.tz})") } } }
            Label(if (parsed != null) "Or pick" else "Or pick quickly")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { types.forEach { (v, l) -> FilterChip(st.value.type == v && parsed == null, { upd { type = v }; parsed = null; whenText = "" }, { Text(l) }, Modifier.heightIn(min = 48.dp), shape = CircleShape, colors = chipColors) } }
            val t = st.value.type
            if (t == "weekly") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DAYS.forEachIndexed { i, d -> FilterChip(st.value.dow == i, { upd { dow = i } }, { Text(d.take(3)) }, Modifier.heightIn(min = 48.dp), shape = CircleShape) } }
            if (t in listOf("daily", "weekdays", "weekly")) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Muted("at"); OutlinedButton({ showTime = true }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("%02d:%02d".format(st.value.h, st.value.m)) }; Muted("Times use $tz") }
            if (t == "every" || t == "once") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted(if (t == "every") "every" else "in")
                    OutlinedTextField(if (t == "every") st.value.every else st.value.inN, { v -> upd { if (t == "every") every = v.filter { it.isDigit() } else inN = v.filter { it.isDigit() } } }, Modifier.width(96.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))
                    listOf("minutes", "hours", "days").forEach { u -> FilterChip(st.value.unit == u, { upd { unit = u } }, { Text(u) }, Modifier.heightIn(min = 48.dp), shape = CircleShape) }
                }
            }
            if (t == "cron") OutlinedTextField(st.value.cron, { v -> upd { cron = v } }, Modifier.fillMaxWidth(), label = { Text("Cron, like 0 8 * * 1-5") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            if (t == "hook") Muted("You get a private web link. Anything that calls it (a phone shortcut, GitHub, another service) starts the agent and hands its text to it.")
            if (t == "manual") Muted("It only runs when you press Run now.")
            Label("Message me the result"); Segmented(listOf("always" to "Always", "errors" to "If it fails", "never" to "Never"), notify) { notify = it }
            Label("Memory between runs"); Segmented(listOf("1" to "Remember progress", "0" to "Keep the whole chat"), if (fresh) "1" else "0") { fresh = it == "1" }
            Muted(if (fresh) "Each run starts fresh (cheap), but the agent keeps short notes for itself. A series like daily lessons never repeats itself and keeps building." else "It continues this agent's own chat, so it remembers everything but uses more tokens over time.")
            if (fresh && job != null) OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("What it remembers (it writes this itself)") }, placeholder = { Text("Nothing yet. It fills in after the first run.") }, minLines = 2, shape = RoundedCornerShape(12.dp))
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (job != null) {
                    TextButton({ confirmDelete = true }, Modifier.heightIn(min = 48.dp)) { Text("Stop repeating", color = MaterialTheme.colorScheme.error) }
                    TextButton({ scope.launch { try { withContext(Dispatchers.IO) { api.runJob(job.id) }; onClose() } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Run now") }
                }
                OutlinedButton(onClose, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Cancel") }
                Button({
                    if (prompt.isBlank() && agent.spec.prompt.isBlank()) { err = "Say what it should do each time, or give the agent instructions first."; return@Button }
                    busy = true
                    scope.launch { try { val sched = withContext(Dispatchers.IO) { if (parsed != null && parsedFor == whenText.trim()) parsed!!.schedule else if (whenText.isNotBlank()) api.parseSchedule(whenText).schedule else toSchedule(st.value, tz) }; withContext(Dispatchers.IO) { api.saveJob(job?.id, JSONObject().put("agent_id", agent.id).put("name", job?.name ?: agent.spec.name).put("prompt", prompt).put("schedule", sched).put("enabled", enabled).put("fresh", fresh).put("notify", notify).also { if (job != null) it.put("notes", notes) }) }; onSaved() } catch (e: ApiError) { err = "Couldn't save: ${e.message}"; busy = false } }
                }, Modifier.heightIn(min = 48.dp), enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text(if (busy) "Saving…" else "Save") }
            }
        }
    }
    if (showTime) {
        val ts = rememberTimePickerState(st.value.h, st.value.m, true)
        AlertDialog({ showTime = false }, confirmButton = { TextButton({ upd { h = ts.hour; m = ts.minute }; showTime = false }, Modifier.heightIn(min = 48.dp)) { Text("OK") } }, dismissButton = { TextButton({ showTime = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } }, text = { TimeInput(ts) })
    }
    if (confirmDelete && job != null) AlertDialog({ confirmDelete = false }, title = { Text("Stop repeating ${agent.spec.name}?") }, text = { Text("It will no longer run by itself. Past results stay in Activity.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { try { withContext(Dispatchers.IO) { api.deleteJob(job.id) }; onSaved() } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Stop repeating", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }, Modifier.heightIn(min = 48.dp)) { Text("Keep it") } })
}

// ---- activity tab ----
@Composable
fun ActivityTab(acts: List<Act>, seen: Long, err: String, topInset: Boolean) {
    var filter by remember { mutableStateOf("all") }
    var open by remember { mutableStateOf<Long?>(null) }
    val list = acts.filter { filter == "all" || (filter == "results" && it.kind == "run") || (filter == "messages" && it.kind == "notify") || (filter == "thoughts" && it.kind == "thought") || (filter == "problems" && (it.kind == "error" || it.kind == "system")) }
    val dayFmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }; val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val today = remember { dayFmt.format(Date()) }; val yesterday = remember { dayFmt.format(Date(System.currentTimeMillis() - 86400000)) }
    Column(Modifier.fillMaxSize().then(if (topInset) Modifier.statusBarsPadding() else Modifier)) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Activity", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f)); StatusPill(err) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { Segmented(listOf("all" to "Everything", "results" to "Results", "messages" to "Messages", "thoughts" to "Thoughts", "problems" to "Problems"), filter) { filter = it } }
        if (list.isEmpty()) Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Nothing here yet", style = MaterialTheme.typography.titleMedium); Muted("When a task runs or an agent messages you, it shows up here.") }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var last = ""
            list.forEach { e ->
                val d = dayFmt.format(Date(e.at * 1000)); val label = when (d) { today -> "Today"; yesterday -> "Yesterday"; else -> d }
                if (label != last) { last = label; item(key = "d$label") { Label(label) } }
                item(key = e.id) {
                    val isOpen = open == e.id
                    Card({ open = if (isOpen) null else e.id }, Modifier.animateContentSize(), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = if (e.id > seen && e.kind != "system" && e.kind != "thought") BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f)) else cardBorder()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = if (isOpen) 6 else 1, overflow = TextOverflow.Ellipsis)
                                Muted(timeFmt.format(Date(e.at * 1000)))
                            }
                            Text(e.body, style = MaterialTheme.typography.bodyMedium, maxLines = if (isOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, color = if (e.kind == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

// ---- settings: automations + messages + phone notifications ----
@Composable
fun AutoSettings(api: Api, srv: SrvSettings?, prefs: Prefs, onPrefs: (Prefs) -> Unit, onSrv: (SrvSettings) -> Unit, lastAct: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok || Build.VERSION.SDK_INT < 33) { Notifier.enable(ctx, lastAct); onPrefs(prefs.copy(notify = true)) } else note = "Notifications are blocked for this app in Android settings."
    }
    fun put(b: JSONObject, msg: String = "") { scope.launch { try { onSrv(withContext(Dispatchers.IO) { api.putSettings(b) }); note = msg } catch (e: ApiError) { note = e.message ?: "" } } }
    SectionCard {
        Label("Phone notifications")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Show a notification for new results and messages", Modifier.weight(1f)); Switch(prefs.notify, { on ->
            if (on) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else { Notifier.enable(ctx, lastAct); onPrefs(prefs.copy(notify = true)) } }
            else { Notifier.disable(ctx); onPrefs(prefs.copy(notify = false)) } }) }
        Muted(if (prefs.notify) "On. The app keeps one quiet connection to your server, so a message arrives the moment an agent has news. You will see a small silent \"Agents is listening\" notification: Android needs it to keep the connection alive." else "Turn this on to get a notification as soon as a task finishes or an agent messages you. You do not need Telegram or any other app.")
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (prefs.notify && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Muted("Some phones stop background apps to save battery. Allow Agents to keep running so messages are never late.")
            OutlinedButton({ try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))) } catch (_: Exception) { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Allow in the background") }
        }
        if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    if (srv == null) { SectionCard { Muted("Loading server settings…") }; return }
    var tz by remember(srv.tz) { mutableStateOf(srv.tz) }
    var mem by remember(srv.memory) { mutableStateOf(srv.memory) }
    var tgToken by remember { mutableStateOf("") }
    var ntfyS by remember(srv.ntfyServer) { mutableStateOf(srv.ntfyServer) }; var ntfyT by remember(srv.ntfyTopic) { mutableStateOf(srv.ntfyTopic) }
    var hook by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    SectionCard {
        Label("Who pays")
        Text("Agents use by default"); Segmented(listOf("subscription" to "My Claude subscription", "api" to "My API key"), srv.defaultBilling) { v -> if (v == "api" && !srv.apiKeySet) note = "Add your API key first." else put(JSONObject().put("default_billing", v), "Saved") }
        if (srv.apiKeySet) {
            Text("API key saved, ending ${srv.apiKeyTail}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ scope.launch { note = try { withContext(Dispatchers.IO) { api.testApiKey() }.ifEmpty { "The key works." } } catch (e: ApiError) { e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Test") }
                OutlinedButton({ put(JSONObject().put("api_key", ""), "Key removed") }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Remove") }
            }
        } else {
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Your Anthropic API key") }, placeholder = { Text("sk-ant-…") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), shape = RoundedCornerShape(12.dp))
            Button({ if (apiKey.isNotBlank()) scope.launch { try { onSrv(withContext(Dispatchers.IO) { api.putSettings(JSONObject().put("api_key", apiKey.trim())) }); val bad = withContext(Dispatchers.IO) { api.testApiKey() }
                if (bad.isNotEmpty()) { onSrv(withContext(Dispatchers.IO) { api.putSettings(JSONObject().put("api_key", "")) }); note = bad } else { note = "The key works and is saved."; apiKey = "" } } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Save and test") }
        }
        Muted("The subscription is a flat price with shared limits. An API key is pay as you go: every run costs real money, with no subscription limits. The key stays on your server and is never shown again. You can also choose for each agent separately in its settings.")
    }
    SectionCard {
        Label("Repeating agents")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Let agents repeat by themselves", Modifier.weight(1f)); Switch(!srv.paused, { put(JSONObject().put("paused", !it)) }) }
        Text("At the same time, at most"); Segmented(listOf("1" to "1", "2" to "2", "3" to "3", "4" to "4"), srv.maxParallel.toString()) { put(JSONObject().put("max_parallel", it.toInt())) }
        Muted("Each run uses your Claude subscription. A lower number keeps usage calm.")
        OutlinedTextField(tz, { tz = it }, Modifier.fillMaxWidth(), label = { Text("Time zone") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ put(JSONObject().put("tz", tz), "Time zone saved") }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Save") }
            if (TimeZone.getDefault().id != srv.tz) OutlinedButton({ put(JSONObject().put("tz", TimeZone.getDefault().id), "Time zone saved") }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Use ${TimeZone.getDefault().id}") }
        }
        OutlinedTextField(mem, { mem = it }, Modifier.fillMaxWidth(), label = { Text("Shared memory (every agent knows this)") }, minLines = 2, supportingText = { Text("${mem.length}/6000") }, shape = RoundedCornerShape(12.dp))
        OutlinedButton({ put(JSONObject().put("global_memory", mem), "Memory saved") }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Save memory") }
    }
    SectionCard {
        Label("Message me"); Muted("Where your agents send their messages. The app inbox is always on. Add the ones you want.")
        Text("Telegram", fontWeight = FontWeight.SemiBold)
        when {
            srv.tgPaired -> { Muted("Linked. You can also chat with your agents there."); OutlinedButton({ scope.launch { try { withContext(Dispatchers.IO) { api.telegramUnlink() }; onSrv(withContext(Dispatchers.IO) { api.settings() }) } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Unlink") } }
            srv.tgToken -> { Muted("Open your bot in Telegram and send this message:"); Text("/pair ${srv.tgCode ?: "…"}", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ scope.launch { try { withContext(Dispatchers.IO) { api.telegramPair() }; onSrv(withContext(Dispatchers.IO) { api.settings() }) } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("New code") }
                    OutlinedButton({ put(JSONObject().put("telegram_token", "")) }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Remove bot") } } }
            else -> { Muted("Create a bot with @BotFather in Telegram, paste its token here, then link your chat.")
                OutlinedTextField(tgToken, { tgToken = it }, Modifier.fillMaxWidth(), label = { Text("Bot token") }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), shape = RoundedCornerShape(12.dp))
                Button({ if (tgToken.isNotBlank()) scope.launch { try { withContext(Dispatchers.IO) { api.putSettings(JSONObject().put("telegram_token", tgToken.trim())); api.telegramPair() }; onSrv(withContext(Dispatchers.IO) { api.settings() }) } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Connect") } }
        }
        Text("Phone notifications with ntfy", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(ntfyS, { ntfyS = it }, Modifier.fillMaxWidth(), label = { Text("Server (default ntfy.sh)") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(ntfyT, { ntfyT = it }, Modifier.fillMaxWidth(), label = { Text("Topic: a long random name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        Muted("Install the free ntfy app and subscribe to the same topic. Anyone who knows the topic can read your messages.")
        Text("Web hook", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(hook, { hook = it }, Modifier.fillMaxWidth(), label = { Text(if (srv.webhook) "Saved. Type to replace" else "https://example.com/my-hook") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        Muted("Gets a JSON message {title, body}. Use it for Slack, Discord, Home Assistant and more.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ put(JSONObject().put("ntfy_server", ntfyS).put("ntfy_topic", ntfyT).also { if (hook.isNotBlank()) it.put("webhook_url", hook) }, "Saved") }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Save") }
            Button({ scope.launch { note = try { withContext(Dispatchers.IO) { api.testNotify() }.let { if (it.isEmpty()) "Test message sent. Check your channels." else "Problem: $it" } } catch (e: ApiError) { e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Send a test") }
        }
    }
}

// ---- life: an agent that lives on its own ----
private val TIMING = Regex("\\b(every (day|morning|evening|night|hour|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|each (day|morning|evening|week)|daily|weekly|hourly|always|forever|24/7|all the time|keep (on )?(working|going|developing)|live online)\\b", RegexOption.IGNORE_CASE)
fun timingHint(prompt: String) = TIMING.containsMatchIn(prompt)
fun wakeText(t: Long): String { if (t <= 0) return ""; val d = Date(t * 1000); val same = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(d) == SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()); return (if (same) "today " else SimpleDateFormat("EEE d MMM ", Locale.getDefault()).format(d)) + SimpleDateFormat("HH:mm", Locale.getDefault()).format(d) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeSheet(agent: Agent, api: Api, onClose: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sp = agent.spec
    var journal by remember { mutableStateOf(agent.journal) }
    var orders by remember { mutableStateOf(agent.orders.joinToString("\n")) }
    var wakeMin by remember { mutableStateOf(sp.wakeMin.toString()) }; var maxWakes by remember { mutableStateOf(sp.maxWakes.toString()) }
    var err by remember { mutableStateOf("") }
    fun put(patch: Spec) { scope.launch { try { withContext(Dispatchers.IO) { api.save(agent.id, patch) }; onSaved() } catch (e: ApiError) { err = e.message ?: "" } } }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (sp.autonomous) "${sp.name} is alive" else "${sp.name} is not alive", style = MaterialTheme.typography.headlineMedium)
            Muted(if (sp.autonomous) "It wakes up by itself, thinks about its purpose (its instructions), does things with its tools, writes in its journal, and decides when to wake next. It is not waiting for you." else "Turn this on and the agent lives on the server by itself: it decides what to do and when, keeps working for days, and tells you only when it matters.")
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("Lives on its own", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(sp.autonomous, { on -> put(sp.copy(autonomous = on, autonomy = if (on && sp.autonomy == "safe") "standard" else sp.autonomy)) }) }
            if (sp.autonomous) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = cardBorder()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (agent.running) "Awake right now." else if (agent.nextWake > 0) "Next wake-up: ${wakeText(agent.nextWake)}" + (if (agent.wakeReason.isNotBlank()) " · ${agent.wakeReason}" else "") else "Waking up soon.")
                    Muted("${agent.wakes} wake-up${if (agent.wakes == 1L) "" else "s"} so far" + if (agent.lastWake > 0) " · last ${ago(agent.lastWake)}" else "") } }
                OutlinedTextField(orders, { orders = it }, Modifier.fillMaxWidth(), label = { Text("Rules it follows from now on (one per line)") }, placeholder = { Text("For example: You may create accounts without asking me.") }, minLines = 2, shape = RoundedCornerShape(12.dp))
                Muted("You can also just tell the agent in chat, for example \"from now on ...\". It saves the rule here itself.")
                OutlinedTextField(journal, { journal = it }, Modifier.fillMaxWidth(), label = { Text("Its journal (it writes this itself, you can edit)") }, minLines = 5, shape = RoundedCornerShape(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(wakeMin, { wakeMin = it.filter { c -> c.isDigit() } }, Modifier.weight(1f), label = { Text("Wake at most every (min)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(maxWakes, { maxWakes = it.filter { c -> c.isDigit() } }, Modifier.weight(1f), label = { Text("Wake-ups per day") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))
                }
                Muted("Every wake-up uses your Claude subscription. Fewer wake-ups and a daily cost limit keep usage calm. What it may do on the server is set by its access level (now: ${when (sp.autonomy) { "full" -> "everything"; "standard" -> "edit files and browse"; else -> "look only" }}). To let it run programs, such as tests or scripts, set its access to Everything.")
            } else Muted("Tip: write its purpose in the Instructions, for example: Explain something new about quantum computers every morning, starting from the basics. It works out the schedule by itself.")
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (sp.autonomous) TextButton({ scope.launch { try { withContext(Dispatchers.IO) { api.wakeNow(agent.id) }; onClose() } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Wake up now") }
                OutlinedButton(onClose, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Close") }
                if (sp.autonomous) Button({ scope.launch { try { withContext(Dispatchers.IO) { api.saveJournal(agent.id, journal); api.putOrders(agent.id, orders); api.save(agent.id, sp.copy(wakeMin = (wakeMin.toIntOrNull() ?: 5).coerceAtLeast(1), maxWakes = (maxWakes.toIntOrNull() ?: 24).coerceIn(1, 200))) }; onSaved() } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Save") }
            }
        }
    }
}

/** After saving an agent whose instructions mention timing: offer to make it repeat, reading the timing from those instructions. */
@Composable
fun OfferRepeatDialog(agentId: Long, name: String, instructions: String, api: Api, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var parsed by remember { mutableStateOf<Parsed?>(null) }; var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    AlertDialog(onClose, title = { Text("Make $name repeat?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("The instructions mention timing. I can read it and set up the repeat for you.")
        parsed?.let { p -> Text("Understood: ${p.human}", fontWeight = FontWeight.SemiBold); Muted("Next: ${p.next.take(4).joinToString(" · ")}") }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } },
        confirmButton = { TextButton({ scope.launch { busy = true; err = ""; try {
            val p = parsed
            if (p == null) parsed = withContext(Dispatchers.IO) { api.parseSchedule(instructions) }
            else { withContext(Dispatchers.IO) { api.saveJob(null, JSONObject().put("agent_id", agentId).put("name", name).put("prompt", "").put("schedule", p.schedule).put("enabled", true).put("fresh", true).put("notify", "always")) }; onDone() }
        } catch (e: ApiError) { err = (e.message ?: "") + " You can use the Repeat button instead." }; busy = false } }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(if (busy) "Reading…" else if (parsed == null) "Read the timing" else "Start repeating") } },
        dismissButton = { TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("No thanks") } })
}

/** Asked once, right after connecting: do you want a popup when an agent messages you? */
@Composable
fun AskNotifyDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(onNo, title = { Text("Get a popup when an agent messages you?") },
        text = { Text("Your agents work on the server by themselves. With this on, your phone shows a popup the moment one of them sends you something. You can change it any time in Settings.") },
        confirmButton = { TextButton(onYes, Modifier.heightIn(min = 48.dp)) { Text("Turn on") } },
        dismissButton = { TextButton(onNo, Modifier.heightIn(min = 48.dp)) { Text("Not now") } })
}
