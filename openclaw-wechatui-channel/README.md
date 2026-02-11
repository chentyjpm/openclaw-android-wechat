# openclaw-wechatui-channel

WeChat (Windows UI automation) as an OpenClaw `wechatui` channel via a Windows bridge + inbound webhook.

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

```toml
[channels.wechatui]
bridgeUrl = "http://WECHAT_PC_LAN_IP:19899"
bridgeToken = "CHANGE_ME"
webhookPath = "/wechatui"
webhookSecret = "CHANGE_ME"
dmPolicy = "allowlist"
allowFrom = ["openclaw", "陈天羽"]
```

## Android Huixiangdou bridge (optional)

If you want this plugin to directly integrate with the Android Huixiangdou accessibility client (`SendEmojiService`),
set the Android app URL to your OpenClaw Gateway and let the gateway act as the Huixiangdou HTTP endpoint.

Add to your config:

```toml
[channels.wechatui]
androidWebhookPath = "/openclawwx"
# Optional auth (Android must send this in `Authorization: Bearer ...` or `X-Huixiangdou-Secret`)
# androidWebhookSecret = "CHANGE_ME"
```

Then in the Android app (Huixiangdou UI), set URL to:

`http://GATEWAY_LAN_IP:18789/openclawwx/pull`

### Long polling (recommended)

To reduce client polling spam, the gateway can hold `type=poll` requests open until data is ready.
Set `androidLongPollMs` (e.g. 25000) in your config:

```toml
[channels.wechatui]
androidLongPollMs = 25000
```

### Server push (optional)

You can also enqueue a message to the Android client (it will be delivered on its next `poll`):

`POST http://GATEWAY_LAN_IP:18789/openclawwx/push`

Body example:

```json
{
  "query_id": "some-session-id",
  "groupname": "some-chat",
  "username": "some-user",
  "text": "hello from gateway"
}
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
