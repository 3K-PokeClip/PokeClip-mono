// 브리지 /api/events 의 state 이벤트 (src/app-state.cpp ToJson 과 1:1). 비밀은 오지 않는다.
export type Phase = 'idle' | 'starting' | 'live' | 'reconnecting' | 'stopping' | 'error';
export type Tri = boolean | 'unknown';
export type AudioKind = 'mic' | 'desktop' | 'app' | 'media' | 'browser' | 'other';

// A2 — 트랙 2~6에 실린 소스. 실제 OBS 트랙 체크 기준이라 자동 배정을 꺼도 지금 나가는 그대로다.
export interface AudioRouting {
  known: boolean;
  autoAssign: boolean;
  applied: boolean;
  deferred: boolean; // 방송·녹화 중에 켜져 끝날 때까지 미뤘다 — 지금 나가는 트랙은 그대로
  // mainStream — 본방(OBS 방송 출력)이 이 트랙을 쓴다. 자동 배정이 손대지 않는 스트리머의 믹스다.
  tracks: { track: number; mainStream: boolean; sources: { name: string; kind: AudioKind }[] }[];
  mixOnly: { name: string }[];
  monitorOnly: { name: string }[];
  overflow: number;
}

// A4 — 핫키 마킹. seq가 오를 때마다 결과 토스트를 한 번 띄운다 (src/app-state.hpp MarkStats).
export type MarkResult = '' | 'sent' | 'retrying' | 'failed' | 'rejected';
export interface MarkStats {
  hotkey: string; // OBS 표기 그대로 (macOS ⌃⇧M · Windows Ctrl + Shift + M). 빈 문자열이면 안 묶였다
  sent: number;
  pending: number;
  failed: number;
  seq: number;
  result: MarkResult;
  reason: string;
  lastAt: number;
}

export interface BridgeState {
  version: number;
  paired: boolean;
  keyHint: string;
  ingest: string;
  apiBase: string;
  phase: Phase;
  errorCode: string;
  errorDetail: string;
  obsStreaming: boolean;
  theme: 'dark' | 'light';
  stats: {
    bitrateKbps: number;
    totalFrames: number;
    droppedFrames: number;
    uptimeSec: number;
  };
  checks: {
    gop2s: Tri;
    res1080p: Tri;
    sharedEncoder: Tri;
    keyintSec: number;
    width: number;
    height: number;
    fps: number;
    audioTracks: Tri;
    audioTrackCount: number;
  };
  audio: AudioRouting;
  marks: MarkStats;
}

export interface Hello {
  plugin: string;
  pluginVersion: string;
  obsVersion: string;
  state: BridgeState;
}

export interface PluginSettings {
  api_base: string;
  ingest_host: string;
  ingest_port: number;
  send_passphrase: boolean;
  latency_ms: number;
  sync_start: boolean;
  force_fallback: boolean;
  audio_auto_assign: boolean;
  clip_api_base?: string; // 개발용 — 화면에 없다. 비우면 api_base
}

export type ActionResult = { ok: true } | { ok: false; reason: string };

// A4 「지금 표시」 응답. 409(거절)는 플러그인이 state.marks로도 알리므로 상태 코드를 같이 넘긴다.
export type MarkReply = ActionResult & { status: number };
