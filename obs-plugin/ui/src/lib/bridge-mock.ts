import type { Bridge } from './bridge';
import type { AudioRouting, BridgeState, MarkStats, Phase, PluginSettings } from './types';

// 개발 전용 — `pnpm dev` 에서 토큰 없이 열면 쓴다.
// ?mock=unpaired|idle|live|reconnecting|error|no_key|encoder|audio_overflow|audio_manual|mark_pending|mark_unsupported
// 트랙 2~6 목록으로 오디오 배정 상태를 만든다 (트랙 1은 늘 최종 믹스라 싣지 않는다).
function routing(tracks: AudioRouting['tracks'][number]['sources'][]): AudioRouting {
  return {
    known: true,
    autoAssign: true,
    applied: true,
    tracks: tracks.map((sources, i) => ({ track: i + 2, sources })),
    mixOnly: [],
    monitorOnly: [],
    overflow: 0,
  };
}

const NO_MARKS: MarkStats = { hotkey: '⌃⇧M', sent: 0, pending: 0, failed: 0, seq: 0, result: '', reason: '', lastAt: 0 };

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
    checks: {
      gop2s: 'unknown',
      res1080p: 'unknown',
      sharedEncoder: 'unknown',
      keyintSec: -1,
      width: 0,
      height: 0,
      fps: 0,
      audioTracks: 'unknown',
      audioTrackCount: 0,
    },
    audio: routing([[{ name: '마이크/보조', kind: 'mic' }], [{ name: '데스크탑 오디오', kind: 'desktop' }], [{ name: 'BGM', kind: 'media' }], [], []]),
    marks: NO_MARKS,
  };
  const liveChecks = {
    gop2s: true,
    res1080p: true,
    sharedEncoder: true,
    keyintSec: 2,
    width: 1920,
    height: 1080,
    fps: 60,
    audioTracks: true,
    audioTrackCount: 6,
  } as const;

  const overrides: Record<string, Partial<BridgeState>> = {
    live: { phase: 'live', obsStreaming: true, checks: { ...liveChecks } },
    reconnecting: { phase: 'reconnecting', obsStreaming: true, checks: { ...liveChecks } },
    error: { phase: 'error', errorCode: 'timeout', checks: { ...liveChecks } },
    no_key: { paired: false, keyHint: '', phase: 'idle', errorCode: 'no_key', obsStreaming: true },
    audio_overflow: {
      audio: {
        ...routing([
          [{ name: '마이크/보조', kind: 'mic' }],
          [{ name: '마이크 2', kind: 'mic' }],
          [{ name: '데스크탑 오디오', kind: 'desktop' }],
          [{ name: 'Discord', kind: 'app' }],
          [{ name: 'BGM', kind: 'media' }],
        ]),
        mixOnly: [{ name: '알림' }, { name: '캡처보드' }],
        monitorOnly: [{ name: '효과음 미리듣기' }],
        overflow: 2,
      },
    },
    audio_manual: {
      audio: {
        ...routing([[{ name: '마이크/보조', kind: 'mic' }, { name: '게임', kind: 'app' }], [], [{ name: '데스크탑 오디오', kind: 'desktop' }], [], []]),
        autoAssign: false,
        applied: false,
        mixOnly: [{ name: 'BGM' }],
      },
    },
    // 서버가 첫 조각 전이라 503 — 하나는 다시 보내는 중
    mark_pending: {
      phase: 'live',
      obsStreaming: true,
      checks: { ...liveChecks },
      marks: { ...NO_MARKS, sent: 2, pending: 1, seq: 3, result: 'retrying', reason: 'mark_not_ready' },
    },
    // 서버에 마크 창구가 아직 없다(사유 없는 401·404) · 단축키를 지운 스트리머
    mark_unsupported: {
      phase: 'live',
      obsStreaming: true,
      checks: { ...liveChecks },
      marks: { ...NO_MARKS, hotkey: '', failed: 1, seq: 1, result: 'failed', reason: 'mark_unsupported' },
    },
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
    audio_auto_assign: true,
  };
  let lastMarkAt = 0;
  const listeners = new Set<(s: BridgeState) => void>();
  const emit = (patch: Partial<BridgeState>) => {
    state = { ...state, ...patch, version: state.version + 1 };
    listeners.forEach((l) => l(state));
  };
  const markResult = (patch: Partial<MarkStats>) =>
    emit({ marks: { ...state.marks, ...patch, seq: state.marks.seq + 1 } });

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
    async mark() {
      if (state.phase !== 'live' && state.phase !== 'reconnecting') {
        markResult({ result: 'rejected', reason: 'mark_not_live' });
        return { ok: false, reason: 'mark_not_live' };
      }
      const now = Date.now();
      if (now - lastMarkAt < 2000) return { ok: false, reason: 'mark_too_soon' };
      lastMarkAt = now;
      emit({ marks: { ...state.marks, pending: state.marks.pending + 1, lastAt: now } });
      setTimeout(() => {
        const pending = Math.max(0, state.marks.pending - 1);
        if (scenario === 'mark_unsupported')
          markResult({ pending, failed: state.marks.failed + 1, result: 'failed', reason: 'mark_unsupported' });
        else markResult({ pending, sent: state.marks.sent + 1, result: 'sent', reason: '' });
      }, 400);
      return { ok: true };
    },
  };
}
