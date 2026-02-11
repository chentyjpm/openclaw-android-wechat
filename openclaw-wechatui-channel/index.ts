import type { OpenClawPluginApi } from "openclaw/plugin-sdk";
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";
import { handleClientPullRequest, handleClientPushRequest } from "./src/client-bridge.js";
import { wechatuiPlugin } from "./src/channel.js";
import { setWeChatUiRuntime } from "./src/runtime.js";

const plugin = {
  id: "wechatui",
  name: "WeChat UI (Android)",
  description: "OpenClaw WeChat UI channel via Android client pull/push",
  configSchema: emptyPluginConfigSchema(),
  register(api: OpenClawPluginApi) {
    setWeChatUiRuntime(api.runtime);
    api.registerChannel({ plugin: wechatuiPlugin });
    api.registerHttpHandler(handleClientPullRequest);
    api.registerHttpHandler(handleClientPushRequest);
  },
};

export default plugin;

