package dev.voer.agents

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            var prefs by remember { mutableStateOf(Prefs.load(ctx)) }
            AgentsTheme(prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(prefs) { prefs = it; it.save(ctx) } }
            }
        }
    }
}

@Composable
fun Root(prefs: Prefs, onPrefs: (Prefs) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { ctx.getSharedPreferences("conn", Context.MODE_PRIVATE) }
    var conn by remember { mutableStateOf(store.getString("url", null)?.let { u -> store.getString("key", null)?.let { k -> Api(u, k) } }) }
    val motion = LocalMotion.current
    AnimatedContent(conn, transitionSpec = { (if (motion) fadeIn(tween(200)) else fadeIn(snap())) togetherWith (if (motion) fadeOut(tween(120)) else fadeOut(snap())) }, label = "root") { c ->
        if (c == null) ConnectScreen { api -> store.edit().putString("url", api.url).putString("key", api.key).apply(); conn = api }
        else MainScreen(c, prefs, onPrefs) { store.edit().clear().apply(); conn = null }
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
                Text("Tip: paste the whole pairing line from the installer (address#key) into the first box.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
