// 편집기 재생의 계약과 구간 경계 판정 (POK-238).
//
// 재생은 두 갈래다 — 목업 시뮬레이션(미디어 없음)과 녹화 재생 서버에서 받은 mp4 한 덩어리(useEditorVideoPlayback, POK-251).
// 화면과 상태 허브는 어느 쪽인지 모르고 이 계약만 본다 (usePlayerSimulation ↔ useHlsPlayback 이 라이브에서 하는 것과
// 같은 구조).
//
// 경계 판정을 여기 순수 함수로 둔 이유: 두 갈래가 같은 규칙을 두 번 적으면 언젠가 갈라진다.
// 게다가 실재생 쪽은 jsdom 에서 영상을 못 틀어 렌더 테스트로 못 잡는다 — 규칙만 떼어내면 표로 검증할 수 있다
// (timelineMath·playerMath 와 같은 이유).

/** 재생이 갇히는 구간. 화면의 「구간 반복」 스위치가 loop 를 정한다 */
export interface PlaybackBounds {
  startSeconds: number;
  endSeconds: number;
  loop: boolean;
}

export type BoundaryAction =
  { kind: 'continue' } | { kind: 'seek'; toSeconds: number } | { kind: 'stop'; atSeconds: number };

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

/** 영상 끝 바로 앞에서 경계를 잡는 여유(초). 끝에 딱 닿으면 브라우저가 먼저 멈춰(ended) 되감을 틱이 없다 */
export const MEDIA_END_MARGIN_SECONDS = 0.05;

/**
 * 받은 영상이 구간 끝보다 먼저 끝나면 영상 끝을 구간 끝으로 본다(POK-490). 녹화 끝·녹화 조각 끝에서 창이 잘리면
 * 재생 위치가 구간 끝에 영영 닿지 않아, 「구간 반복」을 켜 둬도 영상 끝에서 그냥 멈췄다.
 * 영상 길이를 아직 모르거나(NaN · Infinity) 영상이 구간 시작 앞에서 끝나면 그대로 둔다 — 시작과 끝이 겹치면 매 틱 되감는다.
 */
export function effectiveBounds(bounds: PlaybackBounds, mediaEndSeconds: number): PlaybackBounds {
  const end = mediaEndSeconds - MEDIA_END_MARGIN_SECONDS;
  if (!Number.isFinite(end) || end >= bounds.endSeconds || end <= bounds.startSeconds)
    return bounds;
  return { ...bounds, endSeconds: end };
}

/** 재생을 못 하는 이유 — 화면이 안내 문구를 고르는 데 쓴다 */
export type PlaybackError =
  /** 이 브라우저가 HLS 를 못 푼다 (hls.js 미지원 + 네이티브 HLS 없음) */
  | 'unsupported'
  /** 복구를 시도했는데도 재생이 끊겼다 */
  | 'fatal'
  /** 출입증을 다시 받아도 녹화가 403이다 */
  | 'access'
  /** 이 구간에 녹화가 없다(녹화 틈 안) */
  | 'no-recording';

/** 미리보기 아래에 보이는 안내(POK-389). 출입증을 못 받았을 때의 열기 실패 문구와 같은 결로 쓴다 */
export function playbackErrorMessage(error: PlaybackError): string {
  switch (error) {
    case 'access':
      return '영상 출입증이 거절됐어요. 잠시 뒤 다시 열어 주세요';
    case 'no-recording':
      return '이 구간의 녹화가 없어 미리보기를 못 틀어요';
    case 'unsupported':
      return '이 브라우저는 미리보기 영상을 못 틀어요';
    case 'fatal':
      return '미리보기 영상을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요';
  }
}

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
