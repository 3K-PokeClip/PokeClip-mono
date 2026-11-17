// 영상이 지금 화면에 내는 자리를 잰다(POK-196). 트랙 소리는 이 자리에 닻을 내리고 이 자리와 견주어 어긋남을 잰다.
// currentTime 은 엔진마다 거칠고 늦게 갱신된다 — 실측 M6 r2(2026-11-13 · pokeclip-empirical
// runs/2026-11-13-prw2-m6-r2)에서 Firefox 는 잡음 MAD 약 10ms 였고(README 36줄), 그 값에 소리를 다시 건 뒤 20ms 에 잰
// 영상이 건 자리보다 64–444ms 앞서 있었다(results/summary.txt Firefox v2 「닻 뒤」 줄의 +20ms 값). 그래서 그려진 프레임의 미디어
// 시각(requestVideoFrameCallback 의 mediaTime)과 그 프레임이 보이는 시각(expectedDisplayTime)을 잡아 두고, 그 뒤 흐른 시간만큼
// 민다. 두 값은 프레임에 붙어 오므로 콜백이 늦게 돌아도 낡지 않는다(콜백이 도는 시각 now 를 쓰면 막힌 만큼 뒤처진다). 프레임
// 콜백이 없거나 이어짐이 끊긴 뒤 새 프레임이 아직 없으면 currentTime 으로 잰다.
// 한계: Firefox 는 mediaTime 이 그린 프레임과 한 프레임(22–33ms) 어긋나는 회차가 있었다(실측 M6 1회차 README 21줄). 이 시계는
// 그것을 보정하지 않는다.

/** 영상이 얼마나 나아갔는지의 표지. 두 표지가 같으면(sameMark) 그 사이 영상이 서 있었다 */
export interface ClockMark {
  /** 받은 장면 수(미디어 시각이 바뀐 프레임 콜백 수). 프레임 콜백이 없으면 늘 0 */
  readonly frames: number;
  readonly currentTime: number;
}

export interface VideoClock {
  /** 지금 화면에 나가는 자리(영상 초) */
  position(): number;
  mark(): ClockMark;
  /** 앞 프레임과 이어지지 않는다(멈춤을 알아챘다): 그 뒤에 보인 프레임이 올 때까지 currentTime 으로 잰다 */
  restart(): void;
  dispose(): void;
}

/** 장면도 currentTime 도 안 움직였다. 한쪽만 서면 흐르는 것이다: 숨은 탭은 프레임 콜백이 안 돌고, Firefox currentTime 은 거칠다 */
export function sameMark(a: ClockMark, b: ClockMark): boolean {
  return a.frames === b.frames && a.currentTime === b.currentTime;
}

/** 이 사건 뒤의 장면은 앞 장면과 이어지지 않는다(옮김 · 멈춤 · 재생 시작 · 기다림 · 끝 · 다시 받기) */
const BREAK_EVENTS = ['seeking', 'pause', 'play', 'waiting', 'ended', 'emptied'] as const;

/**
 * @param now 프레임 시각(DOMHighResTimeStamp)과 같은 시계의 지금 ms
 */
export function frameClock(
  video: HTMLVideoElement,
  now: () => number = () => performance.now(),
): VideoClock {
  // 기준점: 이 미디어 시각이 displayMs 에 보였고 그 뒤 rate 배속으로 흐른다. 배속은 기준점과 함께 둔다: 지금 배속을 곱하면
  // 배속을 바꾸는 순간 기준점 뒤 흐른 시간 전체가 새 배속으로 다시 셈해져 자리가 뛴다(리뷰 M6 2차 r1)
  let frame: { mediaTime: number; displayMs: number; rate: number } | null = null;
  let lastMediaTime: number | null = null;
  let frames = 0;
  // 이 시각보다 앞에 보인 장면은 쓰지 않는다: 끊기기 전 장면이거나 배속을 바꾸기 전 장면이다
  let floorMs = Number.NEGATIVE_INFINITY;
  let handle: number | null = null;

  const onFrame: VideoFrameRequestCallback = (_callbackNow, metadata) => {
    handle = video.requestVideoFrameCallback(onFrame);
    // 끊기기 전(또는 배속을 바꾸기 전)에 보인 장면의 콜백이 늦게 돌았다. 장면 수에도 넣지 않는다: 세면 멈춘 영상이 다시
    // 흐른다는 표지가 된다(리뷰 M6 2차 r1)
    if (metadata.expectedDisplayTime < floorMs) return;
    if (metadata.mediaTime === lastMediaTime) return; // 같은 장면
    lastMediaTime = metadata.mediaTime;
    frames += 1;
    frame = {
      mediaTime: metadata.mediaTime,
      displayMs: metadata.expectedDisplayTime,
      rate: video.playbackRate,
    };
  };
  if (typeof video.requestVideoFrameCallback === 'function') {
    handle = video.requestVideoFrameCallback(onFrame);
  }

  const position = () => {
    if (frame === null) return video.currentTime;
    return frame.mediaTime + ((now() - frame.displayMs) / 1000) * frame.rate;
  };
  const restart = () => {
    frame = null;
    floorMs = now();
  };
  // 배속을 바꿨다: 지금까지는 옛 배속으로 셈한 자리를 지금 시각의 기준점으로 삼고 그 뒤로 새 배속을 곱한다
  const rebase = () => {
    const at = now();
    if (frame !== null) frame = { mediaTime: position(), displayMs: at, rate: video.playbackRate };
    floorMs = at;
  };
  for (const name of BREAK_EVENTS) video.addEventListener(name, restart);
  video.addEventListener('ratechange', rebase);

  return {
    position,
    mark: () => ({ frames, currentTime: video.currentTime }),
    restart,
    dispose: () => {
      for (const name of BREAK_EVENTS) video.removeEventListener(name, restart);
      video.removeEventListener('ratechange', rebase);
      if (handle !== null) video.cancelVideoFrameCallback(handle);
    },
  };
}
