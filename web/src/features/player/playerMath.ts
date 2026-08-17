// 계약3 4절(LL-HLS DVR 재생 규약)의 UI 수치·계산.
// 컴포넌트에서 분리한 순수 함수 — 시크바 클릭 환산은 jsdom 렌더 테스트로
// 검증하기 어려워 여기서 단위 테스트한다 (dockTransition.ts와 같은 이유).

/** DVR 윈도우 상한 — 계약3 4절: 되감기 최대 1:00:00 */
export const LIVE_WINDOW_SECONDS = 3600;

/** 이 값 미만의 시차는 라이브 엣지로 간주하고 0으로 스냅한다 */
export const AT_EDGE_THRESHOLD_SECONDS = 3;

/** 재생 중 컨트롤 자동 숨김 지연 (디자인 시안 값) */
export const AUTO_HIDE_MS = 2800;

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/**
 * 라이브 엣지 대비 시차 표기 — 계약3 4절: `-01:23` 형태(분 2자리), 상한 `-1:00:00`.
 * 시안 프로토타입의 `-1:23` 표기 대신 티켓·계약 표기를 따른다.
 */
export function formatBehind(seconds: number): string {
  const s = Math.min(LIVE_WINDOW_SECONDS, Math.max(0, Math.round(seconds)));
  if (s >= LIVE_WINDOW_SECONDS) return '-1:00:00';
  return `-${pad2(Math.floor(s / 60))}:${pad2(s % 60)}`;
}

/** 방송 경과 시간 표기 — `1:24:03`, 1시간 미만은 `24:03` */
export function formatUptime(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s % 60)}` : `${m}:${pad2(s % 60)}`;
}

/** 시크바 진행 비율 0..1 — 엣지(시차 0)가 1 */
export function progressFraction(behindSeconds: number): number {
  const s = Math.min(LIVE_WINDOW_SECONDS, Math.max(0, behindSeconds));
  return (LIVE_WINDOW_SECONDS - s) / LIVE_WINDOW_SECONDS;
}

/**
 * 시크바 클릭 위치(0..1, 우측이 라이브 엣지) → 시차 초.
 * 엣지 근처(AT_EDGE_THRESHOLD_SECONDS 미만)는 0으로 스냅한다.
 */
export function behindFromSeekFraction(fraction: number): number {
  const f = Math.min(1, Math.max(0, fraction));
  const behind = Math.round((1 - f) * LIVE_WINDOW_SECONDS);
  return behind < AT_EDGE_THRESHOLD_SECONDS ? 0 : behind;
}

/** 라이브 엣지에 있는가 — LIVE 복귀 버튼·진행 트랙 색이 갈리는 기준 */
export function isAtEdge(behindSeconds: number): boolean {
  return behindSeconds < AT_EDGE_THRESHOLD_SECONDS;
}
