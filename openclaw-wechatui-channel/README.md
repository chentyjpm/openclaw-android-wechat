# openclaw-wechatui-channel

WeChat UI automation as an OpenClaw `wechatui` channel.

- Preferred: connect to a local/remote **ws-server** via WebSocket.
- Legacy: Windows bridge + inbound webhook (kept for backwards compatibility).

## Topology (two computers)

- **Gateway machine**: runs OpenClaw Gateway + this plugin.
- **WeChat machine (Windows)**: runs WeChat + `pywechat/openclaw_wechat_bridge.py`.

## Install (Gateway machine)

From a checkout:

```bash
openclaw plugins install ./openclaw-wechatui-channel
```

If you previously installed an older copy from a local path, delete the existing install dir first:

```bash
rm -rf ~/.openclaw/extensions/wechatui
```

## Configure (Gateway machine)

In your OpenClaw config:

### ws-server mode (recommended)

```toml
[channels.wechatui]
wsUrl = "ws://127.0.0.1:18790/ws"
wsToken = "OPTIONAL_TOKEN"
dmPolicy = "allowlist"
allowFrom = ["openclaw", "陈天羽"]
```

### Legacy Windows bridge mode

```toml
[channels.wechatui]
bridgeUrl = "http://WECHAT_PC_LAN_IP:19899"
bridgeToken = "CHANGE_ME"
webhookPath = "/wechatui"
webhookSecret = "CHANGE_ME"
dmPolicy = "allowlist"
allowFrom = ["openclaw", "陈天羽"]
```

## Run bridge (WeChat machine)

Example (PowerShell, preferred: CLI args):

```powershell
python -X utf8 pywechat\\openclaw_wechat_bridge.py `
  --targets "openclaw" `
  --bridge-token "CHANGE_ME" `
  --webhook-url "http://GATEWAY_LAN_IP:18789/wechatui" `
  --webhook-secret "CHANGE_ME"
```

Legacy: environment variables are still supported for backwards compatibility.

## Sending images

- Preferred: `sendMedia` with `mediaUrl` as a **local file path on the gateway machine** (e.g. `/home/.../image.png` or `file:///home/.../image.png`). The plugin uploads the file to the Windows bridge and sends it.
- Also supported: `http(s)://...` image URLs (gateway downloads, uploads to bridge, then sends).
- Not supported: `data:image/...;base64,...` (disabled; too token-heavy).
