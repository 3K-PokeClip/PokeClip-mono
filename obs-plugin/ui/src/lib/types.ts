// 브리지 /api/events 의 state 이벤트 (src/app-state.cpp ToJson 과 1:1). 비밀은 오지 않는다.
export type Phase = 'idle' | 'starting' | 'live' | 'reconnecting' | 'stopping' | 'error';
export type Tri = boolean | 'unknown';
export type AudioKind = 'mic' | 'desktop' | 'app' | 'media' | 'browser' | 'other';

// A2 — 트랙 2~6에 실린 소스. 실제 OBS 트랙 체크 기준이라 자동 배정을 꺼도 지금 나가는 그대로다.
// key는 플러그인이 소스를 기억하는 열쇠("ch:N" 전역 장치 · "uuid:…") — 손 배정(POK-266)이 이것으로 보낸다.
export interface AudioSource {
  key: string;
  name: string;
  kind: AudioKind;
}
export interface AudioRouting {
  known: boolean;
  autoAssign: boolean;
  applied: boolean;
  deferred: boolean; // 방송·녹화 중에 켜져 끝날 때까지 미뤘다 — 지금 나가는 트랙은 그대로
  prompt: boolean; // 페어링 뒤 아직 자동 배정을 켤지 묻지 않았다 — 독이 한 번 묻는다(기본은 꺼짐)
  customRouting: boolean; // 스트리머가 트랙 2~6을 직접 짜 둔 흔적 — 켜면 덮어쓴다고 알린다
  locked: boolean; // 방송·녹화·리플레이 버퍼·전송 중 — 손 배정 전에 한 번 확인한다(지금 나가는 트랙이 바뀐다)
  // mainStream — 본방(OBS 방송 출력)이 이 트랙을 쓴다. 자동 배정이 손대지 않는 스트리머의 믹스다.
  tracks: { track: number; mainStream: boolean; sources: AudioSource[] }[];
  mixOnly: AudioSource[]; // 트랙 없음 — 어느 스템에도 없어 본방 믹스에만 섞인다(독에서 뺐거나 자리를 못 받았다)
  monitorOnly: { name: string }[];
  overflow: number; // 트랙 2~6이 전부 본방 트랙이라 앉을 곳이 없는 수

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

// A5 — 자동 재시도 진행. attempt가 0이면 재시도 중이 아니다 (src/app-state.hpp RetryView).
export interface RetryView {
  attempt: number; // 끊긴 뒤 몇 번째 재시도인가(1부터)
  nextAt: number; // 다음 시도 시각(UTC epoch ms). 0이면 지금 접속 중
  gaveUp: boolean; // 정책 시간(65분)을 다 써서 멈췄다 — phase는 error
  keySuspect: boolean; // 서버가 살아 있는데 거절이 이어진다 — 키 상태 확인 안내
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
  syncStart: boolean; // 설정 sync_start 사본
  // 본방은 나가는데 우리 송출이 멈춰 있다 — 「다시 연결」을 보여 준다 (src/app-state.hpp CanSendNow)
  canSendNow: boolean;
  retry: RetryView;
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
  audio_assign_prompted?: boolean; // 처음 안내(자동 배정 켤까요?)에 답했다
  clip_api_base?: string; // 개발용 — 화면에 없다. 비우면 api_base
}

export type ActionResult = { ok: true } | { ok: false; reason: string };

// A4 「지금 표시」 응답. 409(거절)는 플러그인이 state.marks로도 알리므로 상태 코드를 같이 넘긴다.
export type MarkReply = ActionResult & { status: number };
