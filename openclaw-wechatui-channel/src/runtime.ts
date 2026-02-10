import type { PluginRuntime } from "openclaw/plugin-sdk";

let runtime: PluginRuntime | null = null;

export function setWeChatUiRuntime(next: PluginRuntime): void {
  runtime = next;
}

export function getWeChatUiRuntime(): PluginRuntime {
  if (!runtime) {
    throw new Error("WeChat UI runtime not initialized");
  }
  return runtime;
}

