import type { OpenClawPluginApi } from "openclaw/plugin-sdk";
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";
import { handleWeChatUiWebhookRequest } from "./src/monitor.js";
import { handleWeChatUiDeviceRequest } from "./src/device-hub.js";
import { wechatuiPlugin } from "./src/channel.js";
import { setWeChatUiRuntime } from "./src/runtime.js";

const plugin = {
  id: "wechatui",
  name: "WeChat UI",
  description: "OpenClaw WeChat UI automation channel via phone wx-server (HTTP pull/push)",
  configSchema: emptyPluginConfigSchema(),
  register(api: OpenClawPluginApi) {
    setWeChatUiRuntime(api.runtime);
    api.registerChannel({ plugin: wechatuiPlugin });
    api.registerHttpHandler(handleWeChatUiWebhookRequest);
    api.registerHttpHandler(handleWeChatUiDeviceRequest);
  },
};

export default plugin;
