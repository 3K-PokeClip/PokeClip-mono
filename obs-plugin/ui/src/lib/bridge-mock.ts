import type { Bridge } from './bridge';
import type { AudioKind, AudioRouting, AudioSource, BridgeState, MarkStats, Phase, PluginSettings, RetryView } from './types';

// 개발 전용 — `pnpm dev` 에서 토큰 없이 열면 쓴다.
// ?mock=unpaired|idle|live|reconnecting|error|no_key|encoder|audio_overflow|audio_grouped|audio_live|audio_manual|
//       audio_main_stream|audio_deferred|audio_prompt|audio_prompt_custom|mark_pending|mark_unsupported|
//       retry_first|retry_key|gave_up|paired_midstream
// 트랙 2~6 목록으로 오디오 배정 상태를 만든다 (트랙 1은 늘 최종 믹스라 싣지 않는다). mainStream은 본방 트랙 번호.
const src = (key: string, name: string, kind: AudioKind): AudioSource => ({ key, name, kind });
const MIC = src('ch:3', '마이크/보조', 'mic');
const MIC2 = src('uuid:mic2', '마이크 2', 'mic');
const DESK = src('ch:1', '데스크탑 오디오', 'desktop');
const DISCORD = src('uuid:discord', 'Discord', 'app');
const GAME = src('uuid:game', '게임', 'app');
const BGM = src('uuid:bgm', 'BGM', 'media');
const ALERT = src('uuid:alert', '알림', 'browser');

function routing(tracks: AudioSource[][], mainStream: number[] = []): AudioRouting {
  return {
    known: true,
    autoAssign: true,
    applied: true,
    deferred: false,
    prompt: false,
    customRouting: false,
    locked: false,
    tracks: tracks.map((sources, i) => ({ track: i + 2, mainStream: mainStream.includes(i + 2), sources })),
    mixOnly: [],
    monitorOnly: [],
    overflow: 0,
  };
}

const NO_MARKS: MarkStats = { hotkey: '⌃⇧M', sent: 0, pending: 0, failed: 0, seq: 0, result: '', reason: '', lastAt: 0 };
const NO_RETRY: RetryView = { attempt: 0, nextAt: 0, gaveUp: false, keySuspect: false };
const MOCK_RETRY_INTERVAL_MS = 5000; // 300초 창 안의 간격과 같다

export function createMockBridge(scenario: string): Bridge {
  const base: BridgeState = {
    version: 1,
    paired: scenario !== 'unpaired',
    keyHint: scenario !== 'unpaired' ? 'Q7ZK' : '',
    ingest: 'ingest.pokeclip.com:8890',
    apiBase: 'https://dev.pokeclip.com',
    phase: 'idle',
    errorCode: '',
    errorDetail: '',
    obsStreaming: false,
    syncStart: true,
    canSendNow: false,
    retry: NO_RETRY,
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
    audio: routing([[MIC], [DESK], [BGM], [], []]),
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
    live: { phase: 'live', obsStreaming: true, checks: { ...liveChecks }, audio: { ...base.audio, locked: true } },
    // 붙어 있다 끊겨 다시 붙는 중 — 3번째 재시도를 12초 뒤에 한다
    reconnecting: {
      phase: 'reconnecting',
      obsStreaming: true,
      errorCode: 'disconnected',
      checks: { ...liveChecks },
      retry: { ...NO_RETRY, attempt: 3, nextAt: Date.now() + 12000 },
      audio: { ...base.audio, locked: true },
    },
    // 처음부터 붙지 못했다(수신 주소를 못 찾음) — 지금 1번째 재시도로 접속 중
    retry_first: { phase: 'starting', obsStreaming: true, errorCode: 'bad_path', retry: { ...NO_RETRY, attempt: 1 } },
    // 서버가 살아 있는데 계속 거절한다 — 키 확인 안내
    retry_key: {
      phase: 'starting',
      obsStreaming: true,
      errorCode: 'connect_failed',
      retry: { ...NO_RETRY, attempt: 5, nextAt: Date.now() + 4000, keySuspect: true },
    },
    // 65분을 다 써서 포기했다 — 본방은 나가는 중이라 「다시 연결」
    gave_up: {
      phase: 'error',
      obsStreaming: true,
      errorCode: 'connect_failed',
      canSendNow: true,
      checks: { ...liveChecks },
      retry: { ...NO_RETRY, gaveUp: true },
    },
    // 페어링 없이 본방을 시작한 뒤 방송 중에 페어링했다 — 우리 송출은 아직 시작 전
    paired_midstream: { phase: 'idle', obsStreaming: true, canSendNow: true },
    error: { phase: 'error', errorCode: 'timeout', checks: { ...liveChecks } },
    no_key: { paired: false, keyHint: '', phase: 'idle', errorCode: 'no_key', obsStreaming: true },
    // 소스 7개 — 자리가 모자라면 같은 종류끼리 묶는다(마이크 2는 마이크 트랙, 게임은 앱 트랙)
    audio_overflow: {
      audio: {
        ...routing([[MIC, MIC2], [DESK], [DISCORD, GAME], [BGM], [ALERT]]),
        monitorOnly: [{ name: '효과음 미리듣기' }],
      },
    },
    // 독에서 BGM과 알림을 트랙 4에 묶었고 게임은 트랙에서 뺐다 — 「트랙 없음」 경고가 보인다
    audio_grouped: {
      audio: { ...routing([[MIC], [DESK], [BGM, ALERT], [], []]), mixOnly: [GAME] },
    },
    // 같은데 방송 중 — 「+」나 ×를 누르면 지금 나가는 트랙이 바뀐다고 한 번 묻는다
    audio_live: {
      phase: 'live',
      obsStreaming: true,
      checks: { ...liveChecks },
      audio: { ...routing([[MIC], [DESK], [BGM], [], []]), mixOnly: [ALERT], locked: true },
    },
    audio_manual: {
      audio: {
        ...routing([[MIC, GAME], [], [DESK], [], []]),
        autoAssign: false,
        applied: false,
        mixOnly: [BGM],
      },
    },
    // 고급 출력에서 방송 트랙을 2로 둔 스트리머 — 트랙 2는 스트리머가 짠 본방 믹스라 자동 배정이 비켜 간다
    audio_main_stream: {
      audio: routing([[MIC, DESK], [MIC], [DESK], [BGM], []], [2]),
    },
    // 막 페어링했다 — 자동 배정은 기본으로 꺼져 있어 한 번 묻는다. 트랙은 OBS 기본(전부 켜짐)
    audio_prompt: {
      audio: {
        ...routing([0, 1, 2, 3, 4].map(() => [MIC, DESK])),
        autoAssign: false,
        applied: false,
        prompt: true,
      },
    },
    // 같은데 스트리머가 트랙 2~6을 직접 짜 뒀다 — 켜면 덮어쓴다고 알린다
    audio_prompt_custom: {
      audio: {
        ...routing([[MIC, DISCORD], [GAME], [], [], []]),
        autoAssign: false,
        applied: false,
        prompt: true,
        customRouting: true,
      },
    },
    // 녹화 중에 자동 배정을 켰다 — 지금 나가는 트랙은 그대로 두고 녹화가 끝나면 적용한다
    audio_deferred: {
      obsStreaming: false,
      audio: {
        ...routing([[MIC, GAME], [], [], [], []]),
        applied: false,
        deferred: true,
        locked: true,
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
    api_base: 'https://dev.pokeclip.com',
    ingest_host: 'ingest.pokeclip.com',
    ingest_port: 8890,
    send_passphrase: true,
    latency_ms: 1000,
    sync_start: true,
    force_fallback: false,
    audio_auto_assign: state.audio.autoAssign,
    audio_assign_prompted: !state.audio.prompt,
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
        // 재시도를 기다리던 시각이 지났다 — 또 실패한 것으로 치고 다음 시도를 잡는다
        if (state.retry.attempt > 0 && state.retry.nextAt > 0 && Date.now() >= state.retry.nextAt) {
          emit({ retry: { ...state.retry, attempt: state.retry.attempt + 1, nextAt: Date.now() + MOCK_RETRY_INTERVAL_MS } });
          return;
        }
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
      emit({
        paired: true,
        keyHint: 'Q7ZK',
        errorCode: state.errorCode === 'no_key' ? '' : state.errorCode,
        // 플러그인처럼: 본방이 나가는 중에 페어링했으면 「다시 연결」이 뜬다
        canSendNow: state.obsStreaming && (state.phase === 'idle' || state.phase === 'error'),
      });
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
      // 플러그인처럼 API 주소는 브리지로 안 바뀐다 — passphrase가 가는 곳이다
      const { api_base: _api, clip_api_base: _clip, ...editable } = next;
      const autoWas = settings.audio_auto_assign;
      settings = { ...settings, ...editable };
      // 플러그인처럼: 스위치를 바꿨으면 처음 안내에 답한 것으로 친다
      if (settings.audio_auto_assign !== autoWas) settings.audio_assign_prompted = true;
      const answered = settings.audio_assign_prompted === true;
      emit({
        ingest: `${settings.ingest_host}:${settings.ingest_port}`,
        audio: {
          ...state.audio,
          autoAssign: settings.audio_auto_assign,
          applied: settings.audio_auto_assign && state.paired,
          prompt: state.audio.prompt && !answered && !settings.audio_auto_assign,
          customRouting: settings.audio_auto_assign ? false : state.audio.customRouting,
        },
      });
      return { ok: true, settings };
    },
    async mark() {
      if (state.phase !== 'live' && state.phase !== 'reconnecting') {
        markResult({ result: 'rejected', reason: 'mark_not_live' });
        return { ok: false, reason: 'mark_not_live', status: 409 };
      }
      const now = Date.now();
      if (now - lastMarkAt < 2000) return { ok: false, reason: 'mark_too_soon', status: 429 };
      lastMarkAt = now;
      emit({ marks: { ...state.marks, pending: state.marks.pending + 1, lastAt: now } });
      setTimeout(() => {
        const pending = Math.max(0, state.marks.pending - 1);
        if (scenario === 'mark_unsupported')
          markResult({ pending, failed: state.marks.failed + 1, result: 'failed', reason: 'mark_unsupported' });
        else markResult({ pending, sent: state.marks.sent + 1, result: 'sent', reason: '' });
      }, 400);
      return { ok: true, status: 202 };
    },
    async sendNow() {
      const connect = () =>
        emit({ phase: 'live', errorCode: '', canSendNow: false, retry: NO_RETRY, checks: { ...liveChecks } });
      // 다음 재시도를 기다리던 중 — 기다림만 건너뛰고 곧 붙는다
      if (state.retry.attempt > 0 && state.retry.nextAt > 0) {
        emit({ retry: { ...state.retry, nextAt: 0 } });
        setTimeout(connect, 800);
        return { ok: true };
      }
      if (!state.obsStreaming) return { ok: false, reason: 'main_not_live' };
      if (!state.canSendNow) return { ok: false, reason: 'send_unavailable' };
      emit({ phase: 'starting', errorCode: '', canSendNow: false, retry: NO_RETRY });
      setTimeout(connect, 800);
      return { ok: true };
    },
    async stopRetry() {
      if (state.retry.attempt === 0) return { ok: false, reason: 'not_retrying' };
      // 마지막 실패 사유는 남는다 — 왜 멈춰 있는지가 그것이다
      emit({ phase: 'error', retry: NO_RETRY, canSendNow: state.obsStreaming });
      return { ok: true };
    },
    // 플러그인처럼: 적용 중일 때만, 본방 트랙은 거절, 찬 트랙을 골라도 밀어내지 않고 묶는다. null은 트랙에서 뺀다(트랙 없음).
    async assignAudio(key, track) {
      if (!state.audio.applied) return { ok: false, reason: 'audio_off' };
      const all = [...state.audio.tracks.flatMap((t) => t.sources), ...state.audio.mixOnly];
      const found = all.find((s) => s.key === key);
      if (!found) return { ok: false, reason: 'unknown_source' };
      if (track !== null && state.audio.tracks[track - 2]?.mainStream) return { ok: false, reason: 'main_stream_track' };
      await new Promise((r) => setTimeout(r, 300));
      const tracks = state.audio.tracks.map((t) => {
        if (t.mainStream) return t;
        const sources = t.sources.filter((s) => s.key !== key);
        return { ...t, sources: t.track === track ? [...sources, found] : sources };
      });
      const rest = state.audio.mixOnly.filter((s) => s.key !== key);
      emit({ audio: { ...state.audio, tracks, mixOnly: track === null ? [...rest, found] : rest } });
      return { ok: true };
    },
  };
}
