// 브리지 /api/events 의 state 이벤트 (src/app-state.cpp ToJson 과 1:1). 비밀은 오지 않는다.
export type Phase = 'idle' | 'starting' | 'live' | 'reconnecting' | 'stopping' | 'error';
export type Tri = boolean | 'unknown';

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
  };
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
}

export type ActionResult = { ok: true } | { ok: false; reason: string };
