import type { OpenClawConfig } from "openclaw/plugin-sdk";
import { resolveWeChatUiAccount } from "./accounts.js";
import { registerClientBridgeTarget } from "./client-bridge.js";
import { WeChatUiConfigSchema } from "./config-schema.js";
import { getWeChatUiRuntime } from "./runtime.js";

type WeChatUiRuntimeEnv = {
  log?: (message: string) => void;
  error?: (message: string) => void;
};

export async function monitorWeChatUiProvider(params: {
  cfg: OpenClawConfig;
  accountId?: string | null;
  abortSignal: AbortSignal;
  runtime: WeChatUiRuntimeEnv;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
}): Promise<() => void> {
  const core = getWeChatUiRuntime();
  const account = resolveWeChatUiAccount({ cfg: params.cfg, accountId: params.accountId });
  WeChatUiConfigSchema.parse((params.cfg.channels?.["wechatui"] ?? {}) as unknown);

  const unregisterClient = registerClientBridgeTarget({
    account,
    config: params.cfg,
    runtime: params.runtime,
    core,
    statusSink: params.statusSink,
  });

  params.runtime.log?.(`[${account.accountId}] [client] endpoints on /client/pull and /client/push`);

  const stop = () => {
    unregisterClient();
  };

  if (params.abortSignal.aborted) {
    stop();
    return stop;
  }
  params.abortSignal.addEventListener("abort", stop, { once: true });
  return stop;
}
