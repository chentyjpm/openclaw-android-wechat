import type { OpenClawPluginApi } from "openclaw/plugin-sdk";
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";
import { handleOpenClawWxPullRequest, handleOpenClawWxPushRequest } from "./src/openclawwx-bridge.js";
import { handleWeChatUiWebhookRequest } from "./src/monitor.js";
import { wechatuiPlugin } from "./src/channel.js";
import { setWeChatUiRuntime } from "./src/runtime.js";

const plugin = {
  id: "wechatui",
  name: "WeChat UI (Windows)",
  description: "OpenClaw WeChat UI automation channel via a Windows bridge + webhook",
  configSchema: emptyPluginConfigSchema(),
  register(api: OpenClawPluginApi) {
    setWeChatUiRuntime(api.runtime);
    api.registerChannel({ plugin: wechatuiPlugin });
    api.registerHttpHandler(handleWeChatUiWebhookRequest);
    api.registerHttpHandler(handleOpenClawWxPullRequest);
    api.registerHttpHandler(handleOpenClawWxPushRequest);
  },
};

export default plugin;

