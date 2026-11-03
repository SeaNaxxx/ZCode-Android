package io.github.zcodeandroid.app.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.local.LocalServerService
import io.github.zcodeandroid.app.local.Provisioner
import io.github.zcodeandroid.app.local.RuntimeBus
import io.github.zcodeandroid.app.local.RuntimePaths
import io.github.zcodeandroid.app.local.RuntimeState
import io.github.zcodeandroid.app.protocol.ZcodeRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native ZCode shell (migration Phase 2 MVP, docs/architecture/MIGRATION-PLAN.md):
 *  - silent first-run: provision + server start happen automatically, with no
 *    Bionic/Node/Git/Bash wording and no "Set up local runtime" button;
 *  - native task list + conversation + composer over the pinned channel
 *    protocol (zcode-task), the web UI stays available as an explicit
 *    fallback from settings;
 *  - Z.ai sign-in via the official oauth channel device flow (external
 *    browser handoff, polling), with cached-session restore on start;
 *  - "This device" wording everywhere (owner requirement).
 *
 * HONEST STATUS: compiled and CI-built only. The device chat cycle (create
 * task, prompt, stream, permission, stop) is the Phase 2 exit criterion and
 * still needs verification on the reference device.
 */

// ------------------------------------------------------------- timeline data

private sealed interface TimelineItem {
    val key: String

    data class UserMsg(val id: String, val text: String) : TimelineItem {
        override val key get() = "u$id"
    }

    data class AssistantMsg(val id: String, var text: String) : TimelineItem {
        override val key get() = "a$id"
    }

    data class ThoughtMsg(val id: String, var text: String) : TimelineItem {
        override val key get() = "t$id"
    }

    data class ToolRow(
        val toolId: String,
        var name: String,
        var title: String,
        var status: String,
    ) : TimelineItem {
        override val key get() = "tool$toolId"
    }

    data class Notice(val id: String, val text: String, val isError: Boolean) : TimelineItem {
        override val key get() = "n$id"
    }

    data class PermissionCard(
        val requestId: String,
        val title: String,
        val description: String,
        val options: List<PermOption>,
    ) : TimelineItem {
        override val key get() = "perm$requestId"
    }
}

private data class PermOption(
    val optionId: String,
    val name: String,
    val decision: String,
)

private data class TaskRow(val taskId: String, val title: String, val updatedAt: Long)

// ------------------------------------------------------------------ the app

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeZcodeApp(
    themeMode: Int,
    onSetThemeMode: (Int) -> Unit,
    onOpenWebFallback: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val state by RuntimeBus.state.collectAsState()
    val scope = rememberCoroutineScope()

    // ------------------------------------------------- silent first-run flow
    LaunchedEffect(Unit) {
        if (Provisioner.isProvisioned(context) &&
            RuntimeBus.state.value is RuntimeState.NotProvisioned
        ) {
            RuntimeBus.setState(RuntimeState.Ready)
        }
    }

    LaunchedEffect(Unit) {
        // Silent auto-provision + auto-start: the owner-specified flow is
        // Open -> (permissions already requested by MainActivity) -> ZCode.
        val s = RuntimeBus.state.value
        if (s is RuntimeState.NotProvisioned) {
            Provisioner.provision(context)
        }
        val after = RuntimeBus.state.value
        if (after is RuntimeState.Ready || after is RuntimeState.Stopped) {
            LocalServerService.start(context)
        }
    }

    when (val s = state) {
        is RuntimeState.NotProvisioned, is RuntimeState.Provisioning, is RuntimeState.Starting ->
            PreparingScreen(s)

        is RuntimeState.Failed -> FailedScreen(s) {
            scope.launch { Provisioner.provision(context) }
        }

        is RuntimeState.Ready, is RuntimeState.Stopped -> PreparingScreen(s)

        is RuntimeState.Running -> ZcodeWorkspaceScreen(
            serverUrl = s.url,
            themeMode = themeMode,
            onSetThemeMode = onSetThemeMode,
            onOpenWebFallback = onOpenWebFallback,
            onOpenDiagnostics = onOpenDiagnostics,
        )
    }
}

// ------------------------------------------------------------ preparing UI

@Composable
private fun PreparingScreen(state: RuntimeState) {
    val percent = (state as? RuntimeState.Provisioning)?.percent
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ZCode", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Preparing ZCode on this device…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("$percent%", style = MaterialTheme.typography.labelLarge)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        // No runtime internals are shown here on purpose: provisioning is a
        // product step, not a technical lesson (owner first-run requirement).
    }
}

@Composable
private fun FailedScreen(state: RuntimeState.Failed, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ZCode could not start", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

// ------------------------------------------------------------- workspace UI

private class WorkspaceHolder(val context: android.content.Context, private val scope: CoroutineScope) {
    val rpc = ZcodeRpcClient("ws://127.0.0.1:3030/ws", scope)
    val api = ZcodeApi(rpc)
    val workspacePath: String = RuntimePaths.workspace(context).absolutePath

    var session by mutableStateOf<SessionState>(SessionState.Checking)
    val tasks = mutableStateListOf<TaskRow>()
    var selectedTaskId by mutableStateOf<String?>(null)
    val timeline = mutableStateListOf<TimelineItem>()
    var running by mutableStateOf(false)
    var listError by mutableStateOf<String?>(null)

    private var taskListener: ZcodeRpcClient.Listener? = null

    sealed interface SessionState {
        data object Checking : SessionState
        data object SignedOut : SessionState
        data object SignedIn : SessionState
        data object SignInProgress : SessionState
    }

    fun start() {
        rpc.connect()
    }

    fun stop() {
        taskListener?.dispose()
        taskListener = null
        rpc.close()
    }

    /** Restore cached session; falls through to SignedOut when absent. */
    suspend fun restoreSession() {
        session = SessionState.Checking
        val cached = runCatching { withContext(Dispatchers.IO) { api.oauthRestoreCachedSession() } }
            .getOrElse {
                RuntimeBus.append("oauth restore failed: ${it.message}")
                null
            }
        session = if (cached != null) SessionState.SignedIn else SessionState.SignedOut
        if (cached != null) refreshTasks()
    }

    suspend fun signIn(scope: CoroutineScope): Boolean {
        session = SessionState.SignInProgress
        return try {
            val providers = withContext(Dispatchers.IO) { api.oauthProviders() }
            val preferred = providers.firstOrNull { p ->
                val id = (p["id"] as? String) ?: ""
                id.contains("zai", ignoreCase = true) || id.contains("zhipu", ignoreCase = true)
            } ?: providers.firstOrNull()
            val providerId = (preferred?.get("id") as? String)
            if (providerId == null) {
                RuntimeBus.append("oauth: no providers returned")
                session = SessionState.SignedOut
                return false
            }
            val start = withContext(Dispatchers.IO) { api.oauthStart(providerId) }
            val url = start["authorizeUrl"] as? String
            if (url == null) {
                RuntimeBus.append("oauth: start response missing authorizeUrl: $start")
                session = SessionState.SignedOut
                return false
            }
            openBrowser(url)
            // Device flow: poll the local server until the browser handoff completes.
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                val result = runCatching { withContext(Dispatchers.IO) { api.oauthPoll() } }.getOrNull()
                if (result != null) {
                    session = SessionState.SignedIn
                    refreshTasks()
                    return true
                }
            }
            session = SessionState.SignedOut
            false
        } catch (e: Exception) {
            RuntimeBus.append("sign-in failed: ${e.message}")
            session = SessionState.SignedOut
            false
        }
    }

    private fun openBrowser(url: String) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { RuntimeBus.append("browser open failed: ${it.message}") }
    }

    suspend fun refreshTasks() {
        try {
            val metas = withContext(Dispatchers.IO) { api.listTasks(workspacePath) }
            listError = null
            tasks.clear()
            for (m in metas) {
                val id = (m["taskId"] as? String) ?: continue
                tasks.add(
                    TaskRow(
                        taskId = id,
                        title = (m["title"] as? String).orEmpty().ifEmpty { "(untitled)" },
                        updatedAt = ((m["updatedAt"] as? Number)?.toLong()) ?: 0L,
                    )
                )
            }
            tasks.sortByDescending { it.updatedAt }
            if (selectedTaskId == null) {
                selectedTaskId = tasks.firstOrNull()?.taskId
                selectedTaskId?.let { openTask(it) }
            }
        } catch (e: Exception) {
            listError = e.message
            RuntimeBus.append("listTasks failed: ${e.message}")
        }
    }

    suspend fun openTask(taskId: String) {
        taskListener?.dispose()
        timeline.clear()
        selectedTaskId = taskId
        running = false
        // History first (honest restore), then live events.
        runCatching {
            val snap = withContext(Dispatchers.IO) { api.getTaskSnapshot(workspacePath, taskId) }
            renderSnapshot(snap)
        }.onFailure { RuntimeBus.append("snapshot failed: ${it.message}") }
        taskListener = api.taskEvents(workspacePath, taskId)
        val listener = taskListener!!
        scope.launch {
            listener.flow.collectLatest { ev -> handleStreamEvent(ev) }
        }
        // Pending permissions survive in the runtime snapshot.
    }

    private fun renderSnapshot(snap: Map<String, Any?>) {
        val messages = snap["messages"] as? List<*> ?: emptyList<Any?>()
        for (m in messages) {
            val msg = m as? Map<*, *> ?: continue
            val role = msg["role"] as? String ?: continue
            val content = msg["content"] as? String ?: ""
            val id = (msg["id"] as? String) ?: "s${timeline.size}"
            if (role == "user") {
                timeline.add(TimelineItem.UserMsg(id, content))
            } else {
                if (content.isNotBlank()) timeline.add(TimelineItem.AssistantMsg(id, content))
                val tools = msg["tools"] as? List<*> ?: emptyList<Any?>()
                for (t in tools) {
                    val tool = t as? Map<*, *> ?: continue
                    timeline.add(
                        TimelineItem.ToolRow(
                            toolId = (tool["toolId"] as? String) ?: "st${timeline.size}",
                            name = (tool["toolName"] as? String) ?: (tool["kind"] as? String) ?: "tool",
                            title = (tool["title"] as? String) ?: "",
                            status = (tool["status"] as? String) ?: "completed",
                        )
                    )
                }
            }
        }
        val runtime = snap["runtime"] as? Map<*, *>
        val pendingPerms = (runtime?.get("pendingPermissions") as? List<*>) ?: emptyList<Any?>()
        for (p in pendingPerms) addPermissionCard(p as? Map<*, *> ?: continue)
    }

    private fun addPermissionCard(p: Map<*, *>) {
        val requestId = p["requestId"] as? String ?: return
        if (timeline.any { it is TimelineItem.PermissionCard && it.requestId == requestId }) return
        val options = (p["options"] as? List<*>).orEmpty().mapNotNull { o ->
            val om = o as? Map<*, *> ?: return@mapNotNull null
            val oid = om["optionId"] as? String ?: return@mapNotNull null
            val response = om["response"] as? Map<*, *>
            PermOption(
                optionId = oid,
                name = (om["name"] as? String) ?: oid,
                decision = (response?.get("decision") as? String) ?: "allow",
            )
        }
        timeline.add(
            TimelineItem.PermissionCard(
                requestId = requestId,
                title = (p["title"] as? String) ?: (p["kind"] as? String) ?: "Permission required",
                description = (p["description"] as? String) ?: "",
                options = options,
            )
        )
    }

    private fun handleStreamEvent(ev: Any?) {
        val map = ev as? Map<*, *> ?: return
        val type = map["type"] as? String ?: return
        when (type) {
            "agent_message_chunk" -> {
                val content = map["content"] as? String ?: return
                if (map["parentToolUseId"] != null) return // nested agent output: MVP keeps main thread
                val last = timeline.lastOrNull()
                if (last is TimelineItem.AssistantMsg) {
                    // Structural replacement (not in-place mutation) so the
                    // snapshot-aware list actually recomposes the row.
                    timeline[timeline.size - 1] = TimelineItem.AssistantMsg(last.id, last.text + content)
                } else {
                    timeline.add(TimelineItem.AssistantMsg("live${timeline.size}", content))
                }
                running = true
            }
            "agent_thought_chunk" -> {
                val content = map["content"] as? String ?: return
                val last = timeline.lastOrNull()
                if (last is TimelineItem.ThoughtMsg) {
                    timeline[timeline.size - 1] = TimelineItem.ThoughtMsg(last.id, last.text + content)
                } else {
                    timeline.add(TimelineItem.ThoughtMsg("th${timeline.size}", content))
                }
            }
            "tool_call" -> {
                val toolId = map["toolId"] as? String ?: return
                timeline.removeAll { it is TimelineItem.ToolRow && it.toolId == toolId }
                timeline.add(
                    TimelineItem.ToolRow(
                        toolId = toolId,
                        name = (map["toolName"] as? String) ?: (map["kind"] as? String) ?: "tool",
                        title = (map["title"] as? String) ?: "",
                        status = "in_progress",
                    )
                )
            }
            "tool_call_update" -> {
                val toolId = map["toolId"] as? String ?: return
                val idx = timeline.indexOfFirst { it is TimelineItem.ToolRow && it.toolId == toolId }
                if (idx >= 0) {
                    val row = timeline[idx] as TimelineItem.ToolRow
                    timeline[idx] = row.copy(
                        status = (map["status"] as? String) ?: row.status,
                        title = (map["title"] as? String) ?: row.title,
                        name = (map["toolName"] as? String) ?: row.name,
                    )
                } else {
                    timeline.add(
                        TimelineItem.ToolRow(
                            toolId = toolId,
                            name = (map["toolName"] as? String) ?: "tool",
                            title = (map["title"] as? String) ?: "",
                            status = (map["status"] as? String) ?: "completed",
                        )
                    )
                }
            }
            "permission_request" -> addPermissionCard(map)
            "permission_response" -> {
                val requestId = map["requestId"] as? String ?: return
                timeline.removeAll { it is TimelineItem.PermissionCard && it.requestId == requestId }
            }
            "task_run_started" -> running = true
            "task_complete" -> {
                running = false
                val reason = map["stopReason"] as? String ?: ""
                timeline.add(TimelineItem.Notice("done${timeline.size}", "Completed ($reason)", false))
            }
            "task_error" -> {
                running = false
                timeline.add(
                    TimelineItem.Notice(
                        "err${timeline.size}",
                        (map["error"] as? String) ?: "task error",
                        true,
                    )
                )
            }
            "task_warning" -> timeline.add(
                TimelineItem.Notice("warn${timeline.size}", (map["warning"] as? String) ?: "", false)
            )
            else -> {
                // Unknown event shapes are logged for evidence, never rendered
                // as if understood (no fabricated content).
                RuntimeBus.append("stream event (unrendered): $type")
            }
        }
    }

    suspend fun send(text: String) {
        var taskId = selectedTaskId
        if (taskId == null) {
            val created = withContext(Dispatchers.IO) { api.createTask(workspacePath) }
            taskId = created["taskId"] as? String
            if (taskId == null) {
                timeline.add(TimelineItem.Notice("cf${timeline.size}", "Task creation failed", true))
                return
            }
            selectedTaskId = taskId
            timeline.clear()
        }
        timeline.add(TimelineItem.UserMsg("u${timeline.size}", text))
        running = true
        runCatching {
            withContext(Dispatchers.IO) { api.sendPrompt(workspacePath, taskId, text) }
        }.onFailure {
            running = false
            timeline.add(TimelineItem.Notice("sf${timeline.size}", "send failed: ${it.message}", true))
        }
        if (!tasks.any { it.taskId == taskId }) refreshTasks()
    }

    suspend fun respondPermission(requestId: String, optionId: String, decision: String) {
        val taskId = selectedTaskId ?: return
        timeline.removeAll { it is TimelineItem.PermissionCard && it.requestId == requestId }
        runCatching {
            withContext(Dispatchers.IO) {
                api.respondPermission(workspacePath, taskId, requestId, optionId, decision)
            }
        }.onFailure { RuntimeBus.append("respondPermission failed: ${it.message}") }
    }

    suspend fun stop() {
        val taskId = selectedTaskId ?: return
        runCatching {
            withContext(Dispatchers.IO) { api.stopGeneration(workspacePath, taskId) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZcodeWorkspaceScreen(
    serverUrl: String,
    themeMode: Int,
    onSetThemeMode: (Int) -> Unit,
    onOpenWebFallback: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { WorkspaceHolder(context, scope) }
    val conn by holder.rpc.conn.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var composer by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        holder.start()
        onDispose { holder.stop() }
    }

    LaunchedEffect(conn) {
        if (conn == ZcodeRpcClient.Conn.READY) holder.restoreSession()
    }

    LaunchedEffect(holder.timeline.size) {
        if (holder.timeline.isNotEmpty()) listState.animateScrollToItem(holder.timeline.size - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "ZCode — This device",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                TextButton(onClick = {
                    scope.launch { drawer.close() }
                    scope.launch {
                        holder.selectedTaskId = null
                        holder.timeline.clear()
                    }
                }) { Text("+ New task") }
                Text(
                    "Tasks",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (holder.tasks.isEmpty()) {
                    Text(
                        holder.listError ?: "No tasks yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn {
                    items(holder.tasks, key = { it.taskId }) { task ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        drawer.close()
                                        holder.openTask(task.taskId)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                if (task.taskId == holder.selectedTaskId) "open" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text("ZCode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This device · ${conn.name.lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
            when (val session = holder.session) {
                is WorkspaceHolder.SessionState.Checking -> CenteredSpinner("Checking sign-in…")
                is WorkspaceHolder.SessionState.SignedOut, is WorkspaceHolder.SessionState.SignInProgress ->
                    SignInScreen(
                        busy = session is WorkspaceHolder.SessionState.SignInProgress,
                        onSignIn = { scope.launch { holder.signIn(scope) } },
                    )
                is WorkspaceHolder.SessionState.SignedIn -> {
                    if (showSettings) {
                        SettingsPanel(
                            themeMode = themeMode,
                            onSetThemeMode = onSetThemeMode,
                            onClose = { showSettings = false },
                            onOpenWebFallback = {
                                showSettings = false
                                onOpenWebFallback()
                            },
                            onOpenDiagnostics = {
                                showSettings = false
                                onOpenDiagnostics()
                            },
                            onBackup = { uri -> scope.launch { io.github.zcodeandroid.app.backup.ZcodeBackup.export(context, uri) } },
                            onRestore = { uri -> scope.launch { io.github.zcodeandroid.app.backup.ZcodeBackup.restore(context, uri) } },
                            onSignOut = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { holder.api.oauthLogout() }
                                    holder.session = WorkspaceHolder.SessionState.SignedOut
                                }
                            },
                            onStopRuntime = { LocalServerService.stop(context) },
                        )
                    } else {
                        ConversationPanel(
                            holder = holder,
                            listState = listState,
                            composer = composer,
                            onComposerChange = { composer = it },
                            onSend = {
                                val text = composer.trim()
                                if (text.isNotEmpty()) {
                                    composer = ""
                                    scope.launch { holder.send(text) }
                                }
                            },
                            onStop = { scope.launch { holder.stop() } },
                            onRespond = { req, opt, dec ->
                                scope.launch { holder.respondPermission(req, opt, dec) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredSpinner(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignInScreen(busy: Boolean, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sign in to Z.ai", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your sessions stay on this device. Signing in links the local runtime to your Z.ai account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onSignIn) { Text("Sign in with Z.ai") }
        }
    }
}

@Composable
private fun ConversationPanel(
    holder: WorkspaceHolder,
    listState: androidx.compose.foundation.lazy.LazyListState,
    composer: String,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRespond: (String, String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(holder.timeline) { item ->
                TimelineRow(item, onRespond)
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(modifier = Modifier.fillMaxWidth().imePadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = composer,
                        onValueChange = onComposerChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask ZCode…") },
                        maxLines = 5,
                    )
                    if (holder.running) {
                        Button(onClick = onStop, modifier = Modifier.padding(bottom = 4.dp)) {
                            Text("Stop")
                        }
                    } else {
                        Button(onClick = onSend, modifier = Modifier.padding(bottom = 4.dp)) {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, onRespond: (String, String, String) -> Unit) {
    when (item) {
        is TimelineItem.UserMsg -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp),
            ) {
                Text(item.text, modifier = Modifier.padding(10.dp).widthIn(max = 320.dp))
            }
        }
        is TimelineItem.AssistantMsg -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
            ) {
                Text(item.text, modifier = Modifier.padding(10.dp).widthIn(max = 340.dp))
            }
        }
        is TimelineItem.ThoughtMsg -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is TimelineItem.ToolRow -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when (item.status) {
                            "completed" -> Color(0xFF22C55E)
                            "failed", "denied" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        RoundedCornerShape(4.dp),
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                buildString {
                    append(item.name)
                    if (item.title.isNotBlank()) append(" — ${item.title}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (item.status == "in_progress") {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
            }
        }
        is TimelineItem.Notice -> Text(
            item.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is TimelineItem.PermissionCard -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                for (option in item.options) {
                    TextButton(onClick = { onRespond(item.requestId, option.optionId, option.decision) }) {
                        Text(option.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    themeMode: Int,
    onSetThemeMode: (Int) -> Unit,
    onClose: () -> Unit,
    onOpenWebFallback: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBackup: (android.net.Uri) -> Unit,
    onRestore: (android.net.Uri) -> Unit,
    onSignOut: () -> Unit,
    onStopRuntime: () -> Unit,
) {
    val ctx = LocalContext.current
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(onBackup) }
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onRestore) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("App theme", style = MaterialTheme.typography.titleSmall)
                listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = themeMode == value, onClick = { onSetThemeMode(value) })
                        Text(label)
                    }
                }
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sessions & data", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Export saves chats, memory and settings (never credentials). Restore them after reinstalling the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val name = "zcode-backup-${System.currentTimeMillis()}.zip"
                        exportPicker.launch(name)
                    }) { Text("Export backup") }
                    OutlinedButton(onClick = {
                        importPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("Restore") }
                }
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Advanced", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onOpenWebFallback) { Text("Open web UI (fallback)") }
                TextButton(onClick = onOpenDiagnostics) { Text("Runtime log & diagnostics") }
                TextButton(onClick = onSignOut) { Text("Sign out") }
                TextButton(onClick = onStopRuntime) { Text("Stop runtime") }
                Text(
                    "Workspace: ${RuntimePaths.workspace(ctx).absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
