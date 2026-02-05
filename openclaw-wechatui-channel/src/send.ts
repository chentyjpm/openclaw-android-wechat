import type { OpenClawConfig } from "openclaw/plugin-sdk";
import { missingTargetError } from "openclaw/plugin-sdk";
import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { resolveWeChatUiAccount } from "./accounts.js";
import { getWeChatUiRuntime } from "./runtime.js";
import { getWeChatUiWsClient } from "./ws-client.js";
import { enqueueWeChatUiSendMediaTask, enqueueWeChatUiSendTextTask } from "./device-hub.js";

type SendTextParams = {
  cfg: OpenClawConfig;
  accountId?: string | null;
  to: string;
  text: string;
};

type SendMediaParams = {
  cfg: OpenClawConfig;
  accountId?: string | null;
  to: string;
  text: string;
  mediaUrl: string;
};

function normalizeBaseUrl(raw?: string | null): string {
  const trimmed = String(raw ?? "").trim();
  if (!trimmed) return "";
  return trimmed.endsWith("/") ? trimmed.slice(0, -1) : trimmed;
}

function normalizeWsUrl(raw?: string | null): string {
  const trimmed = String(raw ?? "").trim();
  return trimmed;
}

function parseDataUrl(mediaUrl: string): { mime: string; base64: string } | null {
  const trimmed = mediaUrl.trim();
  if (!trimmed.toLowerCase().startsWith("data:")) return null;
  const comma = trimmed.indexOf(",");
  if (comma < 0) return null;
  const header = trimmed.slice(5, comma);
  const payload = trimmed.slice(comma + 1).trim();
  const parts = header.split(";").map((p) => p.trim()).filter(Boolean);
  const mime = parts.length > 0 && parts[0].includes("/") ? parts[0] : "application/octet-stream";
  const isBase64 = parts.slice(1).some((p) => p.toLowerCase() === "base64");
  if (!isBase64) return null;
  return { mime, base64: payload };
}

function maybeResolveLocalPath(mediaUrl: string): string | null {
  const trimmed = mediaUrl.trim();
  if (!trimmed) return null;
  if (/^file:\/\//i.test(trimmed)) {
    try {
      return fileURLToPath(new URL(trimmed));
    } catch {
      return null;
    }
  }
  // Treat absolute paths or existing files as local paths.
  try {
    if (path.isAbsolute(trimmed) && fs.existsSync(trimmed)) return trimmed;
    if (fs.existsSync(trimmed)) return trimmed;
  } catch {
    // ignore
  }
  return null;
}

async function wsSendText(params: { wsUrl: string; wsToken: string; accountId: string; to: string; text: string }) {
  const rt = getWeChatUiRuntime();
  const client = getWeChatUiWsClient({
    accountId: params.accountId,
    wsUrl: params.wsUrl,
    wsToken: params.wsToken,
    log: rt.log,
  });
  await client.sendJson({
    op: "send_text",
    account_id: params.accountId,
    target: params.to,
    text: params.text,
    ts_ms: Date.now(),
  });
}

async function wsSendMedia(params: {
  wsUrl: string;
  wsToken: string;
  accountId: string;
  to: string;
  text: string;
  mediaUrl: string;
}) {
  const rt = getWeChatUiRuntime();
  const client = getWeChatUiWsClient({
    accountId: params.accountId,
    wsUrl: params.wsUrl,
    wsToken: params.wsToken,
    log: rt.log,
  });
  await client.sendJson({
    op: "send_media",
    account_id: params.accountId,
    target: params.to,
    text: params.text,
    media_url: params.mediaUrl,
    ts_ms: Date.now(),
  });
}

async function postBridgeImageUpload(params: {
  baseUrl: string;
  token: string;
  to: string;
  buffer: Uint8Array;
  mime?: string;
  fileName?: string;
}): Promise<void> {
  const rt = getWeChatUiRuntime();
  const form = new FormData();
  form.set("target", params.to);
  const mime = String(params.mime ?? "").trim() || "application/octet-stream";
  const fileName = String(params.fileName ?? "image").trim() || "image";
  // Undici/Node fetch supports (Blob, filename).
  form.set("file", new Blob([params.buffer], { type: mime }), fileName);
  rt.log.info(
    `[wechatui] upload -> ${params.baseUrl}/sendImageUpload to=${params.to} bytes=${params.buffer.byteLength} mime=${mime} file=${fileName}`,
  );
  const res = await fetch(`${params.baseUrl}/sendImageUpload`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${params.token}`,
    },
    body: form,
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    rt.log.error(`[wechatui] upload failed status=${res.status} body=${body}`);
    throw new Error(`wechatui sendImageUpload failed: ${res.status} ${body}`.trim());
  }
  rt.log.info("[wechatui] upload ok");
}

export async function sendWeChatUiText(params: SendTextParams): Promise<void> {
  const to = params.to.trim();
  if (!to) {
    throw missingTargetError("wechatui");
  }
  const account = resolveWeChatUiAccount({ cfg: params.cfg, accountId: params.accountId });

  const wsUrl = normalizeWsUrl(account.config.wsUrl);
  if (wsUrl) {
    const wsToken = String(account.config.wsToken ?? "").trim();
    await wsSendText({
      wsUrl,
      wsToken,
      accountId: account.accountId,
      to,
      text: params.text,
    });
    return;
  }

  const baseUrl = normalizeBaseUrl(account.config.bridgeUrl);
  if (!baseUrl) {
    // Phone mode (wx-server HTTP pull/push): enqueue a send_text task and return.
    enqueueWeChatUiSendTextTask({ targetTitle: to, text: params.text });
    return;
  }
  const token = String(account.config.bridgeToken ?? "").trim();
  if (!token) {
    throw new Error("wechatui: missing channels.wechatui.bridgeToken");
  }

  const url = `${baseUrl}/sendText`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ target: to, text: params.text }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`wechatui sendText failed: ${res.status} ${body}`.trim());
  }
}

export async function sendWeChatUiMedia(params: SendMediaParams): Promise<void> {
  const to = params.to.trim();
  if (!to) {
    throw missingTargetError("wechatui");
  }
  const account = resolveWeChatUiAccount({ cfg: params.cfg, accountId: params.accountId });

  const wsUrl = normalizeWsUrl(account.config.wsUrl);
  if (wsUrl) {
    const wsToken = String(account.config.wsToken ?? "").trim();
    const mediaUrl = params.mediaUrl.trim();

    // Local paths are not portable; fall back to link.
    if (maybeResolveLocalPath(mediaUrl)) {
      await wsSendText({
        wsUrl,
        wsToken,
        accountId: account.accountId,
        to,
        text: mediaUrl,
      });
      if (params.text.trim()) {
        await wsSendText({
          wsUrl,
          wsToken,
          accountId: account.accountId,
          to,
          text: params.text,
        });
      }
      return;
    }

    if (parseDataUrl(mediaUrl)) {
      throw new Error("wechatui: base64 data URLs are disabled; use an http(s) URL instead");
    }

    await wsSendMedia({
      wsUrl,
      wsToken,
      accountId: account.accountId,
      to,
      text: params.text,
      mediaUrl,
    });
    return;
  }

  const baseUrl = normalizeBaseUrl(account.config.bridgeUrl);
  if (!baseUrl) {
    // Phone mode (wx-server HTTP pull/push): send image as a task (image_url).
    const mediaUrl = params.mediaUrl.trim();
    if (parseDataUrl(mediaUrl)) {
      throw new Error("wechatui: base64 data URLs are disabled; use an http(s) URL instead");
    }
    if (maybeResolveLocalPath(mediaUrl)) {
      // Local file paths are not portable to the phone; fall back to link.
      enqueueWeChatUiSendTextTask({ targetTitle: to, text: mediaUrl });
      if (params.text.trim()) enqueueWeChatUiSendTextTask({ targetTitle: to, text: params.text });
      return;
    }
    enqueueWeChatUiSendMediaTask({ targetTitle: to, text: params.text, mediaUrl });
    return;
  }
  const token = String(account.config.bridgeToken ?? "").trim();
  if (!token) {
    throw new Error("wechatui: missing channels.wechatui.bridgeToken");
  }

  const mediaUrl = params.mediaUrl.trim();

  // Best effort: if mediaUrl is a local path on the gateway machine, upload it to the Windows bridge.
  const localPath = maybeResolveLocalPath(mediaUrl);
  if (localPath) {
    const buf = await fsp.readFile(localPath);
    const rt = getWeChatUiRuntime();
    const detected = await rt.media.detectMime(buf).catch(() => null);
    await postBridgeImageUpload({
      baseUrl,
      token,
      to,
      buffer: buf,
      mime: detected?.mime ?? undefined,
      fileName: path.basename(localPath),
    });
    if (params.text.trim()) {
      await sendWeChatUiText({ cfg: params.cfg, accountId: params.accountId, to, text: params.text });
    }
    return;
  }

  // Explicitly disallow base64 data URLs (token-heavy).
  if (parseDataUrl(mediaUrl)) {
    throw new Error(
      "wechatui: base64 data URLs are disabled; use a local file path or an http(s) URL instead",
    );
  }

  // http(s) URL: download on gateway (with SSRF policy), then upload bytes to the Windows bridge.
  if (/^https?:\/\//i.test(mediaUrl)) {
    try {
      const rt = getWeChatUiRuntime();
      const web = await rt.media.loadWebMedia(mediaUrl, 8 * 1024 * 1024);
      if (web?.buffer && web?.contentType) {
        await postBridgeImageUpload({
          baseUrl,
          token,
          to,
          buffer: web.buffer,
          mime: web.contentType,
          fileName: web.fileName ?? "image",
        });
        if (params.text.trim()) {
          await sendWeChatUiText({ cfg: params.cfg, accountId: params.accountId, to, text: params.text });
        }
        return;
      }
    } catch {
      // fall through to link fallback
    }
  }

  // Fallback: send as a link so the user still gets something.
  await sendWeChatUiText({ cfg: params.cfg, accountId: params.accountId, to, text: params.mediaUrl });
  if (params.text.trim()) {
    await sendWeChatUiText({ cfg: params.cfg, accountId: params.accountId, to, text: params.text });
  }
  return;

}
