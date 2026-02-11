import type { ChannelAccountSnapshot, ChannelPlugin } from "openclaw/plugin-sdk";
import {
  buildChannelConfigSchema,
  deleteAccountFromConfigSection,
  setAccountEnabledInConfigSection,
  missingTargetError,
} from "openclaw/plugin-sdk";
import { WeChatUiConfigSchema } from "./config-schema.js";
import {
  listWeChatUiAccountIds,
  resolveDefaultWeChatUiAccountId,
  resolveWeChatUiAccount,
  type ResolvedWeChatUiAccount,
} from "./accounts.js";
import { enqueueClientSendTextTask } from "./client-bridge.js";
import { monitorWeChatUiProvider } from "./monitor.js";

const meta = {
  id: "wechatui",
  label: "WeChat UI",
  selectionLabel: "WeChat UI (Android client)",
  detailLabel: "WeChat UI",
  docsPath: "/channels/wechatui",
  docsLabel: "wechatui",
  blurb: "WeChat via Android accessibility client (/client/pull + /client/push).",
  systemImage: "message",
  aliases: ["wx"],
  order: 90,
};

export const wechatuiPlugin: ChannelPlugin<ResolvedWeChatUiAccount> = {
  id: "wechatui",
  meta,
  capabilities: {
    chatTypes: ["direct"],
    reactions: false,
    threads: false,
    media: true,
    nativeCommands: false,
    blockStreaming: true,
  },
  reload: { configPrefixes: ["channels.wechatui"] },
  configSchema: buildChannelConfigSchema(WeChatUiConfigSchema),
  config: {
    listAccountIds: (cfg) => listWeChatUiAccountIds(cfg),
    resolveAccount: (cfg, accountId) => resolveWeChatUiAccount({ cfg: cfg, accountId }),
    defaultAccountId: (cfg) => resolveDefaultWeChatUiAccountId(cfg),
    setAccountEnabled: ({ cfg, accountId, enabled }) =>
      setAccountEnabledInConfigSection({
        cfg: cfg,
        sectionKey: "wechatui",
        accountId,
        enabled,
        allowTopLevel: true,
      }),
    deleteAccount: ({ cfg, accountId }) =>
      deleteAccountFromConfigSection({
        cfg: cfg,
        sectionKey: "wechatui",
        accountId,
        clearBaseFields: ["webhookSecret", "name"],
      }),
    // Client mode (/client/pull + /client/push) does not require bridgeUrl/bridgeToken.
    isConfigured: (_account) => true,
    describeAccount: (account): ChannelAccountSnapshot => ({
      accountId: account.accountId,
      name: account.name,
      enabled: account.enabled,
      configured: true,
      mode: "client",
    }),
  },
  messaging: {
    normalizeTarget: (raw) => raw.trim(),
    targetResolver: {
      looksLikeId: (raw, normalized) => Boolean((normalized ?? raw).trim()),
      hint: "<wechat display name>",
    },
  },
  outbound: {
    deliveryMode: "direct",
    sendText: async ({ cfg, to, text, accountId }) => {
      const dest = String(to ?? "").trim();
      if (!dest) throw missingTargetError("wechatui");
      const account = resolveWeChatUiAccount({ cfg: cfg, accountId });
      const task = enqueueClientSendTextTask({
        accountId: account.accountId,
        text: String(text ?? ""),
        mode: "openclaw",
      });
      return { channel: "wechatui", messageId: `wechatui:task:${task.task_id}` };
    },
    sendMedia: async ({ cfg, to, text, mediaUrl, accountId }) => {
      const dest = String(to ?? "").trim();
      if (!dest) throw missingTargetError("wechatui");
      const url = String(mediaUrl ?? "").trim();
      if (!url) throw new Error("wechatui: missing mediaUrl");
      const account = resolveWeChatUiAccount({ cfg: cfg, accountId });
      const merged = [String(text ?? "").trim(), url].filter(Boolean).join("\n");
      const task = enqueueClientSendTextTask({
        accountId: account.accountId,
        text: merged,
        mode: "openclaw",
      });
      return { channel: "wechatui", messageId: `wechatui:task:${task.task_id}` };
    },
  },
  gateway: {
    startAccount: async (ctx) => {
      const account = ctx.account;
      ctx.setStatus({ accountId: account.accountId, mode: "client" });
      ctx.log?.info(`[${account.accountId}] starting provider (client mode=/client/pull,/client/push)`);
      return await monitorWeChatUiProvider({
        cfg: ctx.cfg,
        accountId: ctx.accountId,
        runtime: ctx.runtime,
        abortSignal: ctx.abortSignal,
        statusSink: (patch) => ctx.setStatus({ accountId: ctx.accountId, ...patch }),
      });
    },
  },
};
