import type { IncomingMessage, ServerResponse } from "node:http";
import type { ChannelLogSink, OpenClawConfig } from "openclaw/plugin-sdk";
import type { ResolvedWeChatUiAccount } from "./accounts.js";
import { resolveWeChatUiAccount } from "./accounts.js";
import { WeChatUiConfigSchema } from "./config-schema.js";
import { getWeChatUiWsClient } from "./ws-client.js";
import { sendWeChatUiMedia, sendWeChatUiText } from "./send.js";
import type { InboundPayload, WeChatUiRuntimeEnv } from "./inbound.js";
import { processWeChatUiInboundMessage } from "./inbound.js";
import { registerWeChatUiDeviceHubContext } from "./device-hub.js";

type WebhookTarget = {
  account: ResolvedWeChatUiAccount;
  config: OpenClawConfig;
  runtime: WeChatUiRuntimeEnv;
  path: string;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
  log?: ChannelLogSink;
};

const DEFAULT_WEBHOOK_PATH = "/wechatui";
const webhookTargets = new Map<string, WebhookTarget[]>();

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

export function resolveWebhookPathFromConfig(config?: { webhookPath?: string | null }): string {
  const raw = config?.webhookPath?.trim();
  return raw ? normalizeWebhookPath(raw) : DEFAULT_WEBHOOK_PATH;
}

export function registerWeChatUiWebhookTarget(target: WebhookTarget): () => void {
  const key = normalizeWebhookPath(target.path);
  const normalizedTarget = { ...target, path: key };
  const existing = webhookTargets.get(key) ?? [];
  const next = [...existing, normalizedTarget];
  webhookTargets.set(key, next);
  return () => {
    const updated = (webhookTargets.get(key) ?? []).filter((entry) => entry !== normalizedTarget);
    if (updated.length > 0) webhookTargets.set(key, updated);
    else webhookTargets.delete(key);
  };
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

function normalizeWsUrl(raw?: string | null): string {
  return String(raw ?? "").trim();
}

function coerceInboundPayload(raw: unknown): InboundPayload | null {
  if (!raw) return null;
  if (typeof raw === "string") return null;
  if (typeof raw !== "object" || Array.isArray(raw)) return null;
  const obj = raw as Record<string, unknown>;

  const direct = obj as InboundPayload;
  if (typeof direct.from === "string" || typeof direct.text === "string") {
    return direct;
  }

  const payload = obj.payload;
  if (payload && typeof payload === "object" && !Array.isArray(payload)) {
    return coerceInboundPayload(payload);
  }
  const data = obj.data;
  if (data && typeof data === "object" && !Array.isArray(data)) {
    return coerceInboundPayload(data);
  }
  return null;
}

function readInboundAccountId(raw: unknown): string {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return "";
  const obj = raw as Record<string, unknown>;
  const a = obj.accountId;
  if (typeof a === "string") return a.trim();
  const b = obj.account_id;
  if (typeof b === "string") return b.trim();
  return "";
}

async function processInboundMessage(payload: InboundPayload, target: WebhookTarget): Promise<void> {
  await processWeChatUiInboundMessage({
    payload,
    account: target.account,
    cfg: target.config,
    runtime: target.runtime,
    statusSink: target.statusSink,
    log: target.log,
    deliverText: async ({ to, text }) => {
      await sendWeChatUiText({ cfg: target.config, accountId: target.account.accountId, to, text });
    },
    deliverMedia: async ({ to, text, mediaUrl }) => {
      await sendWeChatUiMedia({ cfg: target.config, accountId: target.account.accountId, to, text, mediaUrl });
    },
  });
}

export async function handleWeChatUiWebhookRequest(
  req: IncomingMessage,
  res: ServerResponse,
): Promise<boolean> {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = normalizeWebhookPath(url.pathname);
  const targets = webhookTargets.get(path);
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
    res.statusCode = body.error === "payload too large" ? 413 : 400;
    res.end(body.error ?? "invalid payload");
    return true;
  }

  const raw = body.value;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    res.statusCode = 400;
    res.end("invalid payload");
    return true;
  }

  const payload = raw as InboundPayload;

  const matching = targets.filter((t) => {
    const secret = String(t.account.config.webhookSecret ?? "").trim();
    if (!secret) {
      return true;
    }
    return isAuthorized(req, secret);
  });

  if (matching.length === 0) {
    res.statusCode = 401;
    res.end("unauthorized");
    return true;
  }

  res.statusCode = 200;
  res.end("ok");

  // Acknowledge quickly; process asynchronously so the bridge doesn't time out
  // while the agent generates a reply.
  setImmediate(() => {
    void Promise.allSettled(
      matching.map(async (target) => {
        try {
          await processInboundMessage(payload, target);
        } catch (err) {
          target.runtime.error?.(`[wechatui] inbound failed: ${String(err)}`);
        }
      }),
    );
  });

  return true;
}

export async function monitorWeChatUiProvider(params: {
  cfg: OpenClawConfig;
  accountId?: string | null;
  abortSignal: AbortSignal;
  runtime: WeChatUiRuntimeEnv;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
  log?: ChannelLogSink;
}): Promise<() => void> {
  const account = resolveWeChatUiAccount({ cfg: params.cfg, accountId: params.accountId });
  // Validate config early.
  WeChatUiConfigSchema.parse((params.cfg.channels?.["wechatui"] ?? {}) as unknown);

  const unregisterDeviceHub = registerWeChatUiDeviceHubContext({
    account,
    cfg: params.cfg,
    runtime: params.runtime,
    statusSink: params.statusSink,
    log: params.log,
  });

  const wsUrl = normalizeWsUrl(account.config.wsUrl);
  if (wsUrl) {
    const wsToken = String(account.config.wsToken ?? "").trim();
    const client = getWeChatUiWsClient({ accountId: account.accountId, wsUrl, wsToken, log: params.log });
    const unsubscribeMessage = client.onMessage((raw) => {
      const inbound = coerceInboundPayload(raw);
      if (!inbound) return;
      const expected = readInboundAccountId(inbound);
      if (expected && expected !== account.accountId) return;
      void processInboundMessage(inbound, {
        account,
        config: params.cfg,
        runtime: params.runtime,
        path: "/ws",
        statusSink: params.statusSink,
        log: params.log,
      }).catch((err) => params.runtime.error?.(`[wechatui] inbound failed: ${String(err)}`));
    });
    const unsubscribeState = client.onState((state) => {
      params.runtime.log?.(`[${account.accountId}] [wechatui] ws state=${state}`);
    });
    client.start(params.abortSignal);

    const stop = () => {
      unsubscribeMessage();
      unsubscribeState();
      client.close();
      unregisterDeviceHub();
    };

    if (params.abortSignal.aborted) {
      stop();
      return stop;
    }
    params.abortSignal.addEventListener("abort", stop, { once: true });
    return stop;
  }

  const webhookPath = resolveWebhookPathFromConfig(account.config);
  const unregister = registerWeChatUiWebhookTarget({
    account,
    config: params.cfg,
    runtime: params.runtime,
    path: webhookPath,
    statusSink: params.statusSink,
    log: params.log,
  });

  params.runtime.log?.(`[${account.accountId}] [wechatui] webhook listening on ${webhookPath}`);

  const stop = () => {
    unregister();
    unregisterDeviceHub();
  };

  if (params.abortSignal.aborted) {
    stop();
    return stop;
  }
  params.abortSignal.addEventListener("abort", stop, { once: true });
  return stop;
}
