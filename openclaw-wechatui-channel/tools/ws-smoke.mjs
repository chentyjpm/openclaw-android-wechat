#!/usr/bin/env node
import WebSocket from "ws";

const wsUrl = String(process.env.WS_URL ?? "").trim();
const wsToken = String(process.env.WS_TOKEN ?? "").trim();
const accountId = String(process.env.ACCOUNT_ID ?? "default").trim() || "default";
const op = String(process.env.OP ?? "send_text").trim() || "send_text";
const target = String(process.env.TARGET ?? "").trim();
const text = String(process.env.TEXT ?? "hello from ws-smoke").trim();
const mediaUrl = String(process.env.MEDIA_URL ?? "").trim();

if (!wsUrl) {
  console.error("Missing WS_URL, e.g. WS_URL=ws://127.0.0.1:18790/ws");
  process.exit(2);
}
if (!target) {
  console.error("Missing TARGET, e.g. TARGET='openclaw'");
  process.exit(2);
}

const headers = {};
if (wsToken) headers.authorization = `Bearer ${wsToken}`;

console.log(`[ws-smoke] connecting ${wsUrl}`);
const ws = new WebSocket(wsUrl, { headers });

ws.on("open", () => {
  console.log("[ws-smoke] open");
  ws.send(
    JSON.stringify({
      op: "hello",
      account_id: accountId,
      client: "openclaw-wechatui-channel:ws-smoke",
      ts_ms: Date.now(),
    }),
  );

  const payload =
    op === "send_media"
      ? {
          op: "send_media",
          account_id: accountId,
          target,
          text,
          media_url: mediaUrl || "https://example.com/image.jpg",
          ts_ms: Date.now(),
        }
      : {
          op: "send_text",
          account_id: accountId,
          target,
          text,
          ts_ms: Date.now(),
        };

  console.log("[ws-smoke] send", payload);
  ws.send(JSON.stringify(payload));
});

ws.on("message", (data) => {
  const text = typeof data === "string" ? data : data.toString();
  console.log("[ws-smoke] recv", text);
});

ws.on("close", (code, reason) => {
  console.log(`[ws-smoke] close code=${code} reason=${String(reason ?? "")}`);
});

ws.on("error", (err) => {
  console.error("[ws-smoke] error", err);
  process.exitCode = 1;
});

setTimeout(() => {
  try {
    ws.close();
  } catch {
    // ignore
  }
}, 5000);

