import type { RemuxedWindow } from './remuxWindow';

// 창 Blob 시크의 화면 규칙 셋(kty M1 처분 (가) 2026-11-12 · 실측 pokeclip-empirical runs/2026-11-12-prw2-m1 마지막 절).
//   ① 시크 목표는 video.duration − 반 프레임 안으로 묶는다. Firefox 는 출력 앞의 빈 편집 길이(약 79ms)를 duration 에서 빼 끝
//      두 프레임에 닿지 못한다 — 닿는 마지막 프레임을 보인다. 고름은 조각 표로 셈하므로 저장 값은 그대로다.
//   ② 시작 · 정지 자리는 창 첫 영상 프레임(PTS_out) 앞으로 가지 않는다. 그 앞은 빈 편집 구간이라 Safari 는 앞 그림을 남긴다.
//   ③ 시크 착지 = seeked + 프레임 콜백(requestVideoFrameCallback) 도착. mediaTime 값은 비교하지 않는다(Firefox 는 늘 약 +17ms,
//      WebKit 은 늦거나 앞 프레임 값이 온다). 콜백은 시크 전에 건다 — seeked 안에서 걸면 Chrome 은 이미 칠한 프레임이라 안 온다.

/** 창의 Blob 시각 한계 — 첫 영상 프레임 시각과 끝 프레임 길이의 반(초) */
export interface SeekLimits {
  firstFrame: number;
  halfFrame: number;
}

/**
 * 첫 영상 프레임 뒤로 비키는 여유(초). M1 A7 에서 프레임 시작 ±1ms 시크가 세 엔진 모두 그 프레임을 보였고, Safari 는 첫 프레임을
 * PTS_out +0.19ms 부터 보였다. Δ 양자화(약 0.7µs)로 PTS_out 바로 앞에 떨어지는 것도 막는다. 한 프레임(수십 ms)보다 훨씬 작다
 */
export const FIRST_FRAME_MARGIN_SECONDS = 0.001;

export function seekLimitsOf(
  remuxed: Pick<RemuxedWindow, 'shiftSeconds' | 'segments'>,
): SeekLimits {
  const firstPts = Math.min(...remuxed.segments.map((seg) => seg.mediaStart));
  const frames = remuxed.segments.at(-1)?.frames ?? [];
  const last = frames.reduce<{ pts: number; dur: number } | null>(
    (best, frame) => (best === null || frame.pts > best.pts ? frame : best),
    null,
  );
  return { firstFrame: firstPts - remuxed.shiftSeconds, halfFrame: (last?.dur ?? 0) / 2 };
}

/**
 * Blob 시크 목표를 ① · ② 규칙 안으로 묶는다
 * @param duration 영상 노드가 알리는 길이(video.duration). 아직 모르면(NaN) 끝은 묶지 않는다
 */
export function clampSeekBlob(target: number, limits: SeekLimits, duration: number): number {
  const lower = limits.firstFrame + FIRST_FRAME_MARGIN_SECONDS;
  const upper = Number.isFinite(duration) ? duration - limits.halfFrame : Number.POSITIVE_INFINITY;
  return Math.max(0, Math.min(upper, Math.max(lower, target)));
}

/**
 * 영상을 그 Blob 시각으로 옮기고 ③ 착지(seeked + 프레임 콜백)를 기다린다. 시간 안에 착지하지 않거나 오류면 false. 프레임 콜백이
 * 없는 브라우저는 seeked 로 착지한다
 */
export function seekAndLand(
  video: HTMLVideoElement,
  seconds: number,
  timeoutMs: number,
): Promise<boolean> {
  return new Promise((resolve) => {
    const frameCallback = typeof video.requestVideoFrameCallback === 'function';
    let seeked = false;
    let framed = !frameCallback;
    let handle = 0;
    let settled = false;
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      video.removeEventListener('seeked', onSeeked);
      video.removeEventListener('error', onError);
      if (frameCallback && !framed) video.cancelVideoFrameCallback(handle);
      globalThis.clearTimeout(timer);
      resolve(ok);
    };
    const landIfBoth = () => {
      if (seeked && framed) finish(true);
    };
    const onSeeked = () => {
      seeked = true;
      landIfBoth();
    };
    const onError = () => finish(false);
    const timer = globalThis.setTimeout(() => finish(false), timeoutMs);
    video.addEventListener('seeked', onSeeked);
    video.addEventListener('error', onError);
    if (frameCallback) {
      handle = video.requestVideoFrameCallback(() => {
        framed = true;
        landIfBoth();
      });
    }
    video.currentTime = seconds;
  });
}
