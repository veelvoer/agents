package dev.voer.agents

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgentsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() } } }
    }
}

private fun fmtN(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1000 -> "%.1fk".format(n / 1e3); else -> n.toString() }

@Composable
fun Root() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("conn", Context.MODE_PRIVATE) }
    var conn by remember { mutableStateOf(prefs.getString("url", null)?.let { u -> prefs.getString("key", null)?.let { k -> Api(u, k) } }) }
    AnimatedContent(conn, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "root") { c ->
        if (c == null) ConnectScreen { api -> prefs.edit().putString("url", api.url).putString("key", api.key).apply(); conn = api }
        else MainScreen(c) { prefs.edit().clear().apply(); conn = null }
    }
}

@Composable
fun ConnectScreen(onDone: (Api) -> Unit) {
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 440.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Agents", style = MaterialTheme.typography.headlineMedium)
                Text("Connect to your agent server. Your agents run there, not on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(addr, { addr = it }, Modifier.fillMaxWidth(), label = { Text("Server address") }, placeholder = { Text("https://1-2-3-4.sslip.io") }, singleLine = true, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                Text("Tip: paste the whole pairing link from the installer (address#key) into the first box.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { contentDescription = "Error: $err" })
                Button({
                    val (u, k0) = parsePair(addr); val k = k0.ifEmpty { key.trim() }
                    if (u.isEmpty() || k.isEmpty()) { err = "Fill in both the address and the key."; return@Button }
                    busy = true; err = ""
                    scope.launch {
                        val api = Api(u, k)
                        try { withContext(Dispatchers.IO) { api.agents() }; onDone(api) } catch (e: ApiError) { err = "Couldn't connect: ${e.message}"; busy = false }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text(if (busy) "Connecting…" else "Connect") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(api: Api, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var agents by remember { mutableStateOf<List<Agent>?>(null) }
    var models by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Pair<Agent?, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() { try { agents = withContext(Dispatchers.IO) { api.agents() }; err = "" } catch (e: ApiError) { err = e.message ?: "offline" } }
    LaunchedEffect(Unit) { try { models = withContext(Dispatchers.IO) { api.models() } } catch (_: ApiError) {}; while (true) { refresh(); delay(3000) } }
    val current = agents?.firstOrNull { it.id == open }
    BackHandler(open != null) { open = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (current == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            listOf(Triple("Agents", L.Bot, 0), Triple("Usage", L.Chart, 1), Triple("Settings", L.Sliders, 2)).forEach { (label, icon, i) ->
                NavigationBarItem(tab == i, { tab = i }, { Icon(icon, null) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f), unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            AnimatedContent(current?.id ?: -1L, transitionSpec = { (slideInHorizontally(tween(240)) { it / 8 } + fadeIn(tween(200))) togetherWith fadeOut(tween(120)) }, label = "page") { id ->
                if (id != -1L && current != null) ChatScreen(api, current, { open = null }, { editing = current to true }, { scope.launch { refresh() } })
                else when (tab) {
                    0 -> AgentsTab(agents, err, { open = it }, { editing = null to true })
                    1 -> UsageTab(agents ?: emptyList(), err, models)
                    else -> SettingsTab(api, err, onLogout)
                }
            }
        }
    }
    if (editing != null) {
        val a = editing!!.first
        EditorSheet(a, models, api, onClose = { editing = null }, onSaved = { id -> scope.launch { refresh(); if (a == null) open = id }; editing = null }, onDeleted = { scope.launch { refresh() }; open = null; editing = null })
    }
}

@Composable
fun StatusPill(err: String) {
    val ok = err.isEmpty()
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Ok else MaterialTheme.colorScheme.primary))
            Text(if (ok) "Connected" else "Offline", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AgentsTab(agents: List<Agent>?, err: String, onOpen: (Long) -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Agents", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Button(onNew, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Icon(L.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New") }
        }
        if (err.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusPill(err); Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            agents == null && err.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            agents.isNullOrEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("No agents yet. Press New to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(agents, key = { it.id }) { a ->
                    Card({ onOpen(a.id) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val c by animateColorAsState(if (a.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "dot")
                                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                Text(a.spec.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (a.running) Text("working", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("${a.spec.model.modelName()} · ${fmtN(a.usage.tokens)} tokens · $${"%.3f".format(a.usage.cost)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun String.modelName(): String = Regex("claude-(.*?)(?:-\\d{8})?$").find(this)?.groupValues?.get(1)?.split('-')?.joinToString(" ") { it.replaceFirstChar(Char::uppercase) } ?: this

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
fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) =
    IconButton(onClick, Modifier.size(48.dp)) { Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp)) }

@Composable
fun ChatScreen(api: Api, agent: Agent, onBack: () -> Unit, onEdit: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    suspend fun load() { try { msgs = withContext(Dispatchers.IO) { api.messages(agent.id) } } catch (e: ApiError) { note = e.message ?: "" } }
    LaunchedEffect(agent.id) { load() }
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty() || busy) list.animateScrollToItem(msgs.size + if (busy) 1 else 0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(L.Back, "Back to agents", onBack)
            Column(Modifier.weight(1f)) {
                Text(agent.spec.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${agent.spec.model.modelName()} · ${fmtN(agent.usage.tokens)} tokens · $${"%.3f".format(agent.usage.cost)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(L.Edit, "Edit agent", onEdit); IconBtn(L.Trash, "Clear chat", { confirmClear = true })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (msgs.isEmpty() && !busy) item { Text("Say something to ${agent.spec.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(32.dp)) }
            items(msgs.size) { i ->
                val m = msgs[i]
                if (m.role == "user") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.widthIn(max = 300.dp)) { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                } else Text(if (m.role == "error") AnnotatedString(m.text) else md(m.text), color = if (m.role == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary); Text("working", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Surface(Modifier.padding(12.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message ${agent.spec.name}") }, maxLines = 5,
                    colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                FilledIconButton({
                    val t = text.trim(); if (t.isEmpty() || busy) return@FilledIconButton
                    text = ""; note = ""; busy = true; msgs = msgs + Msg("user", t)
                    scope.launch {
                        try { withContext(Dispatchers.IO) { api.run(agent.id, t) } } catch (e: ApiError) { note = e.message ?: "" }
                        busy = false; load(); onChanged()
                    }
                }, Modifier.size(48.dp), enabled = !busy, shape = RoundedCornerShape(14.dp)) { Icon(L.Send, "Send", Modifier.size(20.dp)) }
            }
        }
    }
    if (confirmClear) AlertDialog({ confirmClear = false }, title = { Text("Clear the chat with ${agent.spec.name}?") },
        text = { Text("The messages and the conversation memory of this chat are removed. The agent and its usage stay.") },
        confirmButton = { TextButton({ confirmClear = false; scope.launch { try { withContext(Dispatchers.IO) { api.clear(agent.id) }; msgs = emptyList() } catch (e: ApiError) { note = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }, Modifier.heightIn(min = 48.dp)) { Text("Keep") } })
}

@Composable
fun UsageTab(agents: List<Agent>, err: String, models: List<Pair<String, String>>) {
    val cost = agents.sumOf { it.usage.cost }; val tok = agents.sumOf { it.usage.tokens }; val cache = agents.sumOf { it.usage.cache }
    val max = agents.maxOfOrNull { it.usage.cost }?.coerceAtLeast(1e-6) ?: 1e-6
    @Composable fun Card2(content: @Composable ColumnScope.() -> Unit) = Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(18.dp), content = content) }
    @Composable fun Label(t: String) = Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("Usage", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f)); StatusPill(err) }
        Card2 { Label("Estimated cost"); Text("$${"%.2f".format(cost)}", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 52.sp, lineHeight = 58.sp)); Text("Counted from your Claude subscription runs. It shows how much you used, not a bill.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { Card2 { Label("Working"); Text("${agents.count { it.running }}", style = MaterialTheme.typography.headlineLarge); Text("of ${agents.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            Box(Modifier.weight(1f)) { Card2 { Label("Tokens"); Text(fmtN(tok), style = MaterialTheme.typography.headlineLarge); Text("in and out", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        Card2 { Label("Read from cache"); Text(fmtN(cache), style = MaterialTheme.typography.headlineLarge); Text("tokens reused cheaply", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Card2 {
            Label("By agent")
            if (agents.isEmpty()) Text("Nothing yet. Usage appears after an agent answers.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            agents.sortedByDescending { it.usage.cost }.forEach { a ->
                Row(Modifier.padding(top = 14.dp)) { Text(a.spec.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("$${"%.3f".format(a.usage.cost)}", style = MaterialTheme.typography.bodyMedium) }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) { Box(Modifier.fillMaxWidth((a.usage.cost / max).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(modelColor(a.spec.model))) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SettingsTab(api: Api, err: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 12.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SERVER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(api.url, maxLines = 2, overflow = TextOverflow.Ellipsis); StatusPill(err)
                Text("Agents, memory and the Telegram bridge live on the server, so they keep working when this app is closed.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onLogout, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Disconnect") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorSheet(agent: Agent?, models: List<Pair<String, String>>, api: Api, onClose: () -> Unit, onSaved: (Long) -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = agent?.spec
    var name by remember { mutableStateOf(s?.name ?: "") }
    var model by remember { mutableStateOf(s?.model ?: models.firstOrNull { "sonnet-5" in it.first }?.first ?: "claude-sonnet-5-5") }
    var prompt by remember { mutableStateOf(s?.prompt ?: "") }; var memory by remember { mutableStateOf(s?.memory ?: "") }
    var mcp by remember { mutableStateOf(s?.mcp ?: "") }; var subs by remember { mutableStateOf(s?.subagents ?: false) }
    var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (agent == null) "New agent" else "Edit agent", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Text("MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { (id, label) -> FilterChip(model == id, { model = id }, { Text(label) }, Modifier.heightIn(min = 48.dp), shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)) }
            }
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, minLines = 3, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(memory, { memory = it }, Modifier.fillMaxWidth(), label = { Text("Memory") }, minLines = 2, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(mcp, { mcp = it }, Modifier.fillMaxWidth(), label = { Text("Tools (MCP file on the server)") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("Can start subagents", Modifier.weight(1f)); Switch(subs, { subs = it }) }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (agent != null) TextButton({ confirmDelete = true }, Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClose, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Cancel") }
                Button({
                    if (name.isBlank()) { err = "Give the agent a name."; return@Button }
                    busy = true
                    scope.launch { try { val id = withContext(Dispatchers.IO) { api.save(agent?.id, Spec(name.trim(), model, prompt, memory, mcp, subs)) }; onSaved(id) } catch (e: ApiError) { err = "Couldn't save: ${e.message}. Try again."; busy = false } }
                }, Modifier.heightIn(min = 48.dp), enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text(if (busy) "Saving…" else "Save") }
            }
        }
    }
    if (confirmDelete && agent != null) AlertDialog({ confirmDelete = false }, title = { Text("Delete ${agent.spec.name}?") }, text = { Text("Its chat memory and usage totals are removed from the server. This can't be undone.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { try { withContext(Dispatchers.IO) { api.delete(agent.id) }; onDeleted() } catch (e: ApiError) { err = e.message ?: "" } } }, Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }, Modifier.heightIn(min = 48.dp)) { Text("Keep it") } })
}
