#!/usr/bin/env node
// E1 protocol driver: runs INSIDE the emulator (Bionic node), spawns the real
// ZCode agent bundle (app-server --stdio) and drives one full agent turn:
//
//   runtime/capabilities -> session/create {workspace, mode:"yolo", model} ->
//   session/send {sessionId, content} -> collect notification frames until the
//   mock's final text sentinel appears (or timeout).
//
// E2 adaptation: runs ON-DEVICE (the same device the app runs on). The mock
// model server also runs on-device (started by the app itself on 127.0.0.1:9999),
// so the whole loop works offline. spawn uses process.execPath so the exact
// same Bionic node that provisioned the runtime also runs the agent.
//
// The mock model scripts the turn:
// Write file -> Bash marker -> Bash git commit -> final text.
//
// Exit codes:
//   0  turn completed end-to-end (sentinel observed in a frame)
//   3  protocol failure (capabilities/create/send error, or sentinel never seen)
//   2  usage/spawn error
//
// Usage: node e1-driver.cjs --agent <zcode.cjs> --workspace <dir> --timeout 180
//        [--preflight http://127.0.0.1:9999/healthz]

"use strict";

const { spawn } = require("node:child_process");
const { resolve } = require("node:path");
const readline = require("node:readline");

function parseArgs(argv) {
  const o = { agent: null, workspace: process.cwd(), timeout: 180,
              preflight: "http://127.0.0.1:9999/healthz" };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--agent") o.agent = argv[++i];
    else if (a === "--workspace") o.workspace = argv[++i];
    else if (a === "--timeout") o.timeout = Number(argv[++i]) || 180;
    else if (a === "--preflight") o.preflight = argv[++i];
    else { console.error("unknown arg: " + a); process.exit(2); }
  }
  if (!o.agent) { console.error("usage: node e1-driver.cjs --agent <zcode.cjs> [--workspace dir]"); process.exit(2); }
  return o;
}

const opts = parseArgs(process.argv.slice(2));
const SENTINEL = "E1-MOCK-DONE";
const child = spawn(process.execPath, [resolve(opts.agent), "app-server", "--stdio"], {
  cwd: resolve(opts.workspace),
  stdio: ["pipe", "pipe", "pipe"],
});

let nextId = 1;
const pending = new Map();
let capabilitiesOk = false;
let sessionId = null;
let sawSentinel = false;
let sawErrorFrame = false;
const methodsSeen = new Set();

function send(obj) {
  const line = JSON.stringify(obj);
  console.log("-> " + line.slice(0, 400));
  child.stdin.write(line + "\n");
}

function request(method, params) {
  const id = nextId++;
  return new Promise((res) => {
    pending.set(id, res);
    send({ id, method, ...(params !== undefined ? { params } : {}) });
  });
}

child.on("error", (e) => { console.error("spawn error:", e.message); process.exit(2); });
child.stderr.on("data", (d) => {
  process.stdout.write("[agent-stderr] " + d.toString().trimEnd().slice(0, 400) + "\n");
});

const rl = readline.createInterface({ input: child.stdout });
rl.on("line", (line) => {
  const t = line.trim();
  if (!t) return;
  const interesting = /turn-failed|turn-started|turn\.terminal|"error"|state\.updated|v4\/telemetry|computer-use/.test(t);
  const max = interesting ? 3000 : 300;
  const shown = t.length > max ? t.slice(0, max) + " …[trunc]" : t;
  console.log("<- " + shown);
  let frame;
  try { frame = JSON.parse(t); } catch { return; }
  if (JSON.stringify(frame).includes(SENTINEL)) sawSentinel = true;
  if (frame && frame.id !== undefined && (frame.result !== undefined || frame.error !== undefined)) {
    // response to OUR request
    const res = pending.get(frame.id);
    if (res) { pending.delete(frame.id); res(frame); }
  } else if (frame && frame.method && frame.id !== undefined) {
    // SERVER->CLIENT request: must be answered or the server times out.
    methodsSeen.add(frame.method);
    if (frame.method === "session/requestRuntimePreferences") {
      send({ id: frame.id, result: {
        nativeSearchEnhancementsEnabled: false,
        memoryEnabled: false,
        askUserQuestionAutoResolutionEnabled: true,
      } });
    } else {
      send({ id: frame.id, result: {} });
    }
  } else if (frame && frame.method) {
    methodsSeen.add(frame.method);
  }
});

child.on("exit", (code, sig) => {
  console.log(`[driver] agent exited code=${code} sig=${sig}`);
  for (const res of pending.values()) res({ id: -1, error: { code: -1, message: "agent exited" } });
});

function fail(msg) {
  console.error("[driver] " + msg);
  process.exitCode = 3;
  try { child.kill("SIGTERM"); } catch {}
  setTimeout(() => process.exit(3), 600);
}

async function main() {
  // Preflight: is the on-device mock reachable over loopback?
  try {
    const r = await fetch(opts.preflight);
    console.log("[driver] mock reachability: HTTP", r.status, (await r.text()).slice(0, 120));
  } catch (e) {
    console.log("[driver] MOCK UNREACHABLE from emulator:", (e && e.message) || e);
  }

  const caps = await request("runtime/capabilities");
  if (caps.error === undefined) {
    capabilitiesOk = true;
    console.log("[driver] runtime/capabilities answered");
  } else {
    console.log("[driver] capabilities error: " + JSON.stringify(caps.error).slice(0, 300));
    return fail("capabilities failed");
  }

  const create = await request("session/create", {
    workspace: {
      workspacePath: resolve(opts.workspace),
      workspaceKey: "e1-ws-key",
    },
    mode: "yolo",
    model: {
      providerId: "e1mock",
      modelId: "mock-model",
      options: { reasoningLevel: "disabled" },
    },
  });
  if (create.error !== undefined) {
    console.log("[driver] session/create error: " + JSON.stringify(create.error).slice(0, 500));
    return fail("session/create failed");
  }
  const r = create.result || {};
  sessionId = r.sessionId || r.id || (r.session && (r.session.id || r.session.sessionId));
  console.log("[driver] session/create -> sessionId=" + sessionId);
  if (!sessionId) {
    console.log("[driver] create result: " + JSON.stringify(create.result).slice(0, 500));
    return fail("no sessionId in create result");
  }

  const sendr = await request("session/send", {
    sessionId,
    content: "Run the E1 mock task: create a file, run a shell marker, commit with git.",
    modelSelection: {
      providerId: "e1mock",
      modelId: "mock-model",
      options: { reasoningLevel: "disabled" },
    },
  });
  if (sendr.error !== undefined) {
    console.log("[driver] session/send error: " + JSON.stringify(sendr.error).slice(0, 500));
    return fail("session/send failed");
  }
  console.log("[driver] session/send accepted; collecting notifications...");

  const deadline = Date.now() + opts.timeout * 1000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 1500));
    if (sawSentinel) break;
  }

  console.log("[driver] methods seen: " + [...methodsSeen].sort().join(", "));
  if (!sawSentinel) {
    // Pull the session transcript — the turn failure reason lands there.
    try {
      const msgs = await request("session/messages", { sessionId, limit: 10 });
      console.log("[driver] session/messages -> " + JSON.stringify(msgs).slice(0, 4000));
    } catch (e) {
      console.log("[driver] session/messages dump failed: " + e);
    }
  }
  if (sawSentinel) {
    console.log("[driver] SENTINEL observed — turn completed end-to-end");
    process.exitCode = 0;
    try { child.kill("SIGTERM"); } catch {}
    setTimeout(() => process.exit(0), 600);
  } else {
    return fail("sentinel not observed within timeout (frames logged above)");
  }
}

main().catch((e) => { console.error("[driver] fatal:", e); process.exit(2); });
