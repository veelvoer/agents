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

// ---- tasks tab ----
@Composable
fun TasksTab(jobs: List<Job>?, err: String, paused: Boolean, topInset: Boolean, api: Api, onChanged: () -> Unit, onPause: (Boolean) -> Unit, onEdit: (Job?) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().then(if (topInset) Modifier.statusBarsPadding() else Modifier)) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Automations", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Button({ onEdit(null) }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Icon(L.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New") }
        }
        if (err.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { StatusPill(err) }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = if (paused) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else cardBorder()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (paused) "Paused. Nothing runs by itself until you turn it back on." else "Running. Tasks start on time, even when this app is closed.", Modifier.weight(1f))
                        Switch(!paused, { onPause(!it) })
                    }
                }
            }
            if (note.isNotEmpty()) item { Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (jobs == null) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) } }
            else if (jobs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing scheduled yet", style = MaterialTheme.typography.titleMedium)
                    Muted("A task is something an agent does by itself: every morning, every hour, or when a web link is called. It can message you when it is done.")
                    Muted("Or just tell an agent in chat: \"every weekday at 8:00 tell me the weather\".")
                }
            }
            items(jobs ?: emptyList(), key = { it.id }) { j ->
                val isOpen = open == j.id
                Card({ open = if (isOpen) null else j.id }, Modifier.animateContentSize(), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = cardBorder()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val c = when { j.running -> MaterialTheme.colorScheme.primary; !j.enabled -> MaterialTheme.colorScheme.outline.copy(alpha = .5f); j.lastStatus == "error" -> MaterialTheme.colorScheme.primary; j.lastStatus == "ok" -> Ok; else -> MaterialTheme.colorScheme.outline }
                            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                            Column(Modifier.weight(1f)) {
                                Text(j.name, fontWeight = FontWeight.SemiBold)
                                Muted("${j.agent} · ${j.human}" + (if (j.enabled && j.next != null) " · next ${j.next}" else "") + (if (j.lastRun > 0) " · last ${ago(j.lastRun)}" else ""))
                            }
                            Switch(j.enabled, { on -> scope.launch { try { withContext(Dispatchers.IO) { api.jobEnabled(j.id, on) }; onChanged() } catch (e: ApiError) { note = e.message ?: "" } } })
                        }
                        if (isOpen) {
                            Muted(j.prompt)
                            if (j.lastText.isNotBlank()) { Label("Last result"); Text(j.lastText, style = MaterialTheme.typography.bodyMedium) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton({ scope.launch { try { withContext(Dispatchers.IO) { api.runJob(j.id) }; note = ""; onChanged() } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Run now") }
                                OutlinedButton({ onEdit(j) }, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Edit") }
                                Spacer(Modifier.weight(1f))
                                Muted("${j.runs} run${if (j.runs == 1) "" else "s"} · $${"%.3f".format(j.cost)}")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JobEditorSheet(job: Job?, agents: List<Agent>, tz: String, api: Api, openAgent: Long?, onClose: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(job?.name ?: "") }
    var agentId by remember { mutableLongStateOf(job?.agentId ?: openAgent ?: agents.firstOrNull()?.id ?: 0L) }
    var prompt by remember { mutableStateOf(job?.prompt ?: "") }
    val st = remember { mutableStateOf(fromSchedule(job?.schedule)) }
    var notify by remember { mutableStateOf(job?.notify ?: "always") }
    var fresh by remember { mutableStateOf(job?.fresh ?: true) }
    var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    fun upd(f: SchedState.() -> Unit) { val c = st.value.copy(); c.f(); st.value = c }
    val types = listOf("daily" to "Every day", "weekdays" to "Weekdays", "weekly" to "Weekly", "every" to "Every…", "once" to "Once", "hook" to "Web link", "manual" to "Manual", "cron" to "Cron")
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (job == null) "New task" else "Edit task", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("Morning news") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Label("Agent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { agents.forEach { a -> FilterChip(agentId == a.id, { agentId = a.id }, { Text(a.spec.name) }, Modifier.heightIn(min = 48.dp), shape = CircleShape, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)) } }
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("What should it do?") }, placeholder = { Text("Check the news about my topics and give me the three most important things.") }, minLines = 3, shape = RoundedCornerShape(12.dp))
            Label("When")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { types.forEach { (v, l) -> FilterChip(st.value.type == v, { upd { type = v } }, { Text(l) }, Modifier.heightIn(min = 48.dp), shape = CircleShape, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)) } }
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
            if (t == "hook") Muted("You get a private web link. Anything that calls it (a phone shortcut, GitHub, another service) starts the task and hands its text to the agent.")
            if (t == "manual") Muted("It only runs when you press Run now.")
            Label("Message me the result"); Segmented(listOf("always" to "Always", "errors" to "If it fails", "never" to "Never"), notify) { notify = it }
            Label("Session"); Segmented(listOf("1" to "Fresh each time", "0" to "Keep the chat"), if (fresh) "1" else "0") { fresh = it == "1" }
            Muted(if (fresh) "Fresh each time is cheaper: the agent does not carry old conversation along." else "The agent continues its own chat, so it remembers earlier runs but uses more tokens over time.")
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (job != null) TextButton({ confirmDelete = true }, Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClose, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Cancel") }
                Button({
                    if (name.isBlank()) { err = "Give the task a name."; return@Button }
                    if (prompt.isBlank()) { err = "Say what it should do."; return@Button }
                    val sched = try { toSchedule(st.value, tz) } catch (e: ApiError) { err = e.message ?: ""; return@Button }
                    busy = true
                    scope.launch { try { withContext(Dispatchers.IO) { api.saveJob(job?.id, JSONObject().put("agent_id", agentId).put("name", name.trim()).put("prompt", prompt).put("schedule", sched).put("enabled", job?.enabled ?: true).put("fresh", fresh).put("notify", notify)) }; onSaved() } catch (e: ApiError) { err = "Couldn't save: ${e.message}"; busy = false } }
                }, Modifier.heightIn(min = 48.dp), enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text(if (busy) "Saving…" else "Save") }
            }
        }
    }
    if (showTime) {
        val ts = rememberTimePickerState(st.value.h, st.value.m, true)
        AlertDialog({ showTime = false }, confirmButton = { TextButton({ upd { h = ts.hour; m = ts.minute }; showTime = false }, Modifier.heightIn(min = 48.dp)) { Text("OK") } }, dismissButton = { TextButton({ showTime = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } }, text = { TimeInput(ts) })
    }
    if (confirmDelete && job != null) AlertDialog({ confirmDelete = false }, title = { Text("Delete ${job.name}?") }, text = { Text("The task stops and is removed. Its past results stay in Activity.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { try { withContext(Dispatchers.IO) { api.deleteJob(job.id) }; onSaved() } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }, Modifier.heightIn(min = 48.dp)) { Text("Keep it") } })
}

// ---- activity tab ----
@Composable
fun ActivityTab(acts: List<Act>, seen: Long, err: String, topInset: Boolean) {
    var filter by remember { mutableStateOf("all") }
    var open by remember { mutableStateOf<Long?>(null) }
    val list = acts.filter { filter == "all" || (filter == "results" && it.kind == "run") || (filter == "messages" && it.kind == "notify") || (filter == "problems" && (it.kind == "error" || it.kind == "system")) }
    val dayFmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }; val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val today = remember { dayFmt.format(Date()) }; val yesterday = remember { dayFmt.format(Date(System.currentTimeMillis() - 86400000)) }
    Column(Modifier.fillMaxSize().then(if (topInset) Modifier.statusBarsPadding() else Modifier)) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Activity", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f)); StatusPill(err) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { Segmented(listOf("all" to "Everything", "results" to "Results", "messages" to "Messages", "problems" to "Problems"), filter) { filter = it } }
        if (list.isEmpty()) Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Nothing here yet", style = MaterialTheme.typography.titleMedium); Muted("When a task runs or an agent messages you, it shows up here.") }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var last = ""
            list.forEach { e ->
                val d = dayFmt.format(Date(e.at * 1000)); val label = when (d) { today -> "Today"; yesterday -> "Yesterday"; else -> d }
                if (label != last) { last = label; item(key = "d$label") { Label(label) } }
                item(key = e.id) {
                    val isOpen = open == e.id
                    Card({ open = if (isOpen) null else e.id }, Modifier.animateContentSize(), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = if (e.id > seen && e.kind != "system") BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f)) else cardBorder()) {
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
        if (ok || Build.VERSION.SDK_INT < 33) { Notifier.resetMarker(ctx, lastAct); Notifier.schedule(ctx); onPrefs(prefs.copy(notify = true)) } else note = "Notifications are blocked for this app in Android settings."
    }
    fun put(b: JSONObject, msg: String = "") { scope.launch { try { onSrv(withContext(Dispatchers.IO) { api.putSettings(b) }); note = msg } catch (e: ApiError) { note = e.message ?: "" } } }
    SectionCard {
        Label("Phone notifications")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Show a notification for new results and messages", Modifier.weight(1f)); Switch(prefs.notify, { on ->
            if (on) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else { Notifier.resetMarker(ctx, lastAct); Notifier.schedule(ctx); onPrefs(prefs.copy(notify = true)) } }
            else { Notifier.cancel(ctx); onPrefs(prefs.copy(notify = false)) } }) }
        Muted("Android checks about every 15 minutes. For instant messages, also set up ntfy or Telegram below.")
        if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    if (srv == null) { SectionCard { Muted("Loading server settings…") }; return }
    var tz by remember(srv.tz) { mutableStateOf(srv.tz) }
    var mem by remember(srv.memory) { mutableStateOf(srv.memory) }
    var tgToken by remember { mutableStateOf("") }
    var ntfyS by remember(srv.ntfyServer) { mutableStateOf(srv.ntfyServer) }; var ntfyT by remember(srv.ntfyTopic) { mutableStateOf(srv.ntfyTopic) }
    var hook by remember { mutableStateOf("") }
    SectionCard {
        Label("Automations")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Run automations", Modifier.weight(1f)); Switch(!srv.paused, { put(JSONObject().put("paused", !it)) }) }
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
