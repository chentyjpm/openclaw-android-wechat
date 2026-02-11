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

type OpenClawWxBridgeTarget = {
  account: ResolvedWeChatUiAccount;
  config: OpenClawConfig;
  runtime: WeChatUiRuntimeEnv;
  core: WeChatUiCoreRuntime;
  path: string;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
};

type Query = { type?: string; content?: string };
type UserInfo = { query_id?: string; groupname?: string; username?: string; query?: Query };

type Rsp = { code: number; state: string; text: string; references: string[] };
type QueryResponsePair = { req: Required<UserInfo>; rsp: Rsp };
type ChatResponse = { msg: string; msgCode: number; data: QueryResponsePair[] };

const DEFAULT_BRIDGE_PATH = "/openclawwx";
const bridgeTargets = new Map<string, OpenClawWxBridgeTarget[]>();

type PushPayload = {
  accountId?: string;
  query_id?: string;
  groupname?: string;
  username?: string;
  text?: string;
  code?: number;
  state?: string;
  references?: string[];
};

type SessionState = {
  inFlight: boolean;
  queue: QueryResponsePair[];
  waiters: Array<{
    resolve: (pairs: QueryResponsePair[]) => void;
    timeoutId: NodeJS.Timeout;
  }>;
  lastSeenAt: number;
};

const sessionStateByKey = new Map<string, SessionState>();

function normalizeWebhookPath(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return "/";
  const withSlash = trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  if (withSlash.length > 1 && withSlash.endsWith("/")) return withSlash.slice(0, -1);
  return withSlash;
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

function isAuthorized(req: IncomingMessage, secret: string): boolean {
  const authHeader = String(req.headers.authorization ?? "");
  const bearer = authHeader.toLowerCase().startsWith("bearer ")
    ? authHeader.slice("bearer ".length).trim()
    : "";
  const headerSecret = String(req.headers["x-openclawwx-secret"] ?? "").trim();
  const token = bearer || headerSecret;
  return token === secret;
}

function safeString(value: unknown): string {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}

function normalizeUserInfo(raw: UserInfo): Required<UserInfo> {
  const query_id = safeString(raw.query_id).trim();
  const groupname = safeString(raw.groupname).trim();
  const username = safeString(raw.username).trim();
  const type = safeString(raw.query?.type).trim();
  const content = safeString(raw.query?.content);
  return { query_id, groupname, username, query: { type, content } };
}

function sessionKey(accountId: string, queryId: string): string {
  return `${accountId}:${queryId}`;
}

function ensureSession(key: string): SessionState {
  const existing = sessionStateByKey.get(key);
  if (existing) {
    existing.lastSeenAt = Date.now();
    return existing;
  }
  const state: SessionState = { inFlight: false, queue: [], waiters: [], lastSeenAt: Date.now() };
  sessionStateByKey.set(key, state);
  return state;
}

async function generateReply(params: {
  target: OpenClawWxBridgeTarget;
  req: Required<UserInfo>;
}): Promise<void> {
  const { target, req } = params;
  const { account, config, core } = target;

  const queryId = req.query_id || req.groupname || req.username || "unknown";
  const peerId = `android:${queryId || "unknown"}`;
  const route = core.channel.routing.resolveAgentRoute({
    cfg: config,
    channel: "wechatui",
    accountId: account.accountId,
    peer: { kind: "dm", id: peerId },
  });

  const body = req.query.content ?? "";

  const ctxPayload = {
    Body: body,
    BodyForAgent: body,
    RawBody: body,
    CommandBody: body,
    BodyForCommands: body,
    From: `android:${req.username || req.groupname || "wechat"}`,
    To: `android:${req.groupname || req.query_id || "wechat"}`,
    SessionKey: route.sessionKey,
    AccountId: route.accountId,
    ChatType: "direct",
    ConversationLabel: req.groupname || req.query_id || peerId,
    SenderName: req.username || req.groupname || "wechat",
    SenderId: req.username || req.groupname || peerId,
    Provider: "wechatui",
    Surface: "wechatui",
    Timestamp: Date.now(),
    OriginatingChannel: "wechatui",
    OriginatingTo: peerId,
    WasMentioned: true,
    CommandAuthorized: true,
  };

  let combinedText = "";
  const mediaUrls: string[] = [];

  await core.channel.reply.dispatchReplyWithBufferedBlockDispatcher({
    ctx: ctxPayload,
    cfg: config,
    dispatcherOptions: {
      deliver: async (replyPayload) => {
        const replyText = safeString(replyPayload.text);
        if (replyText) {
          combinedText = combinedText ? `${combinedText}\n${replyText}` : replyText;
        }
        const one = safeString(replyPayload.mediaUrl).trim();
        if (one) mediaUrls.push(one);
        const many = Array.isArray(replyPayload.mediaUrls) ? replyPayload.mediaUrls : [];
        for (const u of many) {
          const s = safeString(u).trim();
          if (s) mediaUrls.push(s);
        }
      },
    },
  });

  const suffix = mediaUrls.length ? `\n\n${mediaUrls.map((u) => `[media] ${u}`).join("\n")}` : "";
  const text = `${combinedText}${suffix}`.trim();

  const key = sessionKey(account.accountId, queryId);
  const state = ensureSession(key);
  state.queue.push({
    req,
    rsp: { code: 0, state: "ok", text: text || "(empty reply)", references: [] },
  });
}

function tryStartGeneration(target: OpenClawWxBridgeTarget, req: Required<UserInfo>) {
  const accountId = target.account.accountId;
  const queryId = req.query_id || req.groupname || req.username || "unknown";
  const key = sessionKey(accountId, queryId);
  const state = ensureSession(key);
  if (state.inFlight) return;
  state.inFlight = true;

  void (async () => {
    try {
      await generateReply({ target, req });
    } catch (err) {
      state.queue.push({
        req,
        rsp: {
          code: 1,
          state: "error",
          text: err instanceof Error ? err.message : String(err),
          references: [],
        },
      });
      target.runtime.error?.(`[openclawwx-bridge] reply generation failed: ${String(err)}`);
    } finally {
      state.inFlight = false;
      state.lastSeenAt = Date.now();
      target.statusSink?.({ lastOutboundAt: Date.now() });
    }
  })();
}

function drainQueued(target: OpenClawWxBridgeTarget, req: Required<UserInfo>): QueryResponsePair[] {
  const accountId = target.account.accountId;
  const queryId = req.query_id || req.groupname || req.username || "unknown";
  const key = sessionKey(accountId, queryId);
  const state = ensureSession(key);
  const out = state.queue.slice(0);
  state.queue.length = 0;
  return out;
}

function json(res: ServerResponse, status: number, payload: unknown) {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.end(JSON.stringify(payload));
}

export function resolveOpenClawWxBridgePathFromConfig(config?: { androidWebhookPath?: string | null }): string {
  const raw = safeString(config?.androidWebhookPath).trim();
  return raw ? normalizeWebhookPath(raw) : DEFAULT_BRIDGE_PATH;
}

export function resolveOpenClawWxBridgePullPathFromConfig(config?: { androidWebhookPath?: string | null }): string {
  const base = resolveOpenClawWxBridgePathFromConfig(config);
  return normalizeWebhookPath(`${base}/pull`);
}

export function resolveOpenClawWxBridgePushPathFromConfig(config?: { androidWebhookPath?: string | null }): string {
  const base = resolveOpenClawWxBridgePathFromConfig(config);
  return normalizeWebhookPath(`${base}/push`);
}

export function registerOpenClawWxBridgeTarget(target: OpenClawWxBridgeTarget): () => void {
  const key = normalizeWebhookPath(target.path);
  const normalizedTarget = { ...target, path: key };
  const existing = bridgeTargets.get(key) ?? [];
  const next = [...existing, normalizedTarget];
  bridgeTargets.set(key, next);
  return () => {
    const updated = (bridgeTargets.get(key) ?? []).filter((entry) => entry !== normalizedTarget);
    if (updated.length > 0) bridgeTargets.set(key, updated);
    else bridgeTargets.delete(key);
  };
}

function enqueuePair(target: OpenClawWxBridgeTarget, pair: QueryResponsePair) {
  const accountId = target.account.accountId;
  const queryId = pair.req.query_id || pair.req.groupname || pair.req.username || "unknown";
  const key = sessionKey(accountId, queryId);
  const state = ensureSession(key);
  state.queue.push(pair);
  state.lastSeenAt = Date.now();

  if (state.waiters.length > 0) {
    const waiter = state.waiters.shift();
    if (waiter) {
      clearTimeout(waiter.timeoutId);
      const out = state.queue.slice(0);
      state.queue.length = 0;
      waiter.resolve(out);
    }
  }
}

function selectSingleTarget(targets: OpenClawWxBridgeTarget[], req: IncomingMessage): OpenClawWxBridgeTarget | null {
  let matching: OpenClawWxBridgeTarget[] = [];
  try {
    matching = targets.filter((t) => {
      WeChatUiConfigSchema.parse((t.config.channels?.["wechatui"] ?? {}) as unknown);
      const secret = safeString(t.account.config.androidWebhookSecret).trim();
      if (!secret) return true;
      return isAuthorized(req, secret);
    });
  } catch {
    return null;
  }

  if (matching.length === 0) return null;
  // If multiple accounts share the same path, pick the first match to avoid duplicate replies.
  return matching[0];
}

async function waitForQueuedPairs(params: {
  target: OpenClawWxBridgeTarget;
  req: Required<UserInfo>;
  waitMs: number;
  httpReq: IncomingMessage;
}): Promise<QueryResponsePair[]> {
  const { target, req, waitMs, httpReq } = params;

  const accountId = target.account.accountId;
  const queryId = req.query_id || req.groupname || req.username || "unknown";
  const key = sessionKey(accountId, queryId);
  const state = ensureSession(key);

  if (state.queue.length > 0) {
    const out = state.queue.slice(0);
    state.queue.length = 0;
    return out;
  }

  const wait = Math.max(0, Math.min(waitMs, 120_000));

  return await new Promise<QueryResponsePair[]>((resolve) => {
    const timeoutId = setTimeout(() => {
      // Remove this waiter if it still exists.
      state.waiters = state.waiters.filter((w) => w.timeoutId !== timeoutId);
      resolve([]);
    }, wait);

    const waiter = { resolve, timeoutId };
    state.waiters.push(waiter);

    httpReq.on("close", () => {
      clearTimeout(timeoutId);
      state.waiters = state.waiters.filter((w) => w.timeoutId !== timeoutId);
      resolve([]);
    });
  });
}

export async function handleOpenClawWxPullRequest(
  req: IncomingMessage,
  res: ServerResponse,
): Promise<boolean> {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = normalizeWebhookPath(url.pathname);
  const targets = bridgeTargets.get(path);
  if (!targets || targets.length === 0) {
    return false;
  }

  if (req.method !== "POST") {
    res.statusCode = 405;
    res.setHeader("Allow", "POST");
    res.end("Method Not Allowed");
    return true;
  }

  const body = await readJsonBody(req, 1024 * 1024);
  if (!body.ok) {
    json(res, body.error === "payload too large" ? 413 : 400, { msg: body.error ?? "invalid payload", msgCode: 1, data: [] });
    return true;
  }

  const raw = body.value;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    json(res, 400, { msg: "invalid payload", msgCode: 1, data: [] });
    return true;
  }

  const parsed = normalizeUserInfo(raw as UserInfo);
  const qType = safeString(parsed.query.type).toLowerCase();
  const effectiveType = qType || "poll";

  const target = selectSingleTarget(targets, req);
  if (!target) {
    json(res, 401, { msg: "unauthorized", msgCode: 1, data: [] });
    return true;
  }

  target.statusSink?.({ lastInboundAt: Date.now() });

  if (effectiveType === "text") {
    const text = safeString(parsed.query.content);
    if (!text.trim()) {
      json(res, 200, { msg: "empty text", msgCode: 0, data: [] } satisfies ChatResponse);
      return true;
    }
    tryStartGeneration(target, parsed);
    json(res, 200, { msg: "ok", msgCode: 0, data: [] } satisfies ChatResponse);
    return true;
  }

  if (effectiveType === "poll") {
    let data = drainQueued(target, parsed);
    if (data.length === 0) {
      const waitMs = typeof target.account.config.androidLongPollMs === "number" ? target.account.config.androidLongPollMs : 0;
      if (waitMs > 0) {
        data = await waitForQueuedPairs({ target, req: parsed, waitMs, httpReq: req });
      }
    }
    json(res, 200, { msg: "ok", msgCode: 0, data } satisfies ChatResponse);
    return true;
  }

  json(res, 400, { msg: `unsupported query.type=${qType}`, msgCode: 1, data: [] });
  return true;
}

export async function handleOpenClawWxPushRequest(
  req: IncomingMessage,
  res: ServerResponse,
): Promise<boolean> {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = normalizeWebhookPath(url.pathname);
  const targets = bridgeTargets.get(path);
  if (!targets || targets.length === 0) {
    return false;
  }

  if (req.method !== "POST") {
    res.statusCode = 405;
    res.setHeader("Allow", "POST");
    res.end("Method Not Allowed");
    return true;
  }

  const body = await readJsonBody(req, 1024 * 1024);
  if (!body.ok) {
    json(res, body.error === "payload too large" ? 413 : 400, { ok: false, error: body.error ?? "invalid payload" });
    return true;
  }

  const raw = body.value;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    json(res, 400, { ok: false, error: "invalid payload" });
    return true;
  }

  const payload = raw as PushPayload;
  const target = selectSingleTarget(targets, req);
  if (!target) {
    json(res, 401, { ok: false, error: "unauthorized" });
    return true;
  }

  const text = safeString(payload.text);
  if (!text.trim()) {
    json(res, 200, { ok: true, queued: false });
    return true;
  }

  const reqInfo = normalizeUserInfo({
    query_id: safeString(payload.query_id).trim(),
    groupname: safeString(payload.groupname).trim(),
    username: safeString(payload.username).trim(),
    query: { type: "push", content: "" },
  });

  const code = typeof payload.code === "number" ? payload.code : 0;
  const state = safeString(payload.state).trim() || (code === 0 ? "ok" : "error");
  const references = Array.isArray(payload.references) ? payload.references.map((v) => safeString(v)).filter(Boolean) : [];

  enqueuePair(target, {
    req: reqInfo,
    rsp: { code, state, text, references },
  });

  target.statusSink?.({ lastOutboundAt: Date.now() });
  json(res, 200, { ok: true, queued: true });
  return true;
}

