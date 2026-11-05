package io.github.zcodeandroid.app.ui.conversation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zcodeandroid.app.local.RuntimeBus
import io.github.zcodeandroid.app.local.RuntimePaths
import io.github.zcodeandroid.app.native.ZcodeApi
import io.github.zcodeandroid.app.protocol.ZcodeRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2 conversation state (REVISED architecture §5 Phase 2; §7 ownership):
 * the runtime owns conversation/agent state — this VM only projects it.
 * Every timeline row is derived from the verified runtime contract:
 * getTaskSnapshot for history (honest restore), onDynamicTaskEvent for
 * streaming. Event type -> row mapping is the shape the 0.5.0 native
 * shell rendered from the same pinned upstream (3c5b227); anything
 * unrecognized is logged verbatim and NEVER rendered as if understood.
 *
 * Timeline rows are immutable; updates are structural replacements so
 * Compose recomposes deterministically (0.5.0 lesson).
 */
class ConversationViewModel(
    private val workspacePath: String,
) : ViewModel() {

    enum class Conn { CONNECTING, READY, CLOSED }

    sealed interface TimelineItem {
        data class UserMsg(val id: String, val text: String) : TimelineItem
        data class AssistantMsg(val id: String, val text: String) : TimelineItem
        data class ThoughtMsg(val id: String, val text: String) : TimelineItem
        data class ToolRow(
            val toolId: String,
            val name: String,
            val title: String,
            val status: String,
        ) : TimelineItem

        data class PermissionCard(
            val requestId: String,
            val title: String,
            val description: String,
            val options: List<PermOption>,
        ) : TimelineItem

        data class Notice(val id: String, val text: String, val isError: Boolean) : TimelineItem
    }

    data class PermOption(val optionId: String, val name: String, val decision: String)
    data class TaskRow(val taskId: String, val title: String, val updatedAt: Long)

    data class UiState(
        val tasks: List<TaskRow> = emptyList(),
        val selectedTaskId: String? = null,
        val timeline: List<TimelineItem> = emptyList(),
        val running: Boolean = false,
        val conn: Conn = Conn.CONNECTING,
        val sendError: String? = null,
        val listError: String? = null,
    ) {
        val hasSelectedTask: Boolean get() = selectedTaskId != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var rpc: ZcodeRpcClient? = null
    private var api: ZcodeApi? = null
    private var taskListener: ZcodeRpcClient.Listener? = null
    private var collectorJob: Job? = null

    /** Restore path after process death: the screen saves this id. */
    var restoreTaskId: String? = null

    fun start() {
        if (rpc != null) return
        val client = ZcodeRpcClient("ws://127.0.0.1:3030/ws", viewModelScope)
        rpc = client
        api = ZcodeApi(client)
        viewModelScope.launch {
            client.conn.collect { c ->
                _state.update {
                    it.copy(
                        conn = when (c) {
                            ZcodeRpcClient.Conn.READY -> Conn.READY
                            ZcodeRpcClient.Conn.CONNECTING -> Conn.CONNECTING
                            else -> Conn.CLOSED
                        },
                    )
                }
            }
        }
        client.connect()
        refreshTasks(openFirst = restoreTaskId == null)
        restoreTaskId?.let { openTask(it) }
    }

    fun refreshTasks(openFirst: Boolean = false) {
        val api = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.listTasks(workspacePath) }
                .onSuccess { metas ->
                    val rows = metas.mapNotNull { m ->
                        val id = m["taskId"] as? String ?: return@mapNotNull null
                        TaskRow(
                            taskId = id,
                            title = (m["title"] as? String).orEmpty().ifEmpty { "(untitled)" },
                            updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
                        )
                    }.sortedByDescending { it.updatedAt }
                    _state.update { it.copy(tasks = rows, listError = null) }
                    if (openFirst && _state.value.selectedTaskId == null) {
                        rows.firstOrNull()?.let { openTask(it.taskId) }
                    }
                }
                .onFailure { e ->
                    RuntimeBus.append("listTasks failed: ${e.message}")
                    _state.update { it.copy(listError = e.message ?: "task list unavailable") }
                }
        }
    }

    fun openTask(taskId: String) {
        val api = api ?: return
        taskListener?.dispose()
        collectorJob?.cancel()
        _state.update { it.copy(selectedTaskId = taskId, timeline = emptyList(), running = false, sendError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // History first (honest restore), then live events.
            runCatching { api.getTaskSnapshot(workspacePath, taskId) }
                .onSuccess { snap -> renderSnapshot(taskId, snap) }
                .onFailure { e -> RuntimeBus.append("snapshot failed: ${e.message}") }
            val listener = api.taskEvents(workspacePath, taskId)
            taskListener = listener
            collectorJob = viewModelScope.launch(Dispatchers.IO) {
                listener.flow.collect { ev -> handleStreamEvent(ev) }
            }
        }
    }

    private fun renderSnapshot(taskId: String, snap: Map<String, Any?>) {
        if (_state.value.selectedTaskId != taskId) return
        val rows = ArrayList<TimelineItem>()
        val messages = snap["messages"] as? List<*> ?: emptyList<Any?>()
        for (m in messages) {
            val msg = m as? Map<*, *> ?: continue
            val role = msg["role"] as? String ?: continue
            val content = msg["content"] as? String ?: ""
            val id = (msg["id"] as? String) ?: "s${rows.size}"
            if (role == "user") {
                rows.add(TimelineItem.UserMsg(id, content))
            } else {
                if (content.isNotBlank()) rows.add(TimelineItem.AssistantMsg(id, content))
                val tools = msg["tools"] as? List<*> ?: emptyList<Any?>()
                for (t in tools) {
                    val tool = t as? Map<*, *> ?: continue
                    rows.add(
                        TimelineItem.ToolRow(
                            toolId = (tool["toolId"] as? String) ?: "st${rows.size}",
                            name = (tool["toolName"] as? String) ?: (tool["kind"] as? String) ?: "tool",
                            title = (tool["title"] as? String) ?: "",
                            status = (tool["status"] as? String) ?: "completed",
                        ),
                    )
                }
            }
        }
        val runtime = snap["runtime"] as? Map<*, *>
        val pending = (runtime?.get("pendingPermissions") as? List<*>).orEmpty()
        for (p in pending) (p as? Map<*, *>)?.let { addPermissionCard(rows, it) }
        _state.update {
            if (it.selectedTaskId == taskId) it.copy(timeline = rows) else it
        }
    }

    private fun addPermissionCard(rows: MutableList<TimelineItem>, p: Map<*, *>) {
        val requestId = p["requestId"] as? String ?: return
        val options = (p["options"] as? List<*>).orEmpty().mapNotNull { o ->
            val om = o as? Map<*, *> ?: return@mapNotNull null
            val oid = om["optionId"] as? String ?: return@mapNotNull null
            val response = om["response"] as? Map<*, *>
            PermOption(
                optionId = oid,
                name = (om["name"] as? String) ?: oid,
                // Each option carries its own decision kind (verified shape).
                decision = (response?.get("decision") as? String) ?: "allow",
            )
        }
        val card = TimelineItem.PermissionCard(
            requestId = requestId,
            title = (p["title"] as? String) ?: (p["kind"] as? String) ?: "Permission required",
            description = (p["description"] as? String) ?: "",
            options = options,
        )
        rows.removeAll { it is TimelineItem.PermissionCard && it.requestId == requestId }
        rows.add(card)
    }

    private fun replaceTimeline(block: (MutableList<TimelineItem>) -> Unit) {
        _state.update { s ->
            val rows = s.timeline.toMutableList()
            block(rows)
            s.copy(timeline = rows)
        }
    }

    private fun handleStreamEvent(ev: Any?) {
        val map = ev as? Map<*, *> ?: return
        when (map["type"] as? String ?: return) {
            "agent_message_chunk" -> {
                val content = map["content"] as? String ?: return
                if (map["parentToolUseId"] != null) return // nested agent output: main thread only
                replaceTimeline { rows ->
                    val last = rows.lastOrNull()
                    if (last is TimelineItem.AssistantMsg) {
                        rows[rows.size - 1] = last.copy(text = last.text + content)
                    } else {
                        rows.add(TimelineItem.AssistantMsg("live${rows.size}", content))
                    }
                }
                _state.update { it.copy(running = true) }
            }
            "agent_thought_chunk" -> {
                val content = map["content"] as? String ?: return
                replaceTimeline { rows ->
                    val last = rows.lastOrNull()
                    if (last is TimelineItem.ThoughtMsg) {
                        rows[rows.size - 1] = last.copy(text = last.text + content)
                    } else {
                        rows.add(TimelineItem.ThoughtMsg("th${rows.size}", content))
                    }
                }
            }
            "tool_call" -> {
                val toolId = map["toolId"] as? String ?: return
                replaceTimeline { rows ->
                    rows.removeAll { it is TimelineItem.ToolRow && it.toolId == toolId }
                    rows.add(
                        TimelineItem.ToolRow(
                            toolId = toolId,
                            name = (map["toolName"] as? String) ?: (map["kind"] as? String) ?: "tool",
                            title = (map["title"] as? String) ?: "",
                            status = "in_progress",
                        ),
                    )
                }
            }
            "tool_call_update" -> {
                val toolId = map["toolId"] as? String ?: return
                replaceTimeline { rows ->
                    val idx = rows.indexOfFirst { it is TimelineItem.ToolRow && it.toolId == toolId }
                    if (idx >= 0) {
                        val row = rows[idx] as TimelineItem.ToolRow
                        rows[idx] = row.copy(
                            status = (map["status"] as? String) ?: row.status,
                            title = (map["title"] as? String) ?: row.title,
                            name = (map["toolName"] as? String) ?: row.name,
                        )
                    } else {
                        rows.add(
                            TimelineItem.ToolRow(
                                toolId = toolId,
                                name = (map["toolName"] as? String) ?: "tool",
                                title = (map["title"] as? String) ?: "",
                                status = (map["status"] as? String) ?: "completed",
                            ),
                        )
                    }
                }
            }
            "permission_request" -> replaceTimeline { addPermissionCard(it, map) }
            "permission_response" -> {
                val requestId = map["requestId"] as? String ?: return
                replaceTimeline { rows ->
                    rows.removeAll { it is TimelineItem.PermissionCard && it.requestId == requestId }
                }
            }
            "task_run_started" -> _state.update { it.copy(running = true) }
            "task_complete" -> {
                _state.update { it.copy(running = false) }
                val reason = map["stopReason"] as? String ?: ""
                replaceTimeline { it.add(TimelineItem.Notice("done${it.size}", "Completed ($reason)", false)) }
            }
            "task_error" -> {
                _state.update { it.copy(running = false) }
                val text = (map["error"] as? String) ?: "task error"
                replaceTimeline { it.add(TimelineItem.Notice("err${it.size}", text, true)) }
            }
            "task_warning" -> {
                val text = (map["warning"] as? String) ?: ""
                replaceTimeline { it.add(TimelineItem.Notice("warn${it.size}", text, false)) }
            }
            else -> {
                // Unknown event shapes are logged for evidence, never rendered.
                Log.i("NativeConversation", "unrendered task event: ${map}")
                RuntimeBus.append("stream event (unrendered): ${map["type"]}")
            }
        }
    }

    fun send(text: String) {
        val api = api ?: run {
            _state.update { it.copy(sendError = "runtime not connected yet") }
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _state.update { it.copy(sendError = null) }
            }
            var taskId = _state.value.selectedTaskId
            if (taskId == null) {
                val created = runCatching { api.createTask(workspacePath) }
                    .getOrElse { e ->
                        RuntimeBus.append("createTask failed: ${e.message}")
                        _state.update { it.copy(sendError = "task creation failed: ${e.message}") }
                        return@launch
                    }
                taskId = created["taskId"] as? String
                if (taskId == null) {
                    _state.update { it.copy(sendError = "task creation returned no taskId: $created") }
                    return@launch
                }
                _state.update { it.copy(selectedTaskId = taskId, timeline = emptyList()) }
                val listener = api.taskEvents(workspacePath, taskId)
                taskListener?.dispose()
                taskListener = listener
                collectorJob?.cancel()
                collectorJob = viewModelScope.launch(Dispatchers.IO) {
                    listener.flow.collect { ev -> handleStreamEvent(ev) }
                }
            }
            withContext(Dispatchers.Main) {
                replaceTimeline { rows -> rows.add(TimelineItem.UserMsg("u${rows.size}", trimmed)) }
                _state.update { it.copy(running = true) }
            }
            runCatching { api.sendPrompt(workspacePath, taskId, trimmed) }
                .onFailure { e ->
                    RuntimeBus.append("sendPrompt failed: ${e.message}")
                    _state.update { it.copy(running = false, sendError = e.message ?: "send failed") }
                }
        }
    }

    fun stop() {
        val api = api ?: return
        val taskId = _state.value.selectedTaskId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.stopGeneration(workspacePath, taskId) }
                .onFailure { RuntimeBus.append("stopGeneration failed: ${it.message}") }
        }
    }

    fun respondPermission(requestId: String, option: PermOption) {
        val api = api ?: return
        val taskId = _state.value.selectedTaskId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.respondPermission(workspacePath, taskId, requestId, option.optionId, option.decision) }
                .onFailure { e ->
                    RuntimeBus.append("respondPermission failed: ${e.message}")
                    _state.update { s -> s.copy(sendError = "permission response failed: ${e.message}") }
                }
        }
    }

    fun dismissSendError() = _state.update { it.copy(sendError = null) }

    override fun onCleared() {
        taskListener?.dispose()
        rpc?.close()
    }

    companion object {
        /**
         * Reflection-proof construction for `viewModel(factory = ...)`.
         * The default factory reflects for a no-arg constructor; this class
         * needs the workspace path, so without an explicit factory every
         * composition crashed with NoSuchMethodException `<init> []`
         * (FATAL EXCEPTION on main — CI run 41994982876, "ZCode keeps
         * stopping" on the relaunch).
         */
        fun factory(ctx: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConversationViewModel(
                        RuntimePaths.workspace(ctx.applicationContext).absolutePath,
                    ) as T
            }
    }
}
