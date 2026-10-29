#!/usr/bin/env node
// Offline mock model provider (verification kit): OpenAI-chat-completions, scripted.
//
// Drives the REAL ZCode agent loop without any external model service:
//   request with no tool result yet        -> tool_call Write  (real file)
//   result of call_1 (Write)               -> tool_call Bash   (real shell marker)
//   result of call_2 (Bash echo)           -> tool_call Bash   (git init/commit)
//   result of call_3 (Bash git)            -> final text with sentinel VERIFY-MOCK-DONE
//   any tool error or unknown tool_call id -> final text quoting the error, so the
//                                             turn still ends and evidence lands
//                                             in the protocol log (verify stage
//                                             will FAIL on the missing effect).
//
// Every request body is appended to the log file as JSON lines (evidence).
// Base URL used by the agent: http://10.0.2.2:9999/v1 (emulator host alias).
//
// Usage: node verify-mock-provider.cjs --port 9999 --log /tmp/verify-mock.log

"use strict";

const http = require("node:http");
const fs = require("node:fs");

function arg(name, dflt) {
  const i = process.argv.indexOf("--" + name);
  return i > 0 ? process.argv[i + 1] : dflt;
}

const PORT = Number(arg("port", 9999));
const LOG = arg("log", "/tmp/verify-mock.log");
const WS = arg("workspace", "/tmp/verify-ws");
const SENTINEL = "VERIFY-MOCK-DONE";

const logStream = fs.createWriteStream(LOG, { flags: "a" });
let reqCount = 0;

function logEvent(obj) {
  logStream.write(JSON.stringify({ ts: new Date().toISOString(), ...obj }) + "\n");
}

function sse(res, chunks, finish) {
  res.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
  });
  let n = 0;
  const id = "chatcmpl-verify-" + Date.now();
  const send = (data, fin) => {
    n += 1;
    const payload = {
      id,
      object: "chat.completion.chunk",
      created: Math.floor(Date.now() / 1000),
      model: "mock-model",
      choices: [{ index: 0, delta: data, finish_reason: fin ?? null }],
    };
    res.write("data: " + JSON.stringify(payload) + "\n\n");
  };
  for (const c of chunks) send(c);
  send({}, finish);
  res.write("data: [DONE]\n\n");
  res.end();
}

function toolCallChunk(id, name, argsObj) {
  return {
    role: "assistant",
    tool_calls: [
      {
        index: 0,
        id,
        type: "function",
        function: { name, arguments: JSON.stringify(argsObj) },
      },
    ],
  };
}

function textChunk(text) {
  return { role: "assistant", content: text };
}

function lastToolCallId(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m && m.role === "tool" && m.tool_call_id) return String(m.tool_call_id);
  }
  return null;
}

function lastToolContent(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m && m.role === "tool") return typeof m.content === "string" ? m.content : JSON.stringify(m.content);
  }
  return "";
}

function scriptedResponse(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const tid = lastToolCallId(messages);
  const tcontent = lastToolContent(messages);
  if (!tid) {
    logEvent({ req: reqCount, action: "write" });
    return { chunks: [toolCallChunk("call_1", "Write", {
      file_path: WS + "/hello.txt",
      content: "verify mock write: hello from Bionic\n",
    })], finish: "tool_calls" };
  }
  if (tid === "call_1") {
    logEvent({ req: reqCount, action: "bash-marker", toolResult: tcontent.slice(0, 200) });
    return { chunks: [toolCallChunk("call_2", "Bash", {
      command: "echo VERIFY-BASH-OK > " + WS + "/bash-marker.txt && cat " + WS + "/hello.txt",
    })], finish: "tool_calls" };
  }
  if (tid === "call_2") {
    logEvent({ req: reqCount, action: "bash-git", toolResult: tcontent.slice(0, 200) });
    return { chunks: [toolCallChunk("call_3", "Bash", {
      command: "cd " + WS + " && git init -q && git add -A && git -c user.email=verify@localhost -c user.name=verify commit -q -m 'VERIFY-MOCK-COMMIT' && git log --oneline | head -1",
    })], finish: "tool_calls" };
  }
  if (tid === "call_3") {
    logEvent({ req: reqCount, action: "final", toolResult: tcontent.slice(0, 200) });
    return { chunks: [textChunk(
      SENTINEL + " — all three tool steps dispatched (Write/Bash/Bash git)."
    )], finish: "stop" };
  }
  // Tool error or unexpected id: end the turn with the error as evidence.
  logEvent({ req: reqCount, action: "final-on-error", tid, toolResult: tcontent.slice(0, 400) });
  return { chunks: [textChunk(
    SENTINEL + " (with errors) — last tool_call id " + tid + " result: " + tcontent.slice(0, 200)
  )], finish: "stop" };
}

const server = http.createServer((req, res) => {
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    if (req.method === "GET" && req.url === "/healthz") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, requests: reqCount }));
      return;
    }
    let body = {};
    try { body = JSON.parse(raw || "{}"); } catch { body = {}; }
    reqCount += 1;
    logEvent({
      req: reqCount,
      url: req.url,
      stream: body.stream === true,
      model: body.model,
      messageRoles: (body.messages || []).map((m) => m.role),
      hasTools: Array.isArray(body.tools) && body.tools.length > 0,
    });
    const scripted = scriptedResponse(body);
    if (body.stream === true) {
      sse(res, scripted.chunks, scripted.finish);
    } else {
      // Non-streaming fallback: assemble a single completion.
      const toolCalls = scripted.chunks[0]?.tool_calls;
      const text = scripted.chunks[0]?.content;
      const msg = toolCalls
        ? { role: "assistant", tool_calls: toolCalls.map((t, i) => ({
            id: t.id, type: "function",
            function: { name: t.function.name, arguments: t.function.arguments },
          })) }
        : { role: "assistant", content: text };
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({
        id: "chatcmpl-verify-" + Date.now(),
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model: "mock-model",
        choices: [{ index: 0, message: msg, finish_reason: scripted.finish }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }));
    }
  });
});

server.listen(PORT, "0.0.0.0", () => {
  logEvent({ event: "listening", port: PORT });
  console.log(`[mock-provider] listening on :${PORT}, log=${LOG}`);
});
