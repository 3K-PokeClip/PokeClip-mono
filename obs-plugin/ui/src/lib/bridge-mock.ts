import type { Bridge } from './bridge';
import type { BridgeState, Phase, PluginSettings } from './types';

// 개발 전용 — `pnpm dev` 에서 토큰 없이 열면 쓴다. ?mock=unpaired|idle|live|reconnecting|error|no_key|encoder
export function createMockBridge(scenario: string): Bridge {
  const base: BridgeState = {
    version: 1,
    paired: scenario !== 'unpaired',
    keyHint: scenario !== 'unpaired' ? 'Q7ZK' : '',
    ingest: 'ingest.pokeclip.com:8890',
    apiBase: 'http://dev.pokeclip.com',
    phase: 'idle',
    errorCode: '',
    errorDetail: '',
    obsStreaming: false,
    theme: new URLSearchParams(location.search).get('theme') === 'light' ? 'light' : 'dark',
    stats: { bitrateKbps: 0, totalFrames: 0, droppedFrames: 0, uptimeSec: 0 },
    checks: { gop2s: 'unknown', res1080p: 'unknown', sharedEncoder: 'unknown', keyintSec: -1, width: 0, height: 0, fps: 0 },
  };
  const liveChecks = { gop2s: true, res1080p: true, sharedEncoder: true, keyintSec: 2, width: 1920, height: 1080, fps: 60 } as const;

  const overrides: Record<string, Partial<BridgeState>> = {
    live: { phase: 'live', obsStreaming: true, checks: { ...liveChecks } },
    reconnecting: { phase: 'reconnecting', obsStreaming: true, checks: { ...liveChecks } },
    error: { phase: 'error', errorCode: 'timeout', checks: { ...liveChecks } },
    no_key: { paired: false, keyHint: '', phase: 'idle', errorCode: 'no_key', obsStreaming: true },
    encoder: {
      phase: 'error',
      errorCode: 'encoder_active',
      obsStreaming: true,
      checks: { ...liveChecks, gop2s: false, keyintSec: 0, res1080p: false, width: 1280, height: 720 },
    },
  };
  let state: BridgeState = { ...base, ...(overrides[scenario] ?? {}) };
  let settings: PluginSettings = {
    api_base: 'http://dev.pokeclip.com',
    ingest_host: 'ingest.pokeclip.com',
    ingest_port: 8890,
    send_passphrase: true,
    latency_ms: 1000,
    sync_start: true,
    force_fallback: false,
  };
  const listeners = new Set<(s: BridgeState) => void>();
  const emit = (patch: Partial<BridgeState>) => {
    state = { ...state, ...patch, version: state.version + 1 };
    listeners.forEach((l) => l(state));
  };

  return {
    async hello() {
      return { plugin: 'pokeclip-obs', pluginVersion: '0.2.0', obsVersion: '32.2.2', state };
    },
    subscribe(onState, onConnection) {
      listeners.add(onState);
      onConnection(true);
      onState(state);
      const timer = setInterval(() => {
        if (state.phase !== 'live') return;
        const jitter = 5800 + Math.random() * 900;
        emit({ stats: { ...state.stats, bitrateKbps: jitter, uptimeSec: state.stats.uptimeSec + 1 } });
      }, 1000);
      return () => {
        listeners.delete(onState);
        clearInterval(timer);
      };
    },
    async pair(code) {
      await new Promise((r) => setTimeout(r, 700));
      if (code.startsWith('0000')) return { ok: false, reason: 'expired' };
      emit({ paired: true, keyHint: 'Q7ZK', errorCode: state.errorCode === 'no_key' ? '' : state.errorCode });
      return { ok: true };
    },
    async unpair() {
      const busy: Phase[] = ['starting', 'live', 'reconnecting', 'stopping'];
      if (busy.includes(state.phase)) return { ok: false, reason: 'streaming' };
      emit({ paired: false, keyHint: '' });
      return { ok: true };
    },
    async getSettings() {
      return settings;
    },
    async putSettings(next) {
      settings = { ...settings, ...next };
      emit({ ingest: `${settings.ingest_host}:${settings.ingest_port}`, apiBase: settings.api_base });
      return { ok: true, settings };
    },
  };
}
