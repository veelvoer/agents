package dev.voer.agents

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.json.JSONObject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun fmtN(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1000 -> "%.1fk".format(n / 1e3); else -> n.toString() }
internal fun String.modelName(models: List<Pair<String, String>>): String = models.firstOrNull { it.first == this }?.second ?: this
internal fun ago(t: Long): String { if (t == 0L) return ""; val s = (System.currentTimeMillis() / 1000 - t).coerceAtLeast(0); return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" } }

@Composable internal fun <T> motionSpec(ms: Int) = if (LocalMotion.current) tween<T>(ms) else snap()
@Composable internal fun cardColors() = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)
@Composable internal fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
@Composable internal fun Muted(t: String, mod: Modifier = Modifier) = Text(t, mod, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
@Composable internal fun Label(t: String) = Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = cardBorder()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }

@Composable
fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true) =
    IconButton(onClick, Modifier.size(48.dp), enabled = enabled) { Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp)) }

@Composable
fun StatusPill(err: String) {
    val ok = err.isEmpty()
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, border = cardBorder()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Ok else MaterialTheme.colorScheme.primary))
            Muted(if (ok) "Connected" else "Offline")
        }
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (v, label) ->
            val on = v == value
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, motionSpec(180), label = "seg")
            Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).background(bg).semantics { role = Role.RadioButton; contentDescription = label + if (on) ", selected" else "" }
                .then(Modifier.clickableNoRipple { onChange(v) }).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun MainScreen(api: Api, prefs: Prefs, onPrefs: (Prefs) -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var agents by remember { mutableStateOf<List<Agent>?>(null) }
    var models by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Pair<Agent?, Boolean>?>(null) }
    var release by remember { mutableStateOf<Release?>(null) }
    var relErr by remember { mutableStateOf("") }
    var serverVersion by remember { mutableStateOf("") }
    var updProgress by remember { mutableStateOf<Float?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<Job>?>(null) }
    var acts by remember { mutableStateOf<List<Act>>(emptyList()) }
    var srv by remember { mutableStateOf<SrvSettings?>(null) }
    var paused by remember { mutableStateOf(false) }
    var actLast by remember { mutableLongStateOf(0L) }
    var rev by remember { mutableLongStateOf(0L) }
    var repeatFor by remember { mutableStateOf<Long?>(null) }
    var lifeFor by remember { mutableStateOf<Long?>(null) }
    var offer by remember { mutableStateOf<Triple<Long, String, String>?>(null) }
    var askNotify by remember { mutableStateOf(!prefs.notify && !ctx.getSharedPreferences("state", Context.MODE_PRIVATE).getBoolean("askedNotify", false)) }
    val notifyPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok || android.os.Build.VERSION.SDK_INT < 33) { Notifier.enable(ctx, actLastState.value); onPrefs(prefs.copy(notify = true)) } }
    val state = remember { ctx.getSharedPreferences("state", Context.MODE_PRIVATE) }
    var seen by remember { mutableLongStateOf(state.getLong("seen", -1L)) }
    val scope = rememberCoroutineScope()
    val appVersion = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Long poll while the app is on screen: the server answers the moment something changes, and an idle app costs almost nothing.
    LaunchedEffect(Unit) {
        try { models = withContext(Dispatchers.IO) { api.models() } } catch (_: ApiError) {}
        try { serverVersion = withContext(Dispatchers.IO) { api.health() } } catch (_: Exception) {}
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            rev = 0L
            while (true) {
                try { val l = withContext(Dispatchers.IO) { api.agents(rev) }; rev = l.rev; agents = l.agents; paused = l.paused; actLast = l.actLast; err = "" }
                catch (e: CancellationException) { throw e }
                catch (e: ApiError) { err = e.message ?: "offline"; rev = 0; delay(4000) }
                catch (e: Exception) { rev = 0; delay(2000) }
            }
        }
    }
    LaunchedEffect(actLast) { try { acts = withContext(Dispatchers.IO) { api.activity().second }; if (seen < 0) { seen = actLast; state.edit().putLong("seen", seen).apply() } } catch (_: ApiError) {} }
    LaunchedEffect(rev) { try { jobs = withContext(Dispatchers.IO) { api.jobs() } } catch (_: ApiError) {} }
    LaunchedEffect(tab, rev) { try { if (tab == 3) srv = withContext(Dispatchers.IO) { api.settings() } } catch (_: ApiError) {} }
    LaunchedEffect(tab, actLast) { if (tab == 1 && actLast > 0) { delay(800); seen = actLast; state.edit().putLong("seen", seen).apply() } }
    LaunchedEffect(prefs.notify) { if (prefs.notify) { Notifier.schedule(ctx); ListenService.start(ctx) } }
    suspend fun checkRelease(manual: Boolean) {
        try { release = withContext(Dispatchers.IO) { Updater.latest() }; relErr = ""; if (manual) dismissed = false }
        catch (e: ApiError) { relErr = e.message ?: "" }
    }
    LaunchedEffect(prefs.autoUpdate) { if (prefs.autoUpdate) checkRelease(false) }
    val updateReady = release?.let { newerVersion(it.version, appVersion) } == true

    fun startUpdate() {
        val r = release ?: return
        scope.launch {
            updProgress = 0f
            try {
                val f = withContext(Dispatchers.IO) { Updater.download(ctx, r) { updProgress = it } }
                updProgress = null
                if (!Updater.install(ctx, f)) relErr = "Allow installs from Agents on the page that opened, go back, and press Update again."
            } catch (e: ApiError) { updProgress = null; relErr = e.message ?: "Update failed." }
        }
    }

    val motion = LocalMotion.current
    actLastState.value = actLast
    val current = agents?.firstOrNull { it.id == open }
    BackHandler(open != null) { open = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (current == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            listOf(Triple("Agents", L.Bot, 0), Triple("Activity", L.Bell, 1), Triple("Usage", L.Chart, 2), Triple("Settings", L.Sliders, 3)).forEach { (label, icon, i) ->
                val n = if (i == 1) acts.count { it.id > seen && it.kind != "system" } else 0
                NavigationBarItem(tab == i, { tab = i }, { BadgedBox({ if (n > 0) Badge { Text(if (n > 9) "9+" else n.toString()) } }) { Icon(icon, null) } }, label = { Text(label, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f), unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad)) {
            if (updateReady && !dismissed && current == null) Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .12f), modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val p = updProgress
                    if (p != null) { Text("Downloading ${release?.version}…", Modifier.weight(1f).padding(vertical = 12.dp)); LinearProgressIndicator({ p }, Modifier.width(100.dp).padding(end = 12.dp)) }
                    else {
                        Text("Version ${release?.version} is ready.", Modifier.weight(1f).padding(vertical = 12.dp))
                        TextButton(::startUpdate, Modifier.heightIn(min = 48.dp)) { Text("Update") }
                        TextButton({ dismissed = true }, Modifier.heightIn(min = 48.dp)) { Text("Later") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                AnimatedContent(current?.id ?: -1L, transitionSpec = { (if (motion) slideInHorizontally(tween(240)) { it / 8 } + fadeIn(tween(200)) else fadeIn(snap())) togetherWith (if (motion) fadeOut(tween(120)) else fadeOut(snap())) }, label = "page") { id ->
                    if (id != -1L && current != null) ChatScreen(api, current, models, repeatText((jobs ?: emptyList()).filter { it.agentId == current.id }), { open = null }, { editing = current to true }, { repeatFor = current.id }, { lifeFor = current.id }, { a -> scope.launch { try { withContext(Dispatchers.IO) { api.pin(a.id, !a.pinned) } } catch (_: ApiError) {} } })
                    else when (tab) {
                        0 -> AgentsTab(agents, err, models, jobs ?: emptyList(), paused, !(updateReady && !dismissed), { open = it }, { editing = null to true }, { scope.launch { try { withContext(Dispatchers.IO) { api.putSettings(JSONObject().put("paused", false)) }; paused = false } catch (_: ApiError) {} } })
                        1 -> ActivityTab(acts, seen, err, !(updateReady && !dismissed))
                        2 -> UsageTab(agents ?: emptyList(), err, models, !(updateReady && !dismissed))
                        else -> SettingsTab(api, prefs, onPrefs, err, appVersion, serverVersion, release, relErr, updateReady, updProgress != null, srv, { srv = it }, actLast, { scope.launch { checkRelease(true) } }, ::startUpdate,
                            { scope.launch { try { val to = withContext(Dispatchers.IO) { api.updateServer() }; relErr = "Updating the server to ${to.removePrefix("agents-server ")}…"; delay(6000); serverVersion = withContext(Dispatchers.IO) { api.health() }; relErr = "" } catch (e: ApiError) { relErr = e.message ?: "" } } }, onLogout)
                    }
                }
            }
        }
    }
    if (askNotify && agents != null) AskNotifyDialog({ askNotify = false; ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putBoolean("askedNotify", true).apply(); if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else { Notifier.enable(ctx, actLast); onPrefs(prefs.copy(notify = true)) } }, { askNotify = false; ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putBoolean("askedNotify", true).apply() })
    offer?.let { (id, nm, ins) -> OfferRepeatDialog(id, nm, ins, api, { offer = null }, { offer = null; scope.launch { try { jobs = withContext(Dispatchers.IO) { api.jobs() } } catch (_: ApiError) {} } }) }
    lifeFor?.let { id -> agents?.firstOrNull { it.id == id }?.let { ag -> LifeSheet(ag, api, { lifeFor = null }, { lifeFor = null }) } }
    repeatFor?.let { id -> agents?.firstOrNull { it.id == id }?.let { ag -> RepeatSheet(ag, (jobs ?: emptyList()).filter { it.agentId == id }, srv?.tz ?: java.util.TimeZone.getDefault().id, api, { repeatFor = null }, { repeatFor = null; scope.launch { try { jobs = withContext(Dispatchers.IO) { api.jobs() } } catch (_: ApiError) {} } }) } }
    if (editing != null) {
        val a = editing!!.first
        EditorSheet(a, models, api, onClose = { editing = null }, onSaved = { id, sp -> if (a == null) open = id; editing = null; if (!sp.autonomous && timingHint(sp.prompt) && (jobs ?: emptyList()).none { it.agentId == id }) offer = Triple(id, sp.name, sp.prompt) }, onDeleted = { open = null; editing = null })
    }
}

@Composable
fun AgentsTab(agents: List<Agent>?, err: String, models: List<Pair<String, String>>, jobs: List<Job>, paused: Boolean, topInset: Boolean, onOpen: (Long) -> Unit, onNew: () -> Unit, onResume: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = agents?.filter { q.isBlank() || (it.spec.name + " " + it.spec.model.modelName(models)).contains(q.trim(), true) }
    Column(Modifier.fillMaxSize().then(if (topInset) Modifier.statusBarsPadding() else Modifier)) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Agents", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Button(onNew, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Icon(L.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New") }
        }
        if (!agents.isNullOrEmpty()) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Search agents") }, leadingIcon = { Icon(L.Search, null, Modifier.size(20.dp)) }, singleLine = true, shape = CircleShape)
        if (err.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { StatusPill(err); Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (paused) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), colors = cardColors(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) { Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Repeating is paused.", Modifier.weight(1f)); TextButton(onResume, Modifier.heightIn(min = 48.dp)) { Text("Resume") } } }
        when {
            agents == null && err.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            agents.isNullOrEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Muted("No agents yet. Press New to create one.") }
            list.isNullOrEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Muted("No agent matches that.") }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val pinned = list.filter { it.pinned }; val rest = list.filter { !it.pinned }
                if (pinned.isNotEmpty()) item { Label("Pinned") }
                items(pinned, key = { it.id }) { AgentCard(it, models, repeatText(jobs.filter { j -> j.agentId == it.id }), onOpen) }
                if (pinned.isNotEmpty() && rest.isNotEmpty()) item { Label("Agents") }
                items(rest, key = { it.id }) { AgentCard(it, models, repeatText(jobs.filter { j -> j.agentId == it.id }), onOpen) }
            }
        }
    }
}

@Composable
private fun AgentCard(a: Agent, models: List<Pair<String, String>>, repeat: String?, onOpen: (Long) -> Unit) {
    Card({ onOpen(a.id) }, shape = RoundedCornerShape(16.dp), colors = cardColors(), border = cardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val c by animateColorAsState(if (a.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, motionSpec(180), label = "dot")
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Text(a.spec.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (a.pinned) Icon(L.Pin, "Pinned", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                if (a.running) Text("working", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            Muted("${a.spec.model.modelName(models)} · ${fmtN(a.usage.tokens)} tokens · $${"%.3f".format(a.usage.cost)}" + if (a.lastActive > 0) " · ${ago(a.lastActive)}" else "")
            if (a.spec.autonomous) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(L.Sparkle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Text("Alive" + (if (a.running) " · awake now" else if (a.nextWake > 0) " · next thought ${wakeText(a.nextWake)}" else ""), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            else if (repeat != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(L.Repeat, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Text(repeat, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

fun md(t: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    while (i < t.length) {
        when {
            t.startsWith("**", i) && t.indexOf("**", i + 2) > 0 -> { val e = t.indexOf("**", i + 2); pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(t.substring(i + 2, e)); pop(); i = e + 2 }
            t.startsWith("```", i) && t.indexOf("```", i + 3) > 0 -> { val e = t.indexOf("```", i + 3); pushStyle(code); append(t.substring(i + 3, e).substringAfter('\n', t.substring(i + 3, e)).trimEnd()); pop(); i = e + 3 }
            t[i] == '`' && t.indexOf('`', i + 1) > 0 -> { val e = t.indexOf('`', i + 1); pushStyle(code); append(t.substring(i + 1, e)); pop(); i = e + 1 }
            else -> { append(t[i]); i++ }
        }
    }
}

@Composable
fun ChatScreen(api: Api, agent: Agent, models: List<Pair<String, String>>, repeat: String?, onBack: () -> Unit, onEdit: () -> Unit, onRepeat: () -> Unit, onLife: () -> Unit, onPin: (Agent) -> Unit) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var start by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var optimistic by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val running = agent.running || optimistic
    suspend fun syncTail() {
        try {
            val p = withContext(Dispatchers.IO) { api.messages(agent.id) }
            msgs = if (msgs.isEmpty() || p.start <= start) p.messages else msgs.take(p.start - start) + p.messages
            start = if (msgs.size == p.messages.size) p.start else minOf(start, p.start)
            optimistic = false
        } catch (e: ApiError) { note = e.message ?: "" }
    }
    // reload whenever the server says this agent's chat or status changed
    LaunchedEffect(agent.id, agent.running, agent.messages) { syncTail() }
    LaunchedEffect(msgs.size, running) { if (msgs.isNotEmpty() || running) list.animateScrollToItem((msgs.size + if (running) 1 else 0).coerceAtLeast(0) + (if (start > 0) 1 else 0)) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(L.Back, "Back to agents", onBack)
            Column(Modifier.weight(1f)) {
                Text(agent.spec.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Muted("${agent.spec.model.modelName(models)} · ${fmtN(agent.usage.tokens)} tokens · $${"%.3f".format(agent.usage.cost)}")
            }
            IconBtn(L.Sparkle, if (agent.spec.autonomous) "Alive: ${wakeText(agent.nextWake)}" else "Let this agent live on its own", onLife, tint = if (agent.spec.autonomous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            IconBtn(L.Repeat, if (repeat != null) "Repeat settings: $repeat" else "Make this agent repeat", onRepeat, tint = if (repeat != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            IconBtn(L.Pin, if (agent.pinned) "Unpin" else "Pin to top", { onPin(agent) }, tint = if (agent.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            IconBtn(L.Edit, "Edit agent", onEdit); IconBtn(L.Trash, "Clear chat", { confirmClear = true })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (start > 0) item { TextButton({ scope.launch { try { val p = withContext(Dispatchers.IO) { api.messages(agent.id, before = start) }; msgs = p.messages + msgs; start = p.start } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load earlier messages") } }
            if (msgs.isEmpty() && !running) item { Muted("Say something to ${agent.spec.name}.", Modifier.fillMaxWidth().padding(32.dp)) }
            items(msgs.size) { i ->
                val m = msgs[i]
                if (m.role == "auto") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(L.Clock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(6.dp)); Muted(m.text) } }
                else if (m.role == "user") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = cardBorder(), modifier = Modifier.widthIn(max = 300.dp)) { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                } else Row(verticalAlignment = Alignment.Top) {
                    Text(if (m.role == "error") AnnotatedString(m.text) else md(m.text), color = if (m.role == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (m.role == "assistant") IconBtn(L.Copy, "Copy answer", { clip.setText(AnnotatedString(m.text)) }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary); Muted("working") } }
        }
        if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Surface(Modifier.padding(12.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = cardBorder()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message ${agent.spec.name}") }, maxLines = 5,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                FilledIconButton({
                    if (running) { scope.launch { try { withContext(Dispatchers.IO) { api.stop(agent.id) } } catch (e: ApiError) { note = e.message ?: "" } }; return@FilledIconButton }
                    val t = text.trim(); if (t.isEmpty()) return@FilledIconButton
                    text = ""; note = ""; optimistic = true; msgs = msgs + Msg("user", t, System.currentTimeMillis() / 1000)
                    scope.launch { try { withContext(Dispatchers.IO) { api.run(agent.id, t) } } catch (e: ApiError) { note = e.message ?: ""; optimistic = false; syncTail() } }
                }, Modifier.size(48.dp), shape = RoundedCornerShape(14.dp)) { Icon(if (running) L.Stop else L.Send, if (running) "Stop" else "Send", Modifier.size(20.dp)) }
            }
        }
    }
    if (confirmClear) AlertDialog({ confirmClear = false }, title = { Text("Clear the chat with ${agent.spec.name}?") },
        text = { Text("The messages and the conversation memory of this chat are removed. The agent and its usage stay.") },
        confirmButton = { TextButton({ confirmClear = false; scope.launch { try { withContext(Dispatchers.IO) { api.clear(agent.id) }; msgs = emptyList(); start = 0 } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }, Modifier.heightIn(min = 48.dp)) { Text("Keep") } })
}

@Composable
fun UsageTab(agents: List<Agent>, err: String, models: List<Pair<String, String>>, topInset: Boolean) {
    val cost = agents.sumOf { it.usage.cost }; val tok = agents.sumOf { it.usage.tokens }; val cache = agents.sumOf { it.usage.cache }
    val days = remember { (13 downTo 0).map { LocalDate.now(java.time.ZoneOffset.UTC).minusDays(it.toLong()).toString() } }
    val per = days.associateWith { d -> agents.sumOf { it.daily[d] ?: 0.0 } }
    val dmax = (per.values.maxOrNull() ?: 0.0).coerceAtLeast(1e-6)
    val max = (agents.maxOfOrNull { it.usage.cost } ?: 0.0).coerceAtLeast(1e-6)
    val byModel = agents.groupBy { it.spec.model }.mapValues { e -> e.value.sumOf { it.usage.cost } }
    val mmax = (byModel.values.maxOrNull() ?: 0.0).coerceAtLeast(1e-6)
    @Composable fun Bar(label: String, value: Double, frac: Double, color: Color) {
        Row(Modifier.padding(top = 10.dp)) { Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis); Muted("$${"%.3f".format(value)}") }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) { Box(Modifier.fillMaxWidth(frac.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(color)) }
    }
    Column(Modifier.fillMaxSize().then(if (topInset) Modifier.statusBarsPadding() else Modifier).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("Usage", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f)); StatusPill(err) }
        SectionCard {
            Label("Estimated cost"); Text("$${"%.2f".format(cost)}", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 52.sp, lineHeight = 58.sp))
            Row(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "Cost per day, last 14 days" }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { d -> val v = per[d] ?: 0.0
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(if (v > 0) (v / dmax).toFloat().coerceIn(0.04f, 1f) * 0.85f else 0.02f).clip(RoundedCornerShape(3.dp)).background(if (v > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                        Text(d.takeLast(2), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Muted("Counted from your Claude subscription runs. It shows how much you used, not a bill.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(Modifier.weight(1f)) { Label("Working"); Text("${agents.count { it.running }}", style = MaterialTheme.typography.headlineLarge); Muted("of ${agents.size}") }
            SectionCard(Modifier.weight(1f)) { Label("Tokens"); Text(fmtN(tok), style = MaterialTheme.typography.headlineLarge); Muted("in and out") }
        }
        val apiSpend = agents.sumOf { it.usage.apiCost }
        if (apiSpend > 0) SectionCard { Label("Paid with your API key"); Text("$${"%.2f".format(apiSpend)}", style = MaterialTheme.typography.headlineLarge); Muted("Real money, billed by Anthropic to your key") }
        SectionCard { Label("Read from cache"); Text(fmtN(cache), style = MaterialTheme.typography.headlineLarge); Muted("tokens reused cheaply") }
        SectionCard {
            Label("By agent")
            if (agents.isEmpty()) Muted("Nothing yet. Usage appears after an agent answers.")
            agents.sortedByDescending { it.usage.cost }.forEach { Bar(it.spec.name, it.usage.cost, it.usage.cost / max, modelColor(it.spec.model)) }
        }
        SectionCard { Label("By model"); byModel.entries.sortedByDescending { it.value }.forEach { Bar(it.key.modelName(models), it.value, it.value / mmax, modelColor(it.key)) }; if (byModel.isEmpty()) Muted("Nothing yet.") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SettingsTab(api: Api, prefs: Prefs, onPrefs: (Prefs) -> Unit, err: String, appVersion: String, serverVersion: String, release: Release?, relErr: String, updateReady: Boolean, updating: Boolean,
                srv: SrvSettings?, onSrv: (SrvSettings) -> Unit, lastAct: Long, onCheck: () -> Unit, onUpdate: () -> Unit, onUpdateServer: () -> Unit, onLogout: () -> Unit) {
    val serverNew = release != null && serverVersion.isNotEmpty() && newerVersion(release.version, serverVersion)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f)); StatusPill(err) }
        SectionCard {
            Label("Appearance")
            Text("Colours"); Segmented(listOf("clay" to "Warm clay", "system" to "Match my phone"), prefs.colors) { onPrefs(prefs.copy(colors = it)) }
            if (prefs.colors == "system") Muted(if (dynamicColorsAvailable) "Using the colours of your wallpaper (Material You). They change when you change your wallpaper." else "This Android version has no wallpaper colours, so the warm clay colours are used.")
            Text("Light or dark"); Segmented(listOf("system" to "Like my phone", "light" to "Light", "dark" to "Dark"), prefs.mode) { onPrefs(prefs.copy(mode = it)) }
            Text("Text size"); Slider(prefs.textScale, { onPrefs(prefs.copy(textScale = (Math.round(it * 20f) / 20f))) }, valueRange = 0.85f..1.3f, modifier = Modifier.semantics { contentDescription = "Text size" },
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant, inactiveTickColor = MaterialTheme.colorScheme.outline))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Reduce animations", Modifier.weight(1f)); Switch(prefs.reduceMotion, { onPrefs(prefs.copy(reduceMotion = it)) }) }
        }
        AutoSettings(api, srv, prefs, onPrefs, onSrv, lastAct)
        SectionCard {
            Label("Updates")
            Text("This app: $appVersion" + when { release == null -> ""; updateReady -> " · version ${release.version} is ready"; else -> " · up to date" })
            if (relErr.isNotEmpty()) Text(relErr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (updateReady) Button(onUpdate, Modifier.heightIn(min = 48.dp), enabled = !updating, shape = RoundedCornerShape(12.dp)) { Text(if (updating) "Downloading…" else "Update now") }
                OutlinedButton(onCheck, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Check now") }
            }
            if (updateReady && !release?.notes.isNullOrBlank()) Muted(release!!.notes.take(500))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Check for updates automatically", Modifier.weight(1f)); Switch(prefs.autoUpdate, { onPrefs(prefs.copy(autoUpdate = it)) }) }
        }
        SectionCard {
            Label("Server"); Text(api.url, maxLines = 3, overflow = TextOverflow.Ellipsis); StatusPill(err)
            Text("Server version: ${serverVersion.ifEmpty { "…" }}" + if (serverNew) " · ${release!!.version} is ready" else if (serverVersion.isNotEmpty()) " · up to date" else "")
            if (serverNew) Button(onUpdateServer, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Update server") }
            Muted("Agents, memory and the Telegram bridge live on the server, so they keep working when this app is closed.")
            OutlinedButton(onLogout, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Disconnect") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorSheet(agent: Agent?, models: List<Pair<String, String>>, api: Api, onClose: () -> Unit, onSaved: (Long, Spec) -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = agent?.spec
    var name by remember { mutableStateOf(s?.name ?: "") }
    var model by remember { mutableStateOf(s?.model ?: models.firstOrNull { "sonnet-5" in it.first }?.first ?: "claude-sonnet-5-5") }
    var custom by remember { mutableStateOf(if (s != null && models.none { it.first == s.model }) s.model else "") }
    var prompt by remember { mutableStateOf(s?.prompt ?: "") }; var memory by remember { mutableStateOf(s?.memory ?: "") }
    var mcp by remember { mutableStateOf(s?.mcp ?: "") }; var subs by remember { mutableStateOf(s?.subagents ?: false) }
    var alive by remember { mutableStateOf(s?.autonomous ?: false) }
    var browser by remember { mutableStateOf(s?.browser ?: false) }
    var billing by remember { mutableStateOf(s?.billing ?: "") }
    var orders by remember { mutableStateOf(agent?.orders?.joinToString("\n") ?: "") }
    var autonomy by remember { mutableStateOf(s?.autonomy ?: "safe") }; var osTools by remember { mutableStateOf(s?.osTools ?: true) }
    var cap by remember { mutableStateOf(s?.dailyCap?.toString() ?: "") }; var workdir by remember { mutableStateOf(s?.workdir ?: "") }
    var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    fun spec() = Spec(name.trim(), custom.trim().ifEmpty { model }, prompt, memory, mcp, subs, autonomy, osTools, cap.toDoubleOrNull(), workdir, alive, s?.wakeMin ?: 5, s?.maxWakes ?: 24, browser, billing)
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (agent == null) "New agent" else "Edit agent", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Label("Model")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { (id, label) -> FilterChip(custom.isBlank() && model == id, { model = id; custom = "" }, { Text(label) }, Modifier.heightIn(min = 48.dp), shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)) }
            }
            OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("Or type any model id") }, placeholder = { Text("claude-opus-5-5") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, minLines = 3, shape = RoundedCornerShape(12.dp))
            Muted("Describe the agent's job here. To make it work by itself, for example every morning, save it and then press Repeat in its chat.")
            OutlinedTextField(orders, { orders = it }, Modifier.fillMaxWidth(), label = { Text("Rules it follows from now on (one per line)") }, placeholder = { Text("For example: Always answer in Dutch.") }, minLines = 2, shape = RoundedCornerShape(12.dp))
            Muted("You can also just tell the agent in chat, for example \"from now on ...\". It saves the rule here itself.")
            OutlinedTextField(memory, { memory = it }, Modifier.fillMaxWidth(), label = { Text("Memory") }, minLines = 2, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(mcp, { mcp = it }, Modifier.fillMaxWidth(), label = { Text("Tools (MCP file on the server)") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Lives on its own", fontWeight = FontWeight.SemiBold); Muted("Wakes up by itself, thinks, acts and decides when to wake next, all the time") }
                Switch(alive, { on -> alive = on; if (on && autonomy == "safe") autonomy = "standard" }) }
            if (!alive && timingHint(prompt)) Text("These instructions mention doing things over time. Turn on Lives on its own and the agent works out the schedule by itself.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            Label("Pay with"); Segmented(listOf("" to "Server default", "subscription" to "Subscription", "api" to "API key"), billing) { billing = it }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Can use a web browser", fontWeight = FontWeight.SemiBold); Muted("Opens pages, clicks, types, logs in, and keeps its own cookies") }
                Switch(browser, { browser = it }) }
            Label("What it may do on the server")
            Segmented(listOf("safe" to "Look only", "standard" to "Edit and browse", "full" to "Everything"), autonomy) { autonomy = it }
            Muted(when (autonomy) { "safe" -> "Reads files and answers. Nothing is changed."; "standard" -> "Can edit files in its folder and search the web."; else -> "Can run any command on the server without asking. Only for agents you trust." })
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("Agentic tools: message me, schedule tasks, remember, ask other agents", Modifier.weight(1f)); Switch(osTools, { osTools = it }) }
            OutlinedTextField(cap, { cap = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(), label = { Text("Daily limit for automatic runs ($, optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(workdir, { workdir = it }, Modifier.fillMaxWidth(), label = { Text("Working folder (optional)") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("Can start subagents", Modifier.weight(1f)); Switch(subs, { subs = it }) }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (agent != null) {
                    TextButton({ confirmDelete = true }, Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton({ scope.launch { try { val sp2 = spec().copy(name = name.trim() + " copy"); val id = withContext(Dispatchers.IO) { api.save(null, sp2) }; onSaved(id, sp2) } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Duplicate") }
                }
                OutlinedButton(onClose, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Cancel") }
                Button({
                    if (name.isBlank()) { err = "Give the agent a name."; return@Button }
                    busy = true
                    scope.launch { try { val sp2 = spec(); val id = withContext(Dispatchers.IO) { val i = api.save(agent?.id, sp2); if (orders != (agent?.orders?.joinToString("\n") ?: "")) api.putOrders(i, orders); i }; onSaved(id, sp2) } catch (e: ApiError) { err = "Couldn't save: ${e.message}. Try again."; busy = false } }
                }, Modifier.heightIn(min = 48.dp), enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text(if (busy) "Saving…" else "Save") }
            }
        }
    }
    if (confirmDelete && agent != null) AlertDialog({ confirmDelete = false }, title = { Text("Delete ${agent.spec.name}?") }, text = { Text("Its chat, memory and usage totals are removed from the server. This can't be undone.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { try { withContext(Dispatchers.IO) { api.delete(agent.id) }; onDeleted() } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }, Modifier.heightIn(min = 48.dp)) { Text("Keep it") } })
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)

private val actLastState = androidx.compose.runtime.mutableLongStateOf(0L)
