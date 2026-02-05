import type { OpenClawConfig } from "openclaw/plugin-sdk";
import { DEFAULT_ACCOUNT_ID, normalizeAccountId } from "openclaw/plugin-sdk";
import type { WeChatUiAccountConfig } from "./config-schema.js";

export type ResolvedWeChatUiAccount = {
  accountId: string;
  enabled: boolean;
  name: string;
  config: WeChatUiAccountConfig;
};

const DEFAULT_NAME = "WeChat UI";

export function listWeChatUiAccountIds(cfg: OpenClawConfig): string[] {
  const accounts = cfg.channels?.["wechatui"]?.accounts ?? {};
  const ids = Object.keys(accounts).map((id) => normalizeAccountId(id));
  const unique = Array.from(new Set([DEFAULT_ACCOUNT_ID, ...ids]));
  return unique;
}

export function resolveDefaultWeChatUiAccountId(_cfg: OpenClawConfig): string {
  return DEFAULT_ACCOUNT_ID;
}

function readAccountConfig(cfg: OpenClawConfig, accountId: string): WeChatUiAccountConfig {
  const section = cfg.channels?.["wechatui"] ?? {};
  const accounts = section.accounts ?? {};
  const account = accounts[accountId] ?? {};

  // Allow "base" fields at channels.wechatui.* for default account compatibility.
  if (accountId === DEFAULT_ACCOUNT_ID) {
    const base: WeChatUiAccountConfig = {
      ...(section as unknown as WeChatUiAccountConfig),
      ...(account as unknown as WeChatUiAccountConfig),
    };
    return base;
  }
  return account as unknown as WeChatUiAccountConfig;
}

export function resolveWeChatUiAccount(params: {
  cfg: OpenClawConfig;
  accountId?: string | null;
}): ResolvedWeChatUiAccount {
  const accountId = normalizeAccountId(params.accountId ?? DEFAULT_ACCOUNT_ID);
  const config = readAccountConfig(params.cfg, accountId);
  const enabled = config.enabled !== false;
  const name = config.name?.trim() || DEFAULT_NAME;
  return { accountId, enabled, name, config };
}

