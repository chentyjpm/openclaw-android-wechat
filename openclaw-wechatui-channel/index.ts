import type { OpenClawPluginApi } from "openclaw/plugin-sdk";
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";
import { handleHuixiangdouBridgeRequest, handleHuixiangdouPushRequest } from "./src/huixiangdou-bridge.js";
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
    api.registerHttpHandler(handleHuixiangdouBridgeRequest);
    api.registerHttpHandler(handleHuixiangdouPushRequest);
  },
};

export default plugin;
