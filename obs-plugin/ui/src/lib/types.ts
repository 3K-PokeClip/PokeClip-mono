// 브리지 /api/events 의 state 이벤트 (src/app-state.cpp ToJson 과 1:1). 비밀은 오지 않는다.
export type Phase = 'idle' | 'starting' | 'live' | 'reconnecting' | 'stopping' | 'error';
export type Tri = boolean | 'unknown';
export type AudioKind = 'mic' | 'desktop' | 'app' | 'media' | 'browser' | 'other';

// A2 — 트랙 2~6에 실린 소스. 실제 OBS 트랙 체크 기준이라 자동 배정을 꺼도 지금 나가는 그대로다.
export interface AudioRouting {
  known: boolean;
  autoAssign: boolean;
  applied: boolean;
  tracks: { track: number; sources: { name: string; kind: AudioKind }[] }[];
  mixOnly: { name: string }[];
  monitorOnly: { name: string }[];
  overflow: number;
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
}

export type ActionResult = { ok: true } | { ok: false; reason: string };
