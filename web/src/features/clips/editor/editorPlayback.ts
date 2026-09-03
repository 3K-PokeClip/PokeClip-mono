// 편집기 재생의 계약과 구간 경계 판정 (POK-238).
//
// 재생은 두 갈래다 — 목업 시뮬레이션(미디어 없음)과 hls.js VOD(로컬 소스). 화면과 상태 허브는
// 어느 쪽인지 모르고 이 계약만 본다 (usePlayerSimulation ↔ useHlsPlayback 이 라이브에서 하는 것과
// 같은 구조).
//
// 경계 판정을 여기 순수 함수로 둔 이유: 두 갈래가 같은 규칙을 두 번 적으면 언젠가 갈라진다.
// 게다가 hls 쪽은 jsdom 에서 못 돌려 렌더 테스트로 못 잡는다 — 규칙만 떼어내면 표로 검증할 수 있다
// (timelineMath·playerMath 와 같은 이유).

/** 재생이 갇히는 구간. 화면의 「구간 반복」 스위치가 loop 를 정한다 */
export interface PlaybackBounds {
  startSeconds: number;
  endSeconds: number;
  loop: boolean;
}

export type BoundaryAction =
  | { kind: 'continue' }
  | { kind: 'seek'; toSeconds: number }
  | { kind: 'stop'; atSeconds: number };

/**
 * 지금 위치가 구간을 벗어났는지, 벗어났으면 무엇을 해야 하는지.
 *
 * 구간 **앞**도 본다 — 끝만 보면 「구간 반복」을 켜 둔 채로 클립 밖을 재생하게 된다.
 * 반복이 꺼져 있으면 앞쪽은 그냥 둔다: 사용자가 일부러 앞으로 시킹해 앞뒤 맥락을 보는 중일 수 있다.
 */
export function boundaryAction(currentSeconds: number, bounds: PlaybackBounds): BoundaryAction {
  const { startSeconds, endSeconds, loop } = bounds;
  if (loop && currentSeconds < startSeconds) return { kind: 'seek', toSeconds: startSeconds };
  if (currentSeconds >= endSeconds) {
    return loop
      ? { kind: 'seek', toSeconds: startSeconds }
      : { kind: 'stop', atSeconds: endSeconds };
  }
  return { kind: 'continue' };
}

/** 재생을 못 하는 이유 — 화면이 안내 문구를 고르는 데 쓴다 */
export type PlaybackError =
  /** 이 브라우저가 HLS 를 못 푼다 (hls.js 미지원 + 네이티브 HLS 없음) */
  | 'unsupported'
  /** 복구를 시도했는데도 재생이 끊겼다 */
  | 'fatal';

/**
 * 편집기가 재생에 대해 아는 전부.
 *
 * 함수는 전부 신원이 안정적이어야 한다(useCallback). 상태 허브가 effect 의존성에 얹기 때문에,
 * 렌더마다 새로 만들면 setBounds 가 매 렌더 돈다.
 */
export interface EditorPlayback {
  playing: boolean;
  /** 지금 위치(초). 소스 로컬 시간축이다 — 방송 절대축이 아니다 */
  currentSeconds: number;
  durationSeconds: number;
  error: PlaybackError | null;
  togglePlay: () => void;
  seekTo: (seconds: number) => void;
  seekBy: (deltaSeconds: number) => void;
  setRate: (rate: number) => void;
  /** 구간과 반복 여부를 알린다. 즉시 움직이지 않고 다음 재생 틱이 처리한다 */
  setBounds: (bounds: PlaybackBounds) => void;
}

export interface EditorPlaybackOptions {
  durationSeconds: number;
  /** 마운트 시점 위치 — 보통 선택 구간의 시작 */
  initialSeconds: number;
}
