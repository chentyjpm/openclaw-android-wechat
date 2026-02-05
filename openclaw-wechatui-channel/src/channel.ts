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
import { monitorWeChatUiProvider, resolveWebhookPathFromConfig } from "./monitor.js";
import { sendWeChatUiMedia, sendWeChatUiText } from "./send.js";

const meta = {
  id: "wechatui",
  label: "WeChat UI",
  selectionLabel: "WeChat UI (Windows bridge)",
  detailLabel: "WeChat UI",
  docsPath: "/channels/wechatui",
  docsLabel: "wechatui",
  blurb: "WeChat (Windows UI automation) via a bridge + webhook.",
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
        clearBaseFields: ["bridgeUrl", "bridgeToken", "webhookPath", "webhookSecret", "name"],
      }),
    isConfigured: (account) => Boolean(account.config.bridgeUrl && account.config.bridgeToken),
    describeAccount: (account): ChannelAccountSnapshot => ({
      accountId: account.accountId,
      name: account.name,
      enabled: account.enabled,
      configured: Boolean(account.config.bridgeUrl && account.config.bridgeToken),
      bridgeUrl: account.config.bridgeUrl,
      webhookPath: account.config.webhookPath,
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
      await sendWeChatUiText({ cfg, accountId, to: dest, text });
      return { channel: "wechatui", messageId: `wechatui:${Date.now()}` };
    },
    sendMedia: async ({ cfg, to, text, mediaUrl, accountId }) => {
      const dest = String(to ?? "").trim();
      if (!dest) throw missingTargetError("wechatui");
      const url = String(mediaUrl ?? "").trim();
      if (!url) throw new Error("wechatui: missing mediaUrl");
      await sendWeChatUiMedia({ cfg, accountId, to: dest, text: text ?? "", mediaUrl: url });
      return { channel: "wechatui", messageId: `wechatui:${Date.now()}` };
    },
  },
  gateway: {
    startAccount: async (ctx) => {
      const account = ctx.account;
      const webhookPath = resolveWebhookPathFromConfig(account.config);
      ctx.setStatus({ accountId: account.accountId, webhookPath });
      ctx.log?.info(`[${account.accountId}] starting provider (webhook=${webhookPath})`);
      return await monitorWeChatUiProvider({
        cfg: ctx.cfg,
        accountId: ctx.accountId,
        runtime: ctx.runtime,
        abortSignal: ctx.abortSignal,
        log: ctx.log,
        statusSink: (patch) => ctx.setStatus({ accountId: ctx.accountId, ...patch }),
      });
    },
  },
};
