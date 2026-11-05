package io.github.zcodeandroid.app.native

import io.github.zcodeandroid.app.protocol.ZcodeRpcClient
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * Typed wrappers over the pinned upstream service channels (packages/shared/
 * src/channels.ts ServiceChannels at 3c5b227649371507ddc4eb46ac5133afe6db1cab).
 *
 * Method names and param shapes come from the pinned service interfaces:
 *  - packages/services/src/session/zcodeTaskService.ts (IZCodeTaskService)
 *  - packages/services/src/oauth/oauth.ts (OAuth service)
 *  - project.readProjects/saveProject — already proven on-device by
 *    register-workspace-project.cjs on every server start.
 *
 * Values are org.json-decoded Kotlin natives (Map/List/String/Number/...).
 * Parsing is defensive: unknown fields are ignored, unknown event kinds are
 * surfaced as raw maps so the UI never fabricates content it does not know.
 */
class ZcodeApi(private val rpc: ZcodeRpcClient) {

    // ---------------------------------------------------------------- project

    suspend fun readProjects(): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return rpc.call(CHANNEL_PROJECT, "readProjects") as? List<Map<String, Any?>> ?: emptyList()
    }

    /**
     * Official project resolution before createTask (ENGINEERING-NOTES §5.3:
     * "register the workspace as a project … then start tasks from that
     * project"). Upstream remote-policy (project-storage/remote-policy.ts
     * assertRemoteTaskDirectories via reserveCreatedTask) rejects task
     * creation on a standalone server with `project.remote-project-required`
     * unless the createTask params carry the projectId of a registered,
     * non-deleted project with EXACTLY one root equal to the workspace path.
     * Run 42071111737: the probe sent no projectId and hit exactly that
     * rejection. This mirrors the official Projects UI path — no protocol
     * bypass, no upstream change.
     */
    fun findRegisteredProjectId(
        projects: List<Map<String, Any?>>,
        workspacePath: String,
    ): String? {
        for (project in projects) {
            if (project["deleted"] == true) continue
            val id = project["id"] as? String ?: continue
            val roots = project["roots"] as? List<*> ?: continue
            val rootPaths = roots
                .filterIsInstance<Map<String, Any?>>()
                .mapNotNull { it["rootPath"] as? String }
            // remote-policy: remote sessions allow exactly one fixed root.
            if (rootPaths.size == 1 && rootPaths[0] == workspacePath) return id
        }
        return null
    }

    suspend fun resolveProjectId(workspacePath: String): String? =
        findRegisteredProjectId(readProjects(), workspacePath)

    // ------------------------------------------------------------------ tasks

    suspend fun initializeWorkspace(workspacePath: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return rpc.call(
            CHANNEL_ZCODE_TASK, "initialize",
            mapOf("workspacePath" to workspacePath),
        ) as? Map<String, Any?> ?: emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun listTasks(workspacePath: String): List<Map<String, Any?>> =
        rpc.call(
            CHANNEL_ZCODE_TASK, "listTasks",
            mapOf("workspacePath" to workspacePath),
        ) as? List<Map<String, Any?>> ?: emptyList()

    /**
     * createTask params per IZCodeTaskService.createTask (pinned source);
     * projectId is an official param and is REQUIRED on standalone servers
     * (remote-policy asserts a registered project — see
     * findRegisteredProjectId). Sent only when resolved: the wire keeps the
     * "no projectId" shape a caller that has none, so upstream behavior is
     * never masked.
     */
    suspend fun createTask(workspacePath: String, projectId: String? = null): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val params = buildMap<String, Any?> {
            if (projectId != null) put("projectId", projectId)
            put("workspacePath", workspacePath)
            put("permissionScope", "session")
        }
        return rpc.call(
            CHANNEL_ZCODE_TASK, "createTask",
            params,
        ) as? Map<String, Any?> ?: emptyMap()
    }

    /**
     * sendPrompt: traceId is a fresh UUID per turn (TraceId = string upstream),
     * clientLabel identifies this native client on the wire.
     */
    suspend fun sendPrompt(
        workspacePath: String,
        taskId: String,
        content: String,
    ) {
        rpc.call(
            CHANNEL_ZCODE_TASK, "sendPrompt",
            mapOf(
                "taskId" to taskId,
                "workspacePath" to workspacePath,
                "traceId" to UUID.randomUUID().toString(),
                "content" to content,
                "clientId" to "zcode-android-native",
                "clientLabel" to "ZCode Android (native)",
                "clientMode" to "desktop-continuous",
            ),
        )
    }

    suspend fun stopGeneration(workspacePath: String, taskId: String) {
        rpc.call(
            CHANNEL_ZCODE_TASK, "stopGeneration",
            mapOf("taskId" to taskId, "workspacePath" to workspacePath),
        )
    }

    /**
     * respondPermission: optionId comes from the permission_request event's
     * options[].optionId; response mirrors ZCodePermissionResponse
     * ({decision: allow|deny|escalate|modify}).
     */
    suspend fun respondPermission(
        workspacePath: String,
        taskId: String,
        requestId: String,
        optionId: String,
        decision: String,
    ): Boolean = rpc.call(
        CHANNEL_ZCODE_TASK, "respondPermission",
        mapOf(
            "taskId" to taskId,
            "workspacePath" to workspacePath,
            "requestId" to requestId,
            "optionId" to optionId,
            "response" to mapOf("decision" to decision),
        ),
    ) as? Boolean ?: false

    suspend fun getTaskSnapshot(workspacePath: String, taskId: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return rpc.call(
            CHANNEL_ZCODE_TASK, "getTaskSnapshot",
            mapOf("taskId" to taskId, "workspacePath" to workspacePath),
        ) as? Map<String, Any?> ?: emptyMap()
    }

    suspend fun getTaskModelSelection(taskId: String): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return rpc.call(
            CHANNEL_ZCODE_TASK, "getTaskModelSelection",
            mapOf("taskId" to taskId),
        ) as? Map<String, Any?>
    }

    suspend fun getTaskConfigOptions(taskId: String): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return rpc.call(
            CHANNEL_ZCODE_TASK, "getTaskConfigOptions",
            mapOf("taskId" to taskId),
        ) as? List<Map<String, Any?>> ?: emptyList()
    }

    suspend fun setMode(taskId: String, mode: String) {
        rpc.call(CHANNEL_ZCODE_TASK, "setMode", mapOf("taskId" to taskId, "mode" to mode))
    }

    /** setModel per IZCodeTaskService.setModel — modelSelection shape is the
     *  official ModelSelection object (modelSelection channel owns its schema);
     *  the native MVP forwards the exact object returned by the config option. */
    suspend fun setModel(taskId: String, modelSelection: Map<String, Any?>) {
        rpc.call(
            CHANNEL_ZCODE_TASK, "setModel",
            mapOf("taskId" to taskId, "modelSelection" to modelSelection),
        )
    }

    /** Streaming: workspace+task scoped event subscription (onDynamicTaskEvent). */
    fun taskEvents(workspacePath: String, taskId: String): ZcodeRpcClient.Listener =
        rpc.listen(
            CHANNEL_ZCODE_TASK, "onDynamicTaskEvent",
            mapOf(
                "workspacePath" to workspacePath,
                "taskId" to taskId,
                "clientId" to "zcode-android-native",
            ),
        )

    /** Workspace-level notifications (config options / commands before task). */
    fun workspaceEvents(workspacePath: String): ZcodeRpcClient.Listener =
        rpc.listen(CHANNEL_ZCODE_TASK, "onDynamicWorkspaceEvent", mapOf("workspacePath" to workspacePath))

    // ------------------------------------------------------- workspace (P4)

    // Pinned-source contracts (zai-org/ZCode @ 3c5b227, packages/services):
    // IFileService.readdir/readTextFile, IGitService.getRepositorySummary/
    // getChanges. Unlike the defensive wrappers above (which keep the UI
    // from fabricating content), these THROW on unexpected shapes — §6:
    // never silently convert protocol failures into empty lists.

    private fun unexpected(method: String, got: Any?): IllegalStateException =
        IllegalStateException("$method returned unexpected shape: ${got?.javaClass?.name ?: "null"}")

    /** file.readdir({path, includeHidden?}) -> FileEntry[] */
    suspend fun readdir(path: String, includeHidden: Boolean = false): List<Map<String, Any?>> {
        val r = rpc.call(
            CHANNEL_FILE, "readdir",
            mapOf("path" to path, "includeHidden" to includeHidden),
        )
        @Suppress("UNCHECKED_CAST")
        return r as? List<Map<String, Any?>> ?: throw unexpected("file.readdir", r)
    }

    /** file.readTextFile({path, offset?, length?}) -> FileTextSlice */
    suspend fun readTextFile(path: String, offset: Long? = null, length: Long? = null): Map<String, Any?> {
        val params = HashMap<String, Any?>()
        params["path"] = path
        if (offset != null) params["offset"] = offset
        if (length != null) params["length"] = length
        val r = rpc.call(CHANNEL_FILE, "readTextFile", params)
        return r as? Map<String, Any?> ?: throw unexpected("file.readTextFile", r)
    }

    /** git.getRepositorySummary({workspacePath}) -> GitRepositorySummary */
    suspend fun gitRepositorySummary(workspacePath: String): Map<String, Any?> {
        val r = rpc.call(CHANNEL_GIT, "getRepositorySummary", mapOf("workspacePath" to workspacePath))
        return r as? Map<String, Any?> ?: throw unexpected("git.getRepositorySummary", r)
    }

    /** git.getChanges({workspacePath, sourceId: "unstaged"|"staged"}) -> GitFileChange[] */
    suspend fun gitChanges(workspacePath: String, sourceId: String = "unstaged"): List<Map<String, Any?>> {
        val r = rpc.call(
            CHANNEL_GIT, "getChanges",
            mapOf("workspacePath" to workspacePath, "sourceId" to sourceId),
        )
        @Suppress("UNCHECKED_CAST")
        return r as? List<Map<String, Any?>> ?: throw unexpected("git.getChanges", r)
    }

    // ------------------------------------------------------------------ oauth

    @Suppress("UNCHECKED_CAST")
    suspend fun oauthProviders(): List<Map<String, Any?>> =
        rpc.call(CHANNEL_OAUTH, "getProviders") as? List<Map<String, Any?>> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    suspend fun oauthStart(provider: String): Map<String, Any?> =
        rpc.call(CHANNEL_OAUTH, "startOAuth", provider) as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    suspend fun oauthPoll(): Map<String, Any?>? =
        rpc.call(CHANNEL_OAUTH, "pollPendingOAuth") as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    suspend fun oauthRestoreCachedSession(): Map<String, Any?>? =
        rpc.call(CHANNEL_OAUTH, "restoreCachedSession") as? Map<String, Any?>

    suspend fun oauthLogout() {
        runCatching { rpc.call(CHANNEL_OAUTH, "logout") }
    }

    companion object {
        const val CHANNEL_ZCODE_TASK = "zcode-task"
        const val CHANNEL_PROJECT = "project"
        const val CHANNEL_OAUTH = "oauth"
        const val CHANNEL_SETTING = "setting"
        const val CHANNEL_MODEL_SELECTION = "model-selection"
        const val CHANNEL_FILE = "file"
        const val CHANNEL_GIT = "git"
    }
}
