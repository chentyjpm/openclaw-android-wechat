import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { OpenClawConfig } from "openclaw/plugin-sdk";
import type { ResolvedWeChatUiAccount } from "./accounts.js";
import { WeChatUiConfigSchema } from "./config-schema.js";
import { getWeChatUiRuntime } from "./runtime.js";

type WeChatUiRuntimeEnv = {
  log?: (message: string) => void;
  error?: (message: string) => void;
};

type WeChatUiCoreRuntime = ReturnType<typeof getWeChatUiRuntime>;

type ClientBridgeTarget = {
  account: ResolvedWeChatUiAccount;
  config: OpenClawConfig;
  runtime: WeChatUiRuntimeEnv;
  core: WeChatUiCoreRuntime;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
};

type ClientTask = {
  task_id: number;
  type: string;
  payload: Record<string, unknown>;
  created_at_ms: number;
};

type ClientQueueState = {
  serverBootId: string;
  nextTaskId: number;
  tasks: ClientTask[];
};

type InboundDedupState = {
  suppressUntilMs: number;
  recentOrder: Array<{ key: string; at: number }>;
  recentMap: Map<string, number>;
  suppressDropCount: number;
};

const clientBridgeTargets = new Set<ClientBridgeTarget>();
const queueByAccount = new Map<string, ClientQueueState>();
const inboundChainByAccount = new Map<string, Promise<void>>();
const inboundDedupByAccount = new Map<string, InboundDedupState>();
const MAX_TASKS = 2000;
const CLIENT_PULL_PATH = "/client/pull";
const CLIENT_PUSH_PATH = "/client/push";
const HELLO_SUPPRESS_MS = 5000;
const INBOUND_DEDUP_TTL_MS = 180_000;
const INBOUND_DEDUP_MAX = 2000;
const TASK_TTL_MS = 120_000;

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

function json(res: ServerResponse, status: number, payload: unknown) {
  res.statusCode = status;
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

function safeString(value: unknown): string {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}

function isAuthorized(req: IncomingMessage, secret: string): boolean {
  const authHeader = String(req.headers.authorization ?? "");
  const bearer = authHeader.toLowerCase().startsWith("bearer ")
    ? authHeader.slice("bearer ".length).trim()
    : "";
  const headerSecret = String(req.headers["x-wechatui-secret"] ?? "").trim();
  const token = bearer || headerSecret;
  return token === secret;
}

function selectSingleTarget(req: IncomingMessage): ClientBridgeTarget | null {
  const targets = Array.from(clientBridgeTargets);
  let matching: ClientBridgeTarget[] = [];
  try {
    matching = targets.filter((t) => {
      WeChatUiConfigSchema.parse((t.config.channels?.["wechatui"] ?? {}) as unknown);
      const secret = safeString(t.account.config.webhookSecret).trim();
      if (!secret) return true;
      return isAuthorized(req, secret);
    });
  } catch {
    return null;
  }
  if (matching.length === 0) return null;
  return matching[0];
}

function getQueueState(accountId: string): ClientQueueState {
  const existing = queueByAccount.get(accountId);
  if (existing) return existing;
  const created: ClientQueueState = {
    serverBootId: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`,
    nextTaskId: 1,
    tasks: [],
  };
  queueByAccount.set(accountId, created);
  return created;
}

function enqueueTaskForAccount(accountId: string, type: string, payload: Record<string, unknown>): ClientTask {
  const state = getQueueState(accountId);
  const task: ClientTask = {
    task_id: state.nextTaskId++,
    type,
    payload,
    created_at_ms: Date.now(),
  };
  state.tasks.push(task);
  if (state.tasks.length > MAX_TASKS) {
    state.tasks.splice(0, state.tasks.length - MAX_TASKS);
  }
  return task;
}

function enqueueTask(target: ClientBridgeTarget, type: string, payload: Record<string, unknown>): ClientTask {
  return enqueueTaskForAccount(target.account.accountId, type, payload);
}

function pruneExpiredTasks(state: ClientQueueState, now: number): void {
  const minTs = now - TASK_TTL_MS;
  if (state.tasks.length === 0) return;
  state.tasks = state.tasks.filter((t) => t.created_at_ms >= minTs);
}

function resetQueueState(accountId: string): ClientQueueState {
  const state = getQueueState(accountId);
  state.serverBootId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  state.nextTaskId = 1;
  state.tasks = [];
  return state;
}

function getInboundDedupState(accountId: string): InboundDedupState {
  const existing = inboundDedupByAccount.get(accountId);
  if (existing) return existing;
  const created: InboundDedupState = {
    suppressUntilMs: 0,
    recentOrder: [],
    recentMap: new Map<string, number>(),
    suppressDropCount: 0,
  };
  inboundDedupByAccount.set(accountId, created);
  return created;
}

function normalizeInboundKey(text: string): string {
  return text.trim().replace(/\s+/g, " ").toLowerCase();
}

function pruneInboundDedup(state: InboundDedupState, now: number): void {
  const minTs = now - INBOUND_DEDUP_TTL_MS;
  while (state.recentOrder.length > 0) {
    const head = state.recentOrder[0];
    if (!head) break;
    if (head.at >= minTs && state.recentOrder.length <= INBOUND_DEDUP_MAX) break;
    state.recentOrder.shift();
    const mapped = state.recentMap.get(head.key);
    if (mapped != null && mapped <= head.at) {
      state.recentMap.delete(head.key);
    }
  }
}

function shouldAcceptInboundText(params: {
  accountId: string;
  text: string;
  now: number;
  target: ClientBridgeTarget;
}): boolean {
  const { accountId, text, now, target } = params;
  const state = getInboundDedupState(accountId);

  if (now < state.suppressUntilMs) {
    state.suppressDropCount += 1;
    if (state.suppressDropCount === 1 || state.suppressDropCount % 20 === 0) {
      target.runtime.log?.(
        `[client-bridge] dropping startup backlog account=${accountId} dropped=${state.suppressDropCount}`,
      );
    }
    return false;
  }

  pruneInboundDedup(state, now);
  const key = normalizeInboundKey(text);
  const last = state.recentMap.get(key);
  if (last != null && now - last < INBOUND_DEDUP_TTL_MS) {
    return false;
  }

  state.recentMap.set(key, now);
  state.recentOrder.push({ key, at: now });
  if (state.recentOrder.length > INBOUND_DEDUP_MAX) {
    pruneInboundDedup(state, now);
  }
  return true;
}

function isHelloEnvelope(envelope: unknown): boolean {
  if (!envelope || typeof envelope !== "object" || Array.isArray(envelope)) return false;
  const env = envelope as Record<string, unknown>;
  return typeof env.hello === "object" && env.hello !== null;
}

function markClientSessionStarted(target: ClientBridgeTarget): void {
  const accountId = target.account.accountId;
  const queue = resetQueueState(accountId);
  const dedup = getInboundDedupState(accountId);
  dedup.suppressUntilMs = Date.now() + HELLO_SUPPRESS_MS;
  dedup.suppressDropCount = 0;
  dedup.recentMap.clear();
  dedup.recentOrder = [];
  target.runtime.log?.(
    `[client-bridge] client hello received account=${accountId}; queue reset boot=${queue.serverBootId}`,
  );
}

export function enqueueClientSendTextTask(params: {
  accountId: string;
  text: string;
  mode?: string;
  requestId?: string;
}): ClientTask {
  return enqueueTaskForAccount(params.accountId, "send_text", {
    request_id: params.requestId ?? randomUUID(),
    text: params.text,
    mode: params.mode ?? "openclaw",
  });
}

async function generateReplyTasks(params: {
  target: ClientBridgeTarget;
  text: string;
}): Promise<void> {
  const { target, text } = params;
  const { account, config, core } = target;
  const peerId = "android:tabscan";

  const route = core.channel.routing.resolveAgentRoute({
    cfg: config,
    channel: "wechatui",
    accountId: account.accountId,
    peer: { kind: "dm", id: peerId },
  });

  const ctxPayload = {
    Body: text,
    BodyForAgent: text,
    RawBody: text,
    CommandBody: text,
    BodyForCommands: text,
    From: peerId,
    To: peerId,
    SessionKey: route.sessionKey,
    AccountId: route.accountId,
    ChatType: "direct",
    ConversationLabel: peerId,
    SenderName: "android-tabscan",
    SenderId: peerId,
    Provider: "wechatui",
    Surface: "wechatui",
    Timestamp: Date.now(),
    OriginatingChannel: "wechatui",
    OriginatingTo: peerId,
    WasMentioned: true,
    CommandAuthorized: true,
  };

  const textLimit =
    account.config.textChunkLimit && account.config.textChunkLimit > 0 ? account.config.textChunkLimit : 1500;
  const outboundTexts: string[] = [];

  await core.channel.reply.dispatchReplyWithBufferedBlockDispatcher({
    ctx: ctxPayload,
    cfg: config,
    dispatcherOptions: {
      deliver: async (replyPayload) => {
        const replyText = safeString(replyPayload.text);
        if (replyText.trim()) {
          const chunks = core.channel.text.chunkMarkdownText(replyText, textLimit);
          if (!chunks.length) chunks.push(replyText);
          outboundTexts.push(...chunks);
        }
        const one = safeString(replyPayload.mediaUrl).trim();
        if (one) outboundTexts.push(`[media] ${one}`);
        const many = Array.isArray(replyPayload.mediaUrls) ? replyPayload.mediaUrls : [];
        for (const u of many) {
          const s = safeString(u).trim();
          if (s) outboundTexts.push(`[media] ${s}`);
        }
      },
    },
  });

  for (const outText of outboundTexts) {
    enqueueTask(target, "send_text", {
      request_id: randomUUID(),
      text: outText,
      mode: "tabscan",
    });
    target.statusSink?.({ lastOutboundAt: Date.now() });
  }

  target.runtime.log?.(
    `[client-bridge] generated reply tasks account=${target.account.accountId} count=${outboundTexts.length}`,
  );
}

function enqueueInboundWork(target: ClientBridgeTarget, text: string): void {
  const key = target.account.accountId;
  const prev = inboundChainByAccount.get(key) ?? Promise.resolve();
  const next = prev
    .catch(() => {
      // keep chain alive
    })
    .then(async () => {
      await generateReplyTasks({ target, text });
    })
    .catch((err) => {
      target.runtime.error?.(`[client-bridge] reply generation failed: ${String(err)}`);
    });
  inboundChainByAccount.set(key, next);
}

function extractMsgText(envelope: unknown): string {
  if (!envelope || typeof envelope !== "object" || Array.isArray(envelope)) return "";
  const env = envelope as Record<string, unknown>;
  const msg = env.msg;
  if (typeof msg === "string") return msg.trim();
  if (!msg || typeof msg !== "object" || Array.isArray(msg)) return "";
  return safeString((msg as Record<string, unknown>).text).trim();
}

export function registerClientBridgeTarget(target: ClientBridgeTarget): () => void {
  clientBridgeTargets.add(target);
  return () => {
    clientBridgeTargets.delete(target);
    const accountId = target.account.accountId;
    const stillExists = Array.from(clientBridgeTargets).some((t) => t.account.accountId === accountId);
    if (!stillExists) {
      queueByAccount.delete(accountId);
      inboundChainByAccount.delete(accountId);
      inboundDedupByAccount.delete(accountId);
    }
  };
}

export async function handleClientPullRequest(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (url.pathname !== CLIENT_PULL_PATH) return false;

  if (req.method !== "POST") {
    res.statusCode = 405;
    res.setHeader("Allow", "POST");
    res.end("Method Not Allowed");
    return true;
  }

  const body = await readJsonBody(req, 1024 * 1024);
  if (!body.ok) {
    json(res, body.error === "payload too large" ? 413 : 400, { error: body.error ?? "invalid payload" });
    return true;
  }
  const raw = body.value;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    json(res, 400, { error: "invalid payload" });
    return true;
  }

  const target = selectSingleTarget(req);
  if (!target) {
    json(res, 401, { error: "unauthorized" });
    return true;
  }

  const obj = raw as Record<string, unknown>;
  const afterId = parseNumber(obj.after_id ?? obj.afterId, 0);
  const limit = Math.max(1, Math.min(50, Math.floor(parseNumber(obj.limit, 10))));
  const state = getQueueState(target.account.accountId);
  pruneExpiredTasks(state, Date.now());
  const tasks = state.tasks
    .filter((t) => t.task_id > afterId)
    .slice(0, limit)
    .map((t) => ({ task_id: t.task_id, type: t.type, payload: t.payload }));
  json(res, 200, { server_boot_id: state.serverBootId, tasks });
  return true;
}

export async function handleClientPushRequest(req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (url.pathname !== CLIENT_PUSH_PATH) return false;

  if (req.method !== "POST") {
    res.statusCode = 405;
    res.setHeader("Allow", "POST");
    res.end("Method Not Allowed");
    return true;
  }

  const body = await readJsonBody(req, 8 * 1024 * 1024);
  if (!body.ok) {
    json(res, body.error === "payload too large" ? 413 : 400, { ok: false, error: body.error ?? "invalid payload" });
    return true;
  }
  const raw = body.value;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    json(res, 400, { ok: false, error: "invalid payload" });
    return true;
  }

  const target = selectSingleTarget(req);
  if (!target) {
    json(res, 401, { ok: false, error: "unauthorized" });
    return true;
  }

  const envelopes = (raw as Record<string, unknown>).envelopes;
  if (!Array.isArray(envelopes)) {
    json(res, 400, { ok: false, error: "missing envelopes" });
    return true;
  }

  json(res, 200, { ok: true });

  setImmediate(() => {
    if (envelopes.some((env) => isHelloEnvelope(env))) {
      markClientSessionStarted(target);
      return;
    }

    for (const envelope of envelopes) {
      const text = extractMsgText(envelope);
      if (!text) continue;
      const now = Date.now();
      if (!shouldAcceptInboundText({ accountId: target.account.accountId, text, now, target })) {
        continue;
      }
      target.statusSink?.({ lastInboundAt: Date.now() });
      enqueueInboundWork(target, text);
    }
  });

  return true;
}
