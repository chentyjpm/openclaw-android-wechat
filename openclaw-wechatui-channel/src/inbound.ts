import type { ChannelLogSink, OpenClawConfig } from "openclaw/plugin-sdk";
import { logInboundDrop } from "openclaw/plugin-sdk";
import type { ResolvedWeChatUiAccount } from "./accounts.js";
import { getWeChatUiRuntime } from "./runtime.js";

export type WeChatUiRuntimeEnv = {
  log?: (message: string) => void;
  error?: (message: string) => void;
};

export type InboundPayload = {
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

type DeliverTextFn = (params: { to: string; text: string }) => Promise<void>;
type DeliverMediaFn = (params: { to: string; text: string; mediaUrl: string }) => Promise<void>;

export async function processWeChatUiInboundMessage(params: {
  payload: InboundPayload;
  account: ResolvedWeChatUiAccount;
  cfg: OpenClawConfig;
  runtime: WeChatUiRuntimeEnv;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
  log?: ChannelLogSink;
  deliverText: DeliverTextFn;
  deliverMedia: DeliverMediaFn;
}): Promise<void> {
  const { payload, account, cfg, runtime, statusSink, deliverText, deliverMedia } = params;
  const core = getWeChatUiRuntime();

  if (payload.fromMe) return;

  const from = String(payload.from ?? "").trim();
  const text = String(payload.text ?? "");
  const timestamp = typeof payload.timestamp === "number" ? payload.timestamp : Date.now();

  if (!from || !text.trim()) return;

  // Optional allowlist gating (recommended; also matches your "指定联系人" requirement)
  const dmPolicy = account.config.dmPolicy ?? "allowlist";
  const allowFrom = (account.config.allowFrom ?? []).map((v) => String(v).trim()).filter(Boolean);
  if (dmPolicy === "disabled") return;
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
    cfg,
    channel: "wechatui",
    accountId: account.accountId,
    peer: { kind: "dm", id: from },
  });

  const ctxPayload = {
    Body: text,
    BodyForAgent: text,
    RawBody: text,
    CommandBody: text,
    BodyForCommands: text,
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
    cfg,
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

        if (mediaUrls.length > 0) {
          for (const u of mediaUrls) {
            await deliverMedia({ to: from, text: "", mediaUrl: u });
            statusSink?.({ lastOutboundAt: Date.now() });
          }
        }

        if (replyText.trim()) {
          const chunks = core.channel.text.chunkMarkdownText(replyText, textLimit);
          if (!chunks.length && replyText) chunks.push(replyText);
          for (const chunk of chunks) {
            await deliverText({ to: from, text: chunk });
            statusSink?.({ lastOutboundAt: Date.now() });
          }
        }
      },
    },
  });
}

