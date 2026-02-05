import type { ChannelLogSink } from "openclaw/plugin-sdk";
import WebSocket from "ws";

type WsClientOptions = {
  accountId: string;
  wsUrl: string;
  wsToken?: string;
  log?: ChannelLogSink;
};

type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

function safeJsonParse(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
}

function normalizeWsUrl(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return "";
  return trimmed;
}

export class WeChatUiWsClient {
  private readonly accountId: string;
  private readonly wsUrl: string;
  private readonly wsToken: string;
  private log?: ChannelLogSink;
  private readonly messageListeners = new Set<(payload: unknown) => void>();
  private readonly stateListeners = new Set<
    (state: "connecting" | "connected" | "disconnected" | "failed") => void
  >();

  private ws: WebSocket | null = null;
  private closed = false;
  private connecting: Promise<void> | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private backoffMs = 250;
  private manualClose = false;

  constructor(opts: WsClientOptions) {
    this.accountId = opts.accountId;
    this.wsUrl = normalizeWsUrl(opts.wsUrl);
    this.wsToken = String(opts.wsToken ?? "").trim();
    this.log = opts.log;
  }

  setLog(log?: ChannelLogSink): void {
    this.log = log;
  }

  onMessage(listener: (payload: unknown) => void): () => void {
    this.messageListeners.add(listener);
    return () => this.messageListeners.delete(listener);
  }

  onState(listener: (state: "connecting" | "connected" | "disconnected" | "failed") => void): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  start(abortSignal: AbortSignal): void {
    if (abortSignal.aborted) {
      this.close();
      return;
    }
    abortSignal.addEventListener(
      "abort",
      () => {
        this.shutdown();
      },
      { once: true },
    );
    void this.ensureConnected();
  }

  shutdown(): void {
    this.closed = true;
    this.close();
  }

  close(): void {
    this.manualClose = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.connecting = null;
    try {
      this.ws?.close();
    } catch {
      // ignore
    }
    this.ws = null;
    for (const listener of this.stateListeners) listener("disconnected");
  }

  async sendJson(payload: JsonValue): Promise<void> {
    await this.ensureConnected();
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("wechatui ws: not connected");
    }
    this.ws.send(JSON.stringify(payload));
  }

  private scheduleReconnect(): void {
    if (this.closed) return;
    if (this.reconnectTimer) return;
    const delay = Math.min(10_000, this.backoffMs);
    this.backoffMs = Math.min(10_000, Math.floor(this.backoffMs * 1.8));
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.ensureConnected();
    }, delay);
  }

  private async ensureConnected(): Promise<void> {
    if (this.closed) return;
    if (!this.wsUrl) throw new Error("wechatui ws: missing wsUrl");
    if (this.ws && this.ws.readyState === WebSocket.OPEN) return;
    if (this.connecting) return this.connecting;

    this.connecting = new Promise<void>((resolve) => {
      for (const listener of this.stateListeners) listener("connecting");
      const headers: Record<string, string> = {};
      if (this.wsToken) headers.authorization = `Bearer ${this.wsToken}`;

      const ws = new WebSocket(this.wsUrl, { headers });
      this.ws = ws;

      const done = (ok: boolean) => {
        this.connecting = null;
        if (ok) this.backoffMs = 250;
        resolve();
      };

      ws.on("open", () => {
        this.log?.info?.(`[${this.accountId}] [wechatui] ws connected ${this.wsUrl}`);
        for (const listener of this.stateListeners) listener("connected");
        // Best-effort hello; servers that don't understand it can ignore.
        const hello = {
          op: "hello",
          account_id: this.accountId,
          client: "openclaw-wechatui-channel",
          ts_ms: Date.now(),
        };
        try {
          ws.send(JSON.stringify(hello));
        } catch {
          // ignore
        }
        done(true);
      });

      ws.on("message", (data) => {
        const text = typeof data === "string" ? data : data.toString();
        const parsed = safeJsonParse(text);
        for (const listener of this.messageListeners) listener(parsed ?? text);
      });

      ws.on("close", (code, reason) => {
        if (this.ws === ws) this.ws = null;
        const msg = String(reason ?? "");
        this.log?.warn?.(`[${this.accountId}] [wechatui] ws closed code=${code} reason=${msg}`);
        const shouldReconnect = !this.closed && !this.manualClose;
        this.manualClose = false;
        if (shouldReconnect) {
          for (const listener of this.stateListeners) listener("disconnected");
          this.scheduleReconnect();
        }
      });

      ws.on("error", (err) => {
        if (this.ws === ws) this.ws = null;
        this.log?.warn?.(`[${this.accountId}] [wechatui] ws error: ${String(err)}`);
        if (!this.closed) {
          for (const listener of this.stateListeners) listener("failed");
          this.scheduleReconnect();
        }
        done(false);
      });
    });

    return this.connecting;
  }
}

const clients = new Map<string, WeChatUiWsClient>();

export function getWeChatUiWsClient(opts: WsClientOptions): WeChatUiWsClient {
  const key = `${opts.accountId}::${opts.wsUrl}`;
  const existing = clients.get(key);
  if (existing) {
    existing.setLog(opts.log);
    return existing;
  }
  const created = new WeChatUiWsClient(opts);
  clients.set(key, created);
  return created;
}
