import type { IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import type { ChannelLogSink, OpenClawConfig } from "openclaw/plugin-sdk";
import type { ResolvedWeChatUiAccount } from "./accounts.js";
import type { InboundPayload, WeChatUiRuntimeEnv } from "./inbound.js";
import { processWeChatUiInboundMessage } from "./inbound.js";

type DeviceHubContext = {
  account: ResolvedWeChatUiAccount;
  cfg: OpenClawConfig;
  runtime: WeChatUiRuntimeEnv;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
  log?: ChannelLogSink;
};

type JsonObject = Record<string, unknown>;

type ServerTask = { task_id: number; type: string; payload: JsonObject };

const contexts: DeviceHubContext[] = [];

const serverBootId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
let nextTaskId = 1;
const tasks: ServerTask[] = [];
const MAX_TASKS = 2000;

const recentInboundKeys: string[] = [];
const recentInboundSet = new Set<string>();
const MAX_RECENT_INBOUND = 5000;

function readPrimaryContext(): DeviceHubContext | null {
  if (contexts.length === 0) return null;
  return contexts[0] ?? null;
}

export function registerWeChatUiDeviceHubContext(ctx: DeviceHubContext): () => void {
  contexts.push(ctx);
  if (contexts.length > 1) {
    ctx.log?.warn?.(
      `[${ctx.account.accountId}] [wechatui] multiple accounts started; device hub will use the first one for /client/pull and /client/push`,
    );
  }
  return () => {
    const idx = contexts.indexOf(ctx);
    if (idx >= 0) contexts.splice(idx, 1);
  };
}

function readJsonBody(req: IncomingMessage, maxBytes: number) {
  const chunks: Buffer[] = [];
  let total = 0;
  return new Promise<{ ok: boolean; value?: unknown; error?: string }>((resolve) => {
    let resolved = false;
    const done = (value: { ok: boolean; value?: unknown; error?: string }) => {
      if (resolved) return;
      resolved = true;
      req.removeAllListeners();
      resolve(value);
    };
    req.on("data", (chunk: Buffer) => {
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
        return done({ ok: true, value: JSON.parse(raw) as unknown });
      } catch (err) {
        return done({ ok: false, error: err instanceof Error ? err.message : String(err) });
      }
    });
    req.on("error", (err) => done({ ok: false, error: err instanceof Error ? err.message : String(err) }));
  });
}

function writeJson(res: ServerResponse, statusCode: number, payload: unknown) {
  res.statusCode = statusCode;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.end(JSON.stringify(payload));
}

function parseNumber(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return fallback;
}

export function enqueueWeChatUiSendTextTask(params: { targetTitle: string; text: string }): string {
  const requestId = randomUUID();
  enqueueTask("send_text", {
    request_id: requestId,
    target_title: params.targetTitle,
    text: params.text,
    mode: "text",
  });
  return requestId;
}

export function enqueueWeChatUiSendMediaTask(params: { targetTitle: string; text: string; mediaUrl: string }): string {
  const requestId = randomUUID();
  enqueueTask("send_text", {
    request_id: requestId,
    target_title: params.targetTitle,
    text: params.text,
    mode: "image",
    image_url: params.mediaUrl,
    image_mime_type: "",
  });
  return requestId;
}

function enqueueTask(type: string, payload: JsonObject): number {
  const task: ServerTask = { task_id: nextTaskId++, type, payload };
  tasks.push(task);
  if (tasks.length > MAX_TASKS) tasks.splice(0, tasks.length - MAX_TASKS);
  return task.task_id;
}

function addRecentInboundKey(key: string): boolean {
  if (recentInboundSet.has(key)) return false;
  recentInboundSet.add(key);
  recentInboundKeys.push(key);
  if (recentInboundKeys.length > MAX_RECENT_INBOUND) {
    const drop = recentInboundKeys.splice(0, recentInboundKeys.length - MAX_RECENT_INBOUND);
    for (const k of drop) recentInboundSet.delete(k);
  }
  return true;
}

async function processWindowStateAsInbound(ctx: DeviceHubContext, windowState: unknown) {
  if (!windowState || typeof windowState !== "object" || Array.isArray(windowState)) return;
  const obj = windowState as JsonObject;
  const tsMs = parseNumber(obj.ts_ms, Date.now());
  const wechat = obj.wechat;
  if (!wechat || typeof wechat !== "object" || Array.isArray(wechat)) return;
  const w = wechat as JsonObject;
  const messages = w.messages;
  if (!messages || typeof messages !== "object" || Array.isArray(messages)) return;
  const items = (messages as JsonObject).items;
  if (!Array.isArray(items)) return;

  for (const it of items) {
    if (!it || typeof it !== "object" || Array.isArray(it)) continue;
    const m = it as JsonObject;
    const sender = String(m.sender ?? "").trim();
    const text = String(m.text ?? "");
    const seq = parseNumber(m.sequence, 0);
    const msgId = String(m.msg_id ?? "").trim();
    const delivered = Boolean(m.delivered);

    // Best-effort: treat "delivered=true" as outbound/echo; ignore it.
    if (delivered) continue;
    if (!sender || !text.trim()) continue;

    const key = msgId || `${sender}::${seq}::${text}`;
    if (!addRecentInboundKey(key)) continue;

    const payload: InboundPayload = { from: sender, text, timestamp: tsMs, fromMe: false };
    await processWeChatUiInboundMessage({
      payload,
      account: ctx.account,
      cfg: ctx.cfg,
      runtime: ctx.runtime,
      statusSink: ctx.statusSink,
      log: ctx.log,
      deliverText: async ({ to, text }) => {
        enqueueWeChatUiSendTextTask({ targetTitle: to, text });
      },
      deliverMedia: async ({ to, text, mediaUrl }) => {
        enqueueWeChatUiSendMediaTask({ targetTitle: to, text, mediaUrl });
      },
    });
  }
}

function handlePush(ctx: DeviceHubContext, body: unknown, res: ServerResponse) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    writeJson(res, 400, { ok: false, error: "invalid payload" });
    return;
  }
  const envs = (body as JsonObject).envelopes;
  if (!Array.isArray(envs)) {
    writeJson(res, 400, { ok: false, error: "missing envelopes" });
    return;
  }

  writeJson(res, 200, { ok: true });

  setImmediate(() => {
    void Promise.allSettled(
      envs.map(async (env) => {
        try {
          if (!env || typeof env !== "object" || Array.isArray(env)) return;
          const e = env as JsonObject;
          if (e.ack && typeof e.ack === "object" && !Array.isArray(e.ack)) {
            const ack = e.ack as JsonObject;
            const requestId = String(ack.request_id ?? "").trim();
            const ok = Boolean(ack.ok);
            const stage = String(ack.stage ?? "").trim();
            const error = String(ack.error ?? "").trim();
            if (requestId) {
              ctx.log?.info?.(
                `[${ctx.account.accountId}] [wechatui] ack request_id=${requestId} ok=${ok} stage=${stage} error=${error}`,
              );
            }
          }
          if (e.window_state) {
            await processWindowStateAsInbound(ctx, e.window_state);
          }
        } catch (err) {
          ctx.runtime.error?.(`[wechatui] push envelope failed: ${String(err)}`);
        }
      }),
    );
  });
}

function handlePull(body: unknown, res: ServerResponse) {
  const obj = body && typeof body === "object" && !Array.isArray(body) ? (body as JsonObject) : {};
  const afterId = parseNumber(obj.after_id ?? obj.afterId, 0);
  const limit = Math.max(1, Math.min(50, Math.floor(parseNumber(obj.limit, 10))));

  const available = tasks.filter((t) => t.task_id > afterId).slice(0, limit);
  writeJson(res, 200, { server_boot_id: serverBootId, tasks: available });
}

export async function handleWeChatUiDeviceRequest(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = url.pathname;
  if (path !== "/client/pull" && path !== "/client/push") {
    return false;
  }

  const ctx = readPrimaryContext();
  if (!ctx) {
    writeJson(res, 503, { ok: false, error: "wechatui device hub not ready (no account started)" });
    return true;
  }

  if (req.method !== "POST") {
    res.statusCode = 405;
    res.setHeader("Allow", "POST");
    res.end("Method Not Allowed");
    return true;
  }

  const body = await readJsonBody(req, 8 * 1024 * 1024);
  if (!body.ok) {
    res.statusCode = body.error === "payload too large" ? 413 : 400;
    res.end(body.error ?? "invalid payload");
    return true;
  }

  if (path === "/client/push") {
    handlePush(ctx, body.value, res);
    return true;
  }
  handlePull(body.value, res);
  return true;
}
