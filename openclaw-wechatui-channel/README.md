# openclaw-wechatui-channel

OpenClaw `wechatui` channel plugin in Android client mode only.

This plugin exposes:

- `POST /client/pull`
- `POST /client/push`

Old Windows bridge/webhook mode has been removed.

## Install

```bash
openclaw plugins install ./openclaw-wechatui-channel
```

## Config

```toml
[channels.wechatui]
enabled = true
webhookSecret = "CHANGE_ME"
dmPolicy = "allowlist"
allowFrom = ["openclaw"]
```

`bridgeUrl` / `bridgeToken` / `webhookPath` are no longer needed.

## Auth

If `webhookSecret` is set, `/client/*` requires one of:

- `Authorization: Bearer <secret>`
- `X-WeChatUI-Secret: <secret>`

## Protocol

- `/client/push` receives Android envelopes.
- For each envelope with `msg.text`, plugin treats it as inbound user text.
- Replies are converted to `send_text` tasks and returned by `/client/pull`.
