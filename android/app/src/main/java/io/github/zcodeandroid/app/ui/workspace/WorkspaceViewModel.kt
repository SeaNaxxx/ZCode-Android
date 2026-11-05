package io.github.zcodeandroid.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zcodeandroid.app.local.RuntimeBus
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
 * P4 workspace state (REVISED architecture §5 Phase 4). Read-only file
 * browsing + git status over the PINNED file/git channel contracts; every
 * failure is explicit and recoverable (§6: protocol failures never become
 * empty lists). Write operations are NOT claimed — the file channel has
 * no workspace-file write method at this pin and the UI must not pretend
 * otherwise (§12 prohibited shortcuts).
 */
class WorkspaceViewModel(
    private val workspacePath: String,
) : ViewModel() {

    data class EntryUi(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isSymbolicLink: Boolean,
    )

    data class GitFileChangeUi(
        val path: String,
        val kind: String,
        val added: Int,
        val removed: Int,
        val isUntracked: Boolean,
    )

    data class PreviewUi(
        val path: String,
        val content: String,
        val truncated: Boolean,
        val isBinary: Boolean,
    )

    data class GitSummaryUi(
        val isRepository: Boolean,
        val gitAvailable: Boolean,
        val branchName: String?,
        val ahead: Int,
        val behind: Int,
        val isDirty: Boolean,
    )

    data class UiState(
        val rootPath: String,
        val cwd: String,
        val entries: List<EntryUi> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val preview: PreviewUi? = null,
        val previewLoading: Boolean = false,
        val git: GitSummaryUi? = null,
        val changes: List<GitFileChangeUi> = emptyList(),
        val gitLoading: Boolean = false,
        val conn: ConversationConn = ConversationConn.CONNECTING,
    )

    enum class ConversationConn { CONNECTING, READY, CLOSED }

    private val _state = MutableStateFlow(UiState(rootPath = workspacePath, cwd = workspacePath))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var rpc: ZcodeRpcClient? = null
    private var api: ZcodeApi? = null
    private var connJob: Job? = null

    fun start() {
        if (rpc != null) return
        val client = ZcodeRpcClient("ws://127.0.0.1:3030/ws", viewModelScope)
        rpc = client
        api = ZcodeApi(client)
        connJob = viewModelScope.launch {
            client.conn.collect { c ->
                _state.update {
                    it.copy(
                        conn = when (c) {
                            ZcodeRpcClient.Conn.READY -> ConversationConn.READY
                            ZcodeRpcClient.Conn.CONNECTING -> ConversationConn.CONNECTING
                            else -> ConversationConn.CLOSED
                        },
                    )
                }
            }
        }
        client.connect()
        openDir(workspacePath)
        refreshGit()
    }

    fun openDir(path: String) {
        val api = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null, preview = null) }
            runCatching { api.readdir(path) }
                .onSuccess { list ->
                    val entries = list.mapNotNull { e ->
                        val name = e["name"] as? String ?: return@mapNotNull null
                        EntryUi(
                            name = name,
                            path = (e["path"] as? String) ?: joinPath(path, name),
                            isDirectory = (e["type"] as? String) == "directory",
                            isSymbolicLink = e["isSymbolicLink"] == true,
                        )
                    }.sortedWith(compareByDescending<EntryUi> { it.isDirectory }.thenBy { it.name.lowercase() })
                    _state.update { it.copy(cwd = path, entries = entries, loading = false) }
                }
                .onFailure { e ->
                    RuntimeBus.append("readdir failed: ${e.message}")
                    _state.update { it.copy(loading = false, error = e.message ?: "readdir failed") }
                }
        }
    }

    fun openParent() {
        val cwd = _state.value.cwd
        val parent = cwd.trimEnd('/').substringBeforeLast('/', "")
        if (parent.isEmpty() || !parent.startsWith(workspacePath)) return
        openDir(parent)
    }

    fun openFile(path: String) {
        val api = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(previewLoading = true, error = null) }
            runCatching { api.readTextFile(path) }
                .onSuccess { slice ->
                    _state.update {
                        it.copy(
                            previewLoading = false,
                            preview = PreviewUi(
                                path = (slice["path"] as? String) ?: path,
                                content = (slice["content"] as? String) ?: "",
                                truncated = slice["truncated"] == true,
                                isBinary = slice["isBinary"] == true,
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    RuntimeBus.append("readTextFile failed: ${e.message}")
                    _state.update {
                        it.copy(previewLoading = false, error = e.message ?: "read failed")
                    }
                }
        }
    }

    fun closePreview() = _state.update { it.copy(preview = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun refreshGit() {
        val api = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(gitLoading = true) }
            runCatching { api.gitRepositorySummary(workspacePath) }
                .onSuccess { s ->
                    val summary = GitSummaryUi(
                        isRepository = s["isRepository"] == true,
                        gitAvailable = s["isGitAvailable"] == true,
                        branchName = s["branchName"] as? String,
                        ahead = (s["ahead"] as? Number)?.toInt() ?: 0,
                        behind = (s["behind"] as? Number)?.toInt() ?: 0,
                        isDirty = s["isDirty"] == true,
                    )
                    _state.update { it.copy(git = summary, gitLoading = false) }
                    if (summary.isRepository) {
                        runCatching { api.gitChanges(workspacePath) }
                            .onSuccess { list ->
                                _state.update { st ->
                                    st.copy(
                                        changes = list.mapNotNull { c ->
                                            val p = c["path"] as? String ?: return@mapNotNull null
                                            GitFileChangeUi(
                                                path = p,
                                                kind = (c["kind"] as? String) ?: "modified",
                                                added = (c["added"] as? Number)?.toInt() ?: 0,
                                                removed = (c["removed"] as? Number)?.toInt() ?: 0,
                                                isUntracked = c["isUntracked"] == true,
                                            )
                                        },
                                    )
                                }
                            }
                            .onFailure { e -> RuntimeBus.append("git.getChanges failed: ${e.message}") }
                    } else {
                        _state.update { it.copy(changes = emptyList()) }
                    }
                }
                .onFailure { e ->
                    RuntimeBus.append("git.getRepositorySummary failed: ${e.message}")
                    _state.update { it.copy(gitLoading = false, git = null, changes = emptyList()) }
                }
        }
    }

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"

    override fun onCleared() {
        rpc?.close()
    }
}
