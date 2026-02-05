import type { IncomingMessage, ServerResponse } from "node:http";
import type { ChannelLogSink, OpenClawConfig } from "openclaw/plugin-sdk";
import { logInboundDrop } from "openclaw/plugin-sdk";
import type { ResolvedWeChatUiAccount } from "./accounts.js";
import { resolveWeChatUiAccount } from "./accounts.js";
import { WeChatUiConfigSchema } from "./config-schema.js";
import { sendWeChatUiMedia, sendWeChatUiText } from "./send.js";
import { getWeChatUiRuntime } from "./runtime.js";

type WeChatUiRuntimeEnv = {
  log?: (message: string) => void;
  error?: (message: string) => void;
};

type WeChatUiCoreRuntime = ReturnType<typeof getWeChatUiRuntime>;

type WebhookTarget = {
  account: ResolvedWeChatUiAccount;
  config: OpenClawConfig;
  runtime: WeChatUiRuntimeEnv;
  core: WeChatUiCoreRuntime;
  path: string;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
  log?: ChannelLogSink;
};

type InboundPayload = {
  accountId?: string;
  from?: string;
  text?: string;
  timestamp?: number;
  fromMe?: boolean;
  media?: Array<{
    kind?: string;
    id?: string;
    url?: string;
    path?: string;
  }>;
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

async function processInboundMessage(payload: InboundPayload, target: WebhookTarget): Promise<void> {
  const { account, config, core, runtime, statusSink } = target;

  if (payload.fromMe) {
    return;
  }

  const from = String(payload.from ?? "").trim();
  const text = String(payload.text ?? "");
  const timestamp = typeof payload.timestamp === "number" ? payload.timestamp : Date.now();

  if (!from || !text.trim()) {
    return;
  }

  // If bridge includes media ids, turn them into URLs using bridgeUrl so the agent can access them.
  // (OpenClaw core media ingestion differs by provider; this keeps the integration text-only.)
  let textWithMedia = text;
  const media = Array.isArray(payload.media) ? payload.media : [];
  if (media.length > 0) {
    const baseUrl = String(account.config.bridgeUrl ?? "").trim().replace(/\/$/, "");
    const lines: string[] = [];
    for (const m of media) {
      const kind = String(m?.kind ?? "").trim();
      const id = String(m?.id ?? "").trim();
      const url = String(m?.url ?? "").trim();
      if (kind === "image" && (url || (baseUrl && id))) {
        const resolved = url || `${baseUrl}/media/${id}`;
        lines.push(`[image] ${resolved}`);
      }
    }
    if (lines.length > 0) {
      textWithMedia = `${text}\n\n${lines.join("\n")}`;
    }
  }

  // Optional allowlist gating (recommended; also matches your "指定联系人" requirement)
  const dmPolicy = account.config.dmPolicy ?? "allowlist";
  const allowFrom = (account.config.allowFrom ?? []).map((v) => String(v).trim()).filter(Boolean);
  if (dmPolicy === "disabled") {
    return;
  }
  if (dmPolicy === "allowlist" && allowFrom.length > 0 && !allowFrom.includes(from)) {
    logInboundDrop({
      log: (msg) => runtime.log?.(`[wechatui] ${msg}`),
      channel: "wechatui",
      reason: "dm sender not allowed",
      target: from,
    });
    return;
  }

  const route = core.channel.routing.resolveAgentRoute({
    cfg: config,
    channel: "wechatui",
    accountId: account.accountId,
    peer: { kind: "dm", id: from },
  });

  const ctxPayload = {
    Body: textWithMedia,
    BodyForAgent: textWithMedia,
    RawBody: textWithMedia,
    CommandBody: textWithMedia,
    BodyForCommands: textWithMedia,
    From: `wechatui:${from}`,
    To: `wechatui:${from}`,
    SessionKey: route.sessionKey,
    AccountId: route.accountId,
    ChatType: "direct",
    ConversationLabel: from,
    SenderName: from,
    SenderId: from,
    Provider: "wechatui",
    Surface: "wechatui",
    Timestamp: timestamp,
    OriginatingChannel: "wechatui",
    OriginatingTo: `wechatui:${from}`,
    WasMentioned: true,
    CommandAuthorized: true,
  };

  statusSink?.({ lastInboundAt: Date.now() });

  const textLimit =
    account.config.textChunkLimit && account.config.textChunkLimit > 0 ? account.config.textChunkLimit : 1500;

  await core.channel.reply.dispatchReplyWithBufferedBlockDispatcher({
    ctx: ctxPayload,
    cfg: config,
    dispatcherOptions: {
      deliver: async (replyPayload) => {
        const replyText = String(replyPayload.text ?? "");
        const mediaUrls: string[] = [];
        const one = String(replyPayload.mediaUrl ?? "").trim();
        if (one) mediaUrls.push(one);
        const many = Array.isArray(replyPayload.mediaUrls) ? replyPayload.mediaUrls : [];
        for (const u of many) {
          const s = String(u ?? "").trim();
          if (s) mediaUrls.push(s);
        }

        // Telegram-style: if there's a mediaUrl, send media first, then follow up with text chunks.
        if (mediaUrls.length > 0) {
          for (const u of mediaUrls) {
            await sendWeChatUiMedia({
              cfg: config,
              accountId: account.accountId,
              to: from,
              text: "",
              mediaUrl: u,
            });
            statusSink?.({ lastOutboundAt: Date.now() });
          }
        }

        if (replyText.trim()) {
          const chunks = core.channel.text.chunkMarkdownText(replyText, textLimit);
          if (!chunks.length && replyText) chunks.push(replyText);
          for (const chunk of chunks) {
            await sendWeChatUiText({
              cfg: config,
              accountId: account.accountId,
              to: from,
              text: chunk,
            });
            statusSink?.({ lastOutboundAt: Date.now() });
          }
        }
      },
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
  const core = getWeChatUiRuntime();
  const account = resolveWeChatUiAccount({ cfg: params.cfg, accountId: params.accountId });
  // Validate config early.
  WeChatUiConfigSchema.parse((params.cfg.channels?.["wechatui"] ?? {}) as unknown);
  const webhookPath = resolveWebhookPathFromConfig(account.config);
  const unregister = registerWeChatUiWebhookTarget({
    account,
    config: params.cfg,
    runtime: params.runtime,
    core,
    path: webhookPath,
    statusSink: params.statusSink,
    log: params.log,
  });

  params.runtime.log?.(`[${account.accountId}] [wechatui] webhook listening on ${webhookPath}`);

  const stop = () => {
    unregister();
  };

  if (params.abortSignal.aborted) {
    stop();
    return stop;
  }
  params.abortSignal.addEventListener("abort", stop, { once: true });
  return stop;
}
