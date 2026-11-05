package io.github.zcodeandroid.app

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zcodeandroid.app.native.ZcodeApi
import io.github.zcodeandroid.app.protocol.ZcodeRpcClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0 exit evidence (REVISED architecture §5/§15; INSPECTION-2026-11-05 §10.4):
 * one REAL chat round through the restored Kotlin protocol stack —
 * ZcodeRpcClient (binary frames + VQL codec) + ZcodeApi (zcode-task channel) —
 * against the app's own embedded server and the offline mock provider:
 *
 *   launch MainActivity (the real silent auto-start path) -> native WS client
 *   READY -> createTask -> sendPrompt -> collect onDynamicTaskEvent frames ->
 *   respondPermission(allow) when a permission_request arrives -> sentinel
 *   VERIFY-MOCK-DONE.
 *
 * A SECOND native WS client runs project.readProjects concurrently for the
 * whole turn (coexistence proof next to the server's own
 * register-workspace-project client machinery).
 *
 * Instrumented test only — the product surface is NOT touched. Event payload
 * shapes beyond project.* are UNKNOWN (evidence ledger): anything
 * unrecognized is logged verbatim, never interpreted.
 */
@RunWith(AndroidJUnit4::class)
class NativeProtocolProbeTest {

    private val tag = "NativeProtocolProbe"
    private val sentinel = "VERIFY-MOCK-DONE"
    private val prompt =
        "Run the mock verification task: create a file, run a shell marker, commit with git."

    private fun log(msg: String) {
        Log.i(tag, msg)
        println("$tag: $msg")
    }

    /** Defensive renderer: Map/List/primitives, never throws. */
    private fun render(data: Any?): String = when (data) {
        null -> "null"
        is Map<*, *> -> data.entries.joinToString(",", "{", "}") { "${it.key}=${render(it.value)}" }
        is List<*> -> data.joinToString(",", "[", "]") { render(it) }
        else -> data.toString()
    }

    private fun short(data: Any?): String = render(data).let { if (it.length > 400) it.take(400) + "…" else it }

    /** First recognizable kind-ish string, else "unrecognized" (logged verbatim anyway). */
    private fun extractKind(data: Any?): String {
        if (data is Map<*, *>) {
            for (key in listOf("kind", "type", "event", "method", "name", "state")) {
                val v = data[key]
                if (v is String && v.isNotEmpty()) return v
            }
            for (key in listOf("payload", "data")) {
                (data[key])?.let { nested -> return extractKind(nested) }
            }
        }
        return "unrecognized"
    }

    /** Recursive walk for {requestId, options[]} — shape REFERENCED, not yet wire-verified. */
    private fun findPermission(data: Any?): Pair<String, List<Map<String, Any?>>>? {
        when (data) {
            is Map<*, *> -> {
                val rid = data["requestId"]
                val opts = data["options"]
                if (rid != null && opts is List<*>) {
                    return rid.toString() to opts.filterIsInstance<Map<String, Any?>>()
                }
                for (v in data.values) findPermission(v)?.let { return it }
            }
            is List<*> -> for (v in data) findPermission(v)?.let { return it }
        }
        return null
    }

    /** Only an option whose rendering mentions "allow" — never guess on unknown shapes. */
    private fun pickAllowOptionId(opts: List<Map<String, Any?>>): String? =
        opts.firstOrNull { render(it).contains("allow", ignoreCase = true) }
            ?.get("optionId")?.toString()

    @Test
    fun nativeProtocolChatRound() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var listener: ZcodeRpcClient.Listener? = null
        var client: ZcodeRpcClient? = null
        var concurrent: ZcodeRpcClient? = null
        try {
            val instr = InstrumentationRegistry.getInstrumentation()
            val ctx = instr.targetContext
            val wsPath = File(ctx.filesDir, "workspace").absolutePath

            // 1. Real app startup path: the activity starts the foreground
            //    service (provisioned runtime -> Ready -> LocalServerService).
            instr.startActivitySync(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            log("MainActivity launched; waiting for ws://127.0.0.1:3030/ws (client-managed reconnect)")

            // 2. Main native client: READY = project.readProjects answered.
            val main = ZcodeRpcClient("ws://127.0.0.1:3030/ws", scope)
            client = main
            main.connect()
            val readyDeadline = System.currentTimeMillis() + 240_000L
            var ready = false
            while (System.currentTimeMillis() < readyDeadline) {
                if (main.healthProbe(8_000L)) { ready = true; break }
                delay(2_000L)
            }
            if (!ready) fail("native WS client never became READY within 240s (server unreachable or initialize frame rejected)")
            log("native WS client READY (initialize frame + readProjects answered)")

            // 3. Concurrent second native client for the whole turn.
            val second = ZcodeRpcClient("ws://127.0.0.1:3030/ws", scope)
            concurrent = second
            second.connect()
            // Probe spacing must fit INSIDE the mock turn: run 42097957584
            // proved the full chat round now completes in ~7-9s (sentinel +
            // task_complete via the Kotlin client), so 8s spacing allowed
            // only ONE readProjects round-trip before the coexistence
            // assertion — the criterion starved on pacing, not on protocol.
            // 2s spacing keeps >= 2 REAL concurrent round-trips during the
            // turn (coexistence proof stays honest, nothing weakened).
            val concurrentOk = AtomicInteger(0)
            val concurrentJob = scope.launch {
                while (isActive) {
                    if (second.healthProbe(8_000L)) concurrentOk.incrementAndGet()
                    delay(2_000L)
                }
            }

            val api = ZcodeApi(main)

            // 4. Task setup (pinned-source contracts; errors are evidence).
            val projects = api.readProjects()
            log("project.readProjects -> ${projects.size} registered project(s)")
            runCatching { api.initializeWorkspace(wsPath) }
                .onSuccess { log("initializeWorkspace ok: ${short(it)}") }
                .onFailure { log("initializeWorkspace error (logged, non-fatal contract probe): ${it.message}") }
            runCatching { api.listTasks(wsPath) }
                .onSuccess { log("listTasks -> ${it.size} existing task(s)") }
                .onFailure { log("listTasks error (logged, non-fatal): ${it.message}") }

            // Standalone-server contract (remote-policy.ts, no bypass): task
            // creation requires the registered project that owns the
            // workspace root — run 42071111737 failed exactly here with
            // project.remote-project-required when no projectId was sent.
            val projectId = api.resolveProjectId(wsPath)
            log("workspace project resolution -> ${projectId ?: "NONE"}")
            if (projectId == null) {
                fail(
                    "no registered project owns exactly the workspace root " +
                        "(projects=${short(projects)}); createTask on a standalone " +
                        "server requires it (upstream remote-policy)",
                )
            }
            val task = api.createTask(wsPath, projectId)
            val taskId: String = (task["taskId"] as? String)
                ?: (task["id"] as? String)
                ?: ((task["task"] as? Map<*, *>)?.get("taskId") as? String)
                ?: ((task["task"] as? Map<*, *>)?.get("id") as? String)
                ?: run {
                    // org.junit.Assert.fail returns void (not Nothing), so the
                    // null check alone does not smart-cast; fail then stop.
                    fail("createTask returned no taskId (result: ${short(task)})")
                    return@runBlocking
                }
            log("createTask -> taskId=$taskId")

            // 5. Subscribe BEFORE sendPrompt so every turn frame is captured.
            val sub = api.taskEvents(wsPath, taskId)
            listener = sub
            val done = CompletableDeferred<Unit>()
            val kinds = ConcurrentHashMap.newKeySet<String>()
            val permissionCount = AtomicInteger(0)
            scope.launch {
                sub.flow.collect { data ->
                    val text = render(data)
                    kinds.add(extractKind(data))
                    log("task-event: ${text.take(400)}")
                    if (text.contains(sentinel)) done.complete(Unit)
                    val perm = findPermission(data)
                    if (perm != null) {
                        val (requestId, options) = perm
                        val optionId = pickAllowOptionId(options)
                        if (optionId != null) {
                            permissionCount.incrementAndGet()
                            log("permission_request -> respondPermission allow (requestId=$requestId optionId=$optionId)")
                            val resp = runCatching {
                                api.respondPermission(wsPath, taskId, requestId, optionId, "allow")
                            }
                            log("respondPermission -> ${resp.getOrNull()} (turn continuation is the acceptance)")
                        } else {
                            log("permission_request with NO allow-looking option — logged verbatim, not guessed: ${text.take(800)}")
                        }
                    }
                }
            }
            delay(1_000L) // let the collector subscribe before the turn starts

            // 6. The real chat round.
            api.sendPrompt(wsPath, taskId, prompt)
            log("sendPrompt accepted; collecting onDynamicTaskEvent frames until sentinel")

            val got = withTimeoutOrNull(300_000L) { done.await() } != null

            // 7. Post-turn evidence.
            val snapshot = runCatching { api.getTaskSnapshot(wsPath, taskId) }
                .getOrDefault(emptyMap<String, Any?>())
            log("task snapshot keys=${(snapshot as? Map<*, *>)?.keys ?: emptySet<Any>()}")
            concurrentJob.cancel()
            log("event types observed: ${kinds.sorted()}")

            if (!got) fail("sentinel '$sentinel' never observed within 300s (kinds=${kinds.sorted()}; snapshot=${short(snapshot)})")
            assertTrue("no task events received", kinds.isNotEmpty())
            assertTrue(
                "concurrent native client readProjects succeeded only ${concurrentOk.get()} time(s)",
                concurrentOk.get() >= 2,
            )

            val summary = "NATIVE-PROBE-PASS event-types=${kinds.sorted()} " +
                "permission-requests=${permissionCount.get()} " +
                "concurrent-readProjects-ok=${concurrentOk.get()}"
            log(summary)
            if (permissionCount.get() == 0) {
                log("honest note: respondPermission was NOT exercised (no permission_request events; mock turn auto-allowed under this task's scope)")
            }
        } finally {
            listener?.dispose()
            client?.close()
            concurrent?.close()
            scope.cancel()
        }
    }
}
