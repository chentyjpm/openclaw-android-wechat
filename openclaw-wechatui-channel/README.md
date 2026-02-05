# openclaw-wechatui-channel

WeChat UI automation as an OpenClaw `wechatui` channel.

- Recommended: phone-side `wx-server` connects to the OpenClaw Gateway via **HTTP `/client/pull` + `/client/push`**.
- Optional: connect to a local/remote **ws-server** via WebSocket (experimental).
- Legacy: Windows bridge + inbound webhook (kept for backwards compatibility).

## Topology (phone + gateway)

- **Gateway machine (PC)**: runs OpenClaw Gateway + this plugin. Exposes:
  - `POST /client/pull` (phone pulls tasks)
  - `POST /client/push` (phone pushes window_state/ack)
- **Phone (Android)**: runs `wx-server` accessibility service. Configure its `host`/`port` to point to the Gateway.

## Topology (two computers, legacy Windows bridge)

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

### Phone wx-server mode (recommended)

No special config is required besides enabling the channel. On the phone, set:

- `host` = gateway LAN IP (e.g. `192.168.1.10`)
- `port` = OpenClaw Gateway HTTP port (whatever you run OpenClaw on)

The phone will call `http://<host>:<port>/client/pull` and `/client/push`.

### ws-server mode (experimental)

```toml
[channels.wechatui]
wsUrl = "ws://127.0.0.1:18790/ws"
wsToken = "OPTIONAL_TOKEN"
dmPolicy = "allowlist"
allowFrom = ["openclaw", "陈天羽"]
```

## Standalone smoke test (no OpenClaw install)

If you just want to verify the ws-server endpoint accepts the payloads this plugin sends:

```bash
cd openclaw-wechatui-channel
npm i
WS_URL="ws://127.0.0.1:18790/ws" TARGET="openclaw" node tools/ws-smoke.mjs
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
