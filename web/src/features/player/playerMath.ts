// 계약3 4절(LL-HLS DVR 재생 규약)의 UI 수치·계산.
// 컴포넌트에서 분리한 순수 함수 — 시크바 클릭·드래그 환산은 jsdom 렌더 테스트로
// 검증하기 어려워 여기서 단위 테스트한다 (dockTransition.ts와 같은 이유).

/** DVR 윈도우 상한 — 계약3 4절: 되감기 최대 1:00:00 */
export const LIVE_WINDOW_SECONDS = 3600;

/** 이 값 미만의 시차는 라이브 엣지로 간주하고 0으로 스냅한다 */
export const AT_EDGE_THRESHOLD_SECONDS = 3;

/**
 * liveSyncPosition을 모를 때(Safari 네이티브·MSE 미부착) 라이브 끝에서 물러날 거리 —
 * 정확히 range.end에 붙이면 부분 세그먼트를 기다리며 멎는다
 * (infra/dev-media/player.html의 LIVE_EDGE_BACKOFF와 같은 값).
 * 시차를 이 지점(dvrWindow의 liveEdgePosition) 기준으로 재므로 스냅 직후 시차는 0이고,
 * 따라서 AT_EDGE_THRESHOLD_SECONDS와 묶이지 않는다.
 */
export const LIVE_EDGE_BACKOFF_SECONDS = 3;

/**
 * 키보드 시킹 한 걸음 — 계약3 4절 5번의 되감기 단위(currentTime -= N)다.
 * 시크바(포커스 시)와 플레이어 전역 단축키가 같은 값을 써야 "마우스·키보드 동일"(POK-32)이
 * 유지되므로 상수를 여기 한 곳에 둔다.
 */
export const SEEK_STEP_SECONDS = 10;

/** PageUp/PageDown 큰 스텝 — WAI-ARIA slider 패턴의 선택 항목 */
export const SEEK_PAGE_SECONDS = 60;

/** 재생 중 컨트롤 자동 숨김 지연 (디자인 시안 값) */
export const AUTO_HIDE_MS = 2800;

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/**
 * 되감기 창 정규화 — 비율 계산의 분모라 여기서 한 번만 방어하고 아래 두 함수는 결과만 믿는다.
 * 0·음수·NaN이면 0(되감을 곳 없음), 상한은 계약3의 1시간으로 자른다.
 */
function usableWindow(windowSeconds: number): number {
  if (!Number.isFinite(windowSeconds) || windowSeconds <= 0) return 0;
  return Math.min(LIVE_WINDOW_SECONDS, windowSeconds);
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

/**
 * 시계 표기 → 초. formatUptime의 역함수로 `1:24:03`·`24:03` 둘 다 받는다.
 * 하이라이트 카드의 timestamp가 이미 포맷된 문자열이라(계약 필드) 시점 이동에 되돌릴 값이 필요하다.
 * 시계 표기가 아니면 null — 호출부가 시킹을 건너뛴다.
 */
export function parseClockLabel(label: string): number | null {
  const parts = label.trim().split(':');
  if (parts.length !== 2 && parts.length !== 3) return null;
  if (!parts.every((part) => /^\d{1,3}$/.test(part))) return null;
  const values = parts.map(Number);
  // 뒤 두 칸은 분·초다 — 60 이상이면 시계 표기가 아니다
  if (values.slice(1).some((value) => value >= 60)) return null;
  return values.reduce((acc, value) => acc * 60 + value, 0);
}

/**
 * 시크바 진행 비율 0..1 — 엣지(시차 0)가 1.
 *
 * windowSeconds는 필수 인자다 (계약3 4절 4번, POK-32). 상한을 LIVE_WINDOW_SECONDS로
 * 고정하면 방송이 1시간보다 짧을 때 시크바 왼쪽이 통째로 도달 불가 영역이 된다 —
 * 실제 되감기 폭은 seekable 기준이라 dvrWindow의 rewindWindowSeconds가 정한다.
 * 기본값을 주면 호출부가 빠뜨렸을 때 그 버그가 조용히 되살아난다
 * (dvrWindow.liveEdgePosition의 syncPosition을 필수로 둔 것과 같은 이유).
 */
export function progressFraction(behindSeconds: number, windowSeconds: number): number {
  const w = usableWindow(windowSeconds);
  // 되감을 곳이 없으면(매니페스트 로드 전·방송 시작 직후) 항상 엣지다.
  // 이 방어가 없으면 0/0 = NaN이 CSS width로 나가 트랙이 통째로 사라진다.
  if (w === 0) return 1;
  const s = Math.min(w, Math.max(0, behindSeconds));
  return (w - s) / w;
}

/**
 * 시크바 위치(0..1, 우측이 라이브 엣지) → 시차 초.
 * 엣지 근처(AT_EDGE_THRESHOLD_SECONDS 미만)는 0으로 스냅한다.
 * windowSeconds가 필수인 이유는 progressFraction과 같다.
 */
export function behindFromSeekFraction(fraction: number, windowSeconds: number): number {
  const w = usableWindow(windowSeconds);
  const f = Math.min(1, Math.max(0, fraction));
  const behind = Math.round((1 - f) * w);
  return behind < AT_EDGE_THRESHOLD_SECONDS ? 0 : behind;
}

/**
 * 시크바 포인터 좌표 → 진행 비율 0..1. 폭이 0이면(레이아웃 없음) 계산 불가라 null.
 *
 * 클램프가 필요한 것은 드래그 때문이다 — 포인터 캡처 중엔 트랙 밖으로도 좌표가 오므로
 * 클릭만 있던 시절엔 없던 경로다. DOMRect가 아니라 구조 타입을 받는 이유는
 * dvrWindow의 SeekableLike와 같다 (jsdom엔 레이아웃이 없어 실제 rect를 만들 수 없다).
 */
export function seekFractionFromPointer(
  rect: { left: number; width: number },
  clientX: number,
): number | null {
  if (!(rect.width > 0)) return null;
  return Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
}

/** 라이브 엣지에 있는가 — LIVE 복귀 버튼·진행 트랙 색이 갈리는 기준 */
export function isAtEdge(behindSeconds: number): boolean {
  return behindSeconds < AT_EDGE_THRESHOLD_SECONDS;
}
