#!/usr/bin/env node
import http from "node:http";
import { randomUUID } from "node:crypto";
import readline from "node:readline";

const host = String(process.env.HOST ?? "0.0.0.0").trim() || "0.0.0.0";
const port = Number.parseInt(String(process.env.PORT ?? "18790"), 10) || 18790;

const serverBootId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
let nextTaskId = 1;
const tasks = [];
const MAX_TASKS = 2000;

let lastPushAt = 0;
let lastWindowState = null;

function readJson(req, maxBytes = 8 * 1024 * 1024) {
  const chunks = [];
  let total = 0;
  return new Promise((resolve) => {
    let resolved = false;
    const done = (value) => {
      if (resolved) return;
      resolved = true;
      req.removeAllListeners();
      resolve(value);
    };
    req.on("data", (chunk) => {
      total += chunk.length;
      if (total > maxBytes) {
        done({ ok: false, error: "payload too large" });
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      try {
        const raw = Buffer.concat(chunks).toString("utf8");
        if (!raw.trim()) return done({ ok: false, error: "empty payload" });
        return done({ ok: true, value: JSON.parse(raw) });
      } catch (err) {
        return done({ ok: false, error: err instanceof Error ? err.message : String(err) });
      }
    });
    req.on("error", (err) => done({ ok: false, error: err instanceof Error ? err.message : String(err) }));
  });
}

function writeJson(res, statusCode, payload) {
  res.statusCode = statusCode;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.end(JSON.stringify(payload));
}

function parseNumber(value, fallback) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return fallback;
}

function enqueueTask(type, payload) {
  const task = { task_id: nextTaskId++, type, payload };
  tasks.push(task);
  if (tasks.length > MAX_TASKS) tasks.splice(0, tasks.length - MAX_TASKS);
  return task;
}

function enqueueTabScanText(text) {
  const requestId = randomUUID();
  const task = enqueueTask("send_text", {
    request_id: requestId,
    text,
    mode: "tabscan",
  });
  return { requestId, taskId: task.task_id };
}

function handleClientPull(body, res) {
  const obj = body && typeof body === "object" && !Array.isArray(body) ? body : {};
  const afterId = parseNumber(obj.after_id ?? obj.afterId, 0);
  const limit = Math.max(1, Math.min(50, Math.floor(parseNumber(obj.limit, 10))));
  const available = tasks.filter((t) => t.task_id > afterId).slice(0, limit);
  writeJson(res, 200, { server_boot_id: serverBootId, tasks: available });
}

function logAck(ack) {
  if (!ack || typeof ack !== "object" || Array.isArray(ack)) return;
  const requestId = String(ack.request_id ?? "").trim();
  const ok = Boolean(ack.ok);
  const stage = String(ack.stage ?? "").trim();
  const error = String(ack.error ?? "").trim();
  if (requestId) {
    console.log(`[dev-server] ack request_id=${requestId} ok=${ok} stage=${stage} error=${error}`);
  }
}

function logWindowState(ws) {
  if (!ws || typeof ws !== "object" || Array.isArray(ws)) return;
  const pkg = String(ws.pkg ?? "").trim();
  const cls = String(ws.cls ?? "").trim();
  const ts = parseNumber(ws.ts_ms, Date.now());
  const wechat = ws.wechat && typeof ws.wechat === "object" && !Array.isArray(ws.wechat) ? ws.wechat : null;
  const screen = wechat ? String(wechat.screen ?? "").trim() : "";
  const title = wechat ? String(wechat.title ?? "").trim() : "";
  console.log(`[dev-server] window_state ts=${ts} pkg=${pkg} cls=${cls} wechat.screen=${screen} wechat.title=${title}`);
}

function logTabScanDelta(delta) {
  if (!delta || typeof delta !== "object" || Array.isArray(delta)) return;
  const text = String(delta.text ?? "");
  console.log(`[dev-server] msg text=${JSON.stringify(text)}`);
}

function handleClientPush(body, res) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    writeJson(res, 400, { ok: false, error: "invalid payload" });
    return;
  }
  const envs = body.envelopes;
  if (!Array.isArray(envs)) {
    writeJson(res, 400, { ok: false, error: "missing envelopes" });
    return;
  }

  lastPushAt = Date.now();
  writeJson(res, 200, { ok: true });

  // async log
  setImmediate(() => {
    for (const env of envs) {
      if (!env || typeof env !== "object" || Array.isArray(env)) continue;
      if (env.ack) logAck(env.ack);
      if (env.window_state) {
        lastWindowState = env.window_state;
        logWindowState(env.window_state);
      }
      if (env.msg) logTabScanDelta(env.msg);
    }
  });
}

function handleDevEnqueueSendText(body, res) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    writeJson(res, 400, { ok: false, error: "invalid payload" });
    return;
  }
  const targetTitle = String(body.target_title ?? body.targetTitle ?? "").trim();
  const text = String(body.text ?? "");
  if (!targetTitle) return writeJson(res, 400, { ok: false, error: "missing target_title" });
  if (!text.trim()) return writeJson(res, 400, { ok: false, error: "missing text" });

  const requestId = randomUUID();
  const mode = String(body.mode ?? "text").trim() || "text";
  const payload = {
    request_id: requestId,
    target_title: targetTitle,
    text,
    mode,
  };
  const imageUrl = String(body.image_url ?? body.imageUrl ?? "").trim();
  if (imageUrl) payload.image_url = imageUrl;
  const mime = String(body.image_mime_type ?? body.imageMimeType ?? "").trim();
  if (mime) payload.image_mime_type = mime;

  const task = enqueueTask("send_text", payload);
  writeJson(res, 200, { ok: true, request_id: requestId, task_id: task.task_id });
}

function handleDevEnqueueMsg(body, res) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    writeJson(res, 400, { ok: false, error: "invalid payload" });
    return;
  }
  const text = String(body.text ?? "");
  if (!text.trim()) return writeJson(res, 400, { ok: false, error: "missing text" });
  const result = enqueueTabScanText(text);
  writeJson(res, 200, { ok: true, request_id: result.requestId, task_id: result.taskId, mode: "tabscan" });
}

function handleDevState(_req, res) {
  writeJson(res, 200, {
    ok: true,
    server_boot_id: serverBootId,
    last_push_at: lastPushAt || null,
    next_task_id: nextTaskId,
    queued_tasks: tasks.length,
    last_window_state: lastWindowState,
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");

  if (req.method === "GET" && url.pathname === "/dev/state") {
    return handleDevState(req, res);
  }

  if (req.method !== "POST") {
    res.statusCode = 404;
    res.end("Not Found");
    return;
  }

  const body = await readJson(req);
  if (!body.ok) {
    res.statusCode = body.error === "payload too large" ? 413 : 400;
    res.end(body.error ?? "invalid payload");
    return;
  }

  if (url.pathname === "/client/pull") return handleClientPull(body.value, res);
  if (url.pathname === "/client/push") return handleClientPush(body.value, res);
  if (url.pathname === "/dev/enqueue/send_text") return handleDevEnqueueSendText(body.value, res);
  if (url.pathname === "/dev/enqueue/msg") return handleDevEnqueueMsg(body.value, res);

  res.statusCode = 404;
  res.end("Not Found");
});

server.listen(port, host, () => {
  console.log(`[dev-server] listening on http://${host}:${port}`);
  console.log("[dev-server] endpoints: POST /client/pull , POST /client/push");
  console.log("[dev-server] dev endpoints: POST /dev/enqueue/send_text , POST /dev/enqueue/msg , GET /dev/state");
  console.log("[dev-server] stdin: type a message then press Enter to enqueue tabscan send_text");
  if (process.stdin.isTTY) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: "msg> " });
    rl.prompt();
    rl.on("line", (line) => {
      const text = String(line ?? "").trim();
      if (!text) {
        rl.prompt();
        return;
      }
      const result = enqueueTabScanText(text);
      console.log(`[dev-server] enqueued tabscan message task_id=${result.taskId} request_id=${result.requestId}`);
      rl.prompt();
    });
  }
});

