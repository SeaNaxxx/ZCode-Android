package io.github.zcodeandroid.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

/**
 * Home state (owner UI brief: the unified dashboard). One VM, one StateFlow.
 * Cards come from `listTasks` (the verified ZCodeTaskMeta contract);
 * badges come ONLY from workspace-level events that actually carry a
 * taskId (task_run_started / task_complete / task_error). If the runtime
 * never emits those at workspace level, every card stays honestly "Idle" —
 * a badge is never invented from nothing.
 */
class HomeViewModel(
    private val workspacePath: String,
) : ViewModel() {

    enum class Conn { CONNECTING, READY, CLOSED }
    enum class Badge { IDLE, RUNNING, COMPLETED }

    data class WorkspaceCardUi(
        val taskId: String,
        val title: String,
        val updatedAt: Long,
        val model: String?,
    )

    data class UiState(
        val cards: List<WorkspaceCardUi> = emptyList(),
        val badges: Map<String, Badge> = emptyMap(),
        val conn: Conn = Conn.CONNECTING,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var rpc: ZcodeRpcClient? = null
    private var api: ZcodeApi? = null
    private var listener: ZcodeRpcClient.Listener? = null
    private var collectorJob: Job? = null

    private val running = LinkedHashSet<String>()
    private val completed = LinkedHashSet<String>()

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
        refresh()
        subscribeWorkspaceEvents()
    }

    fun refresh() {
        val api = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { api.listTasks(workspacePath) }
                .onSuccess { metas ->
                    val cards = metas.mapNotNull { m ->
                        val id = m["taskId"] as? String ?: return@mapNotNull null
                        WorkspaceCardUi(
                            taskId = id,
                            title = (m["title"] as? String).orEmpty().ifEmpty { "(untitled)" },
                            updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
                            model = m["model"] as? String,
                        )
                    }.sortedByDescending { it.updatedAt }
                    _state.update { it.copy(cards = cards, loading = false, error = null) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "task list unavailable")
                    }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun subscribeWorkspaceEvents() {
        val api = api ?: return
        listener?.dispose()
        val l = api.workspaceEvents(workspacePath)
        listener = l
        collectorJob = viewModelScope.launch(Dispatchers.IO) {
            l.flow.collect { ev ->
                val map = ev as? Map<*, *> ?: return@collect
                val taskId = map["taskId"] as? String ?: return@collect
                var changed = false
                when (map["type"] as? String) {
                    "task_run_started" -> {
                        changed = running.add(taskId)
                        completed.remove(taskId)
                    }
                    "task_complete" -> {
                        changed = running.remove(taskId)
                        completed.add(taskId)
                    }
                    "task_error" -> {
                        changed = running.remove(taskId)
                    }
                }
                if (changed) recomputeBadges()
            }
        }
    }

    private fun recomputeBadges() {
        val badges = HashMap<String, Badge>()
        running.forEach { badges[it] = Badge.RUNNING }
        completed.forEach { badges[it] = Badge.COMPLETED }
        _state.update { it.copy(badges = badges) }
    }

    override fun onCleared() {
        collectorJob?.cancel()
        listener?.dispose()
        rpc?.close()
    }

    companion object {
        /** Reflection-proof construction (CI run 41994982876 crash class). */
        fun factory(ctx: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        RuntimePaths.workspace(ctx.applicationContext).absolutePath,
                    ) as T
            }
    }
}
