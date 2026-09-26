import { takeSseFrames } from './format';
import type { ActionResult, BridgeState, Hello, PluginSettings } from './types';

// 플러그인 루프백 브리지 클라이언트. 토큰은 URL 조각(#token=)으로 받아 sessionStorage 에만 둔다.
export interface Bridge {
  hello(): Promise<Hello>;
  subscribe(onState: (s: BridgeState) => void, onConnection: (up: boolean) => void): () => void;
  pair(code: string): Promise<ActionResult>;
  unpair(): Promise<ActionResult>;
  getSettings(): Promise<PluginSettings>;
  putSettings(next: Partial<PluginSettings>): Promise<ActionResult & { settings?: PluginSettings }>;
}

const TOKEN_KEY = 'pokeclip.bridge.token';

export function readToken(): string | null {
  const match = /(?:^#|&)token=([0-9a-f]{64})(?:&|$)/.exec(window.location.hash);
  if (match) {
    try {
      sessionStorage.setItem(TOKEN_KEY, match[1]);
    } catch {
      /* 저장 실패해도 이번 세션은 쓴다 */
    }
    history.replaceState(null, '', window.location.pathname + window.location.search);
    return match[1];
  }
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

async function readResult(res: Response): Promise<ActionResult> {
  let body: { ok?: boolean; reason?: string; error?: string } = {};
  try {
    body = await res.json();
  } catch {
    /* 본문 없음 */
  }
  if (res.ok) return { ok: true };
  return { ok: false, reason: body.reason ?? body.error ?? `http_${res.status}` };
}

export function createHttpBridge(token: string): Bridge {
  const headers = { Authorization: `Bearer ${token}` };
  const json = { ...headers, 'Content-Type': 'application/json' };

  return {
    async hello() {
      const res = await fetch('/api/hello', { headers, cache: 'no-store' });
      if (!res.ok) throw new Error(`hello ${res.status}`);
      return res.json();
    },

    subscribe(onState, onConnection) {
      let stopped = false;
      let controller: AbortController | null = null;

      const loop = async () => {
        let delay = 500;
        while (!stopped) {
          controller = new AbortController();
          try {
            const res = await fetch('/api/events', { headers, cache: 'no-store', signal: controller.signal });
            if (!res.ok || !res.body) throw new Error(`events ${res.status}`);
            onConnection(true);
            delay = 500;
            const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
            let buffer = '';
            for (;;) {
              const { value, done } = await reader.read();
              if (done) break;
              buffer += value;
              const { frames, rest } = takeSseFrames(buffer);
              buffer = rest;
              for (const f of frames) {
                if (f.event === 'state') onState(JSON.parse(f.data) as BridgeState);
              }
            }
          } catch {
            /* 아래에서 재시도 */
          }
          if (stopped) break;
          onConnection(false);
          await new Promise((r) => setTimeout(r, delay));
          delay = Math.min(delay * 2, 5000);
        }
      };
      void loop();
      return () => {
        stopped = true;
        controller?.abort();
      };
    },

    async pair(code) {
      const res = await fetch('/api/pair', { method: 'POST', headers: json, body: JSON.stringify({ code }) });
      return readResult(res);
    },

    async unpair() {
      const res = await fetch('/api/unpair', { method: 'POST', headers });
      return readResult(res);
    },

    async getSettings() {
      const res = await fetch('/api/config', { headers, cache: 'no-store' });
      if (!res.ok) throw new Error(`config ${res.status}`);
      return res.json();
    },

    async putSettings(next) {
      const res = await fetch('/api/config', { method: 'PUT', headers: json, body: JSON.stringify(next) });
      if (res.ok) return { ok: true, settings: (await res.json()) as PluginSettings };
      return readResult(res);
    },
  };
}
