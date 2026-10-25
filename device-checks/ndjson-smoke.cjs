#!/usr/bin/env node
// Minimal NDJSON protocol smoke client for the ZCode-Android device checks.
// docs/RUNTIME-CHECKS.md (Check 3) and UPSTREAM-ANALYSIS.md §12/§14.
//
// Frame schemas verified at zai-org/ZCode 3c5b2276
// (packages/shared/src/zcode-protocol/index.ts:392-437, all .strict()):
//   request      {"id": string|int, "method": string, "params"?, "trace"?}
//   notification {"method": string, "params"?, "trace"?}
//   response     {"id", "result"}          error {"id", "error": {code, message, data?}}
//
// Only runtime/capabilities is sent by name (registered method, §4 of the analysis).
// Task mode (--task) exercises session/create and records every reply frame —
// including error frames, which are evidence, not failures of this client.
//
// Usage:
//   node ndjson-smoke.mjs --agent <path/to/agent/zcode.cjs> [--task "prompt"] \
//        [--workspace <dir>] [--timeout 20]
// Exit code 0 = capabilities answered; 3 = no answer (protocol-level failure);
// 2 = usage/spawn error.

"use strict";

const { spawn } = require("node:child_process");
const { resolve } = require("node:path");
const readline = require("node:readline");

function parseArgs(argv) {
  const o = { agent: null, task: null, workspace: process.cwd(), timeout: 20 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--agent") o.agent = argv[++i];
    else if (a === "--task") o.task = argv[++i];
    else if (a === "--workspace") o.workspace = argv[++i];
    else if (a === "--timeout") o.timeout = Number(argv[++i]) || 20;
    else if (a === "--send") o.raw = argv[++i]; // raw frame escape hatch
    else { console.error("unknown arg: " + a); process.exit(2); }
  }
  if (!o.agent) { console.error("usage: node ndjson-smoke.mjs --agent <zcode.cjs> [--task ...] [--send '<json>']"); process.exit(2); }
  return o;
}

const opts = parseArgs(process.argv.slice(2));
const agentPath = resolve(opts.agent);
const child = spawn("node", [agentPath, "app-server", "--stdio"], {
  cwd: resolve(opts.workspace),
  stdio: ["pipe", "pipe", "pipe"],
});

let nextId = 1;
const pending = new Map();
let capabilitiesAnswered = false;

function send(obj) {
  const line = JSON.stringify(obj);
  process.stdout.write(`-> ${line}\n`);
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
  // stderr is not a protocol channel (protocol-console.ts boundary) — logged, not parsed.
  process.stdout.write("[stderr] " + d.toString().trimEnd().slice(0, 500) + "\n");
});

const rl = readline.createInterface({ input: child.stdout });
rl.on("line", (line) => {
  const t = line.trim();
  if (!t) return;
  process.stdout.write("<- " + t.slice(0, 500) + (t.length > 500 ? " …[truncated]" : "") + "\n");
  let frame;
  try { frame = JSON.parse(t); } catch { return; } // non-JSON line is recorded, not fatal
  if (frame && frame.id !== undefined && (frame.result !== undefined || frame.error !== undefined)) {
    const res = pending.get(frame.id);
    if (res) { pending.delete(frame.id); res(frame); }
  }
});

child.on("exit", (code, sig) => {
  process.stdout.write(`[agent exited code=${code} sig=${sig}]\n`);
  for (const res of pending.values()) res({ id: -1, error: { code: -1, message: "agent exited" } });
});

setTimeout(async () => {
  try {
    const caps = await request("runtime/capabilities");
    if (caps.error === undefined) {
      capabilitiesAnswered = true;
      process.stdout.write("[smoke] runtime/capabilities answered\n");
    } else {
      process.stdout.write(`[smoke] capabilities error frame: ${JSON.stringify(caps.error).slice(0, 300)}\n`);
    }
    if (opts.raw) {
      process.stdout.write("[smoke] sending raw frame\n");
      send(JSON.parse(opts.raw));
      await new Promise((r) => setTimeout(r, 4000));
    }
    if (opts.task) {
      const created = await request("session/create", {});
      const sid = created && created.result && (created.result.sessionId || created.result.id);
      process.stdout.write(`[smoke] session/create -> ${sid || JSON.stringify(created).slice(0, 300)}\n`);
      if (sid) {
        await request("session/send", { sessionId: sid, text: opts.task });
        // Reply frames (incl. tool-use events) stream as notifications until timeout.
        await new Promise((r) => setTimeout(r, Math.max(opts.timeout, 30) * 1000));
      }
    }
  } finally {
    child.kill("SIGTERM");
    setTimeout(() => process.exit(capabilitiesAnswered ? 0 : 3), 500).unref();
  }
}, 1500);
