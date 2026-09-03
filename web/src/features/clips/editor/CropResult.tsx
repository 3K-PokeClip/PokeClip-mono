'use client';

import { useEffect, useRef, type RefObject } from 'react';
import type { CropRect } from './cropMath';
import styles from './editorShared.module.css';

// 잡은 영역이 실제로 어떻게 나오는지 보여주는 칸 (POK-109).
//
// **캔버스인 이유**: 상하분할은 같은 영상의 두 영역을 동시에 보여줘야 하는데 `<video>` 하나를
// 두 곳에 놓을 수 없다. 두 번째 인스턴스를 띄우면 디코더가 두 벌 돌고 대역폭도 두 배다.
// 캔버스로 그리면 디코더 하나로 원하는 만큼 잘라 그릴 수 있고, 잘라내는 좌표가 계약6 crop 그대로다.
//
// 원본이 MediaSource(blob:)라 캔버스가 오염되지 않는다. 네이티브 HLS(Safari) 경로에서는 오염되지만
// 픽셀을 되읽지 않으므로 그리는 데는 지장이 없다.

/** 정지 중에도 이 간격으로 한 번 더 그린다 — 시킹·크롭 변경이 프레임에 바로 반영되게 */
const IDLE_REDRAW_MS = 120;

export function CropResult({
  videoRef,
  crop,
  label,
  flex,
}: {
  videoRef: RefObject<HTMLVideoElement | null> | undefined;
  crop: CropRect | undefined;
  label: string;
  /** 상하분할이면 세로 지분 — 칸 높이를 나눠 가진다 */
  flex: number;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  // 그리는 루프가 최신 crop 을 보게 한다 — crop 이 바뀔 때마다 루프를 다시 걸면
  // 드래그하는 동안 rAF 가 계속 끊겼다 붙는다
  const cropRef = useRef(crop);
  cropRef.current = crop;

  useEffect(() => {
    const canvas = canvasRef.current;
    const video = videoRef?.current ?? null;
    if (canvas === null || video === null) return undefined;
    const context = canvas.getContext('2d');
    if (context === null) return undefined;

    let raf = 0;

    const draw = () => {
      const rect = cropRef.current;
      const box = canvas.getBoundingClientRect();
      if (rect === undefined || box.width === 0 || box.height === 0) return;
      if (video.videoWidth === 0 || video.videoHeight === 0) return;

      // 화면 배율만큼 실제 픽셀을 잡는다 — 안 그러면 결과가 흐리게 보여 크롭 판단을 그르친다
      const ratio = window.devicePixelRatio || 1;
      const width = Math.round(box.width * ratio);
      const height = Math.round(box.height * ratio);
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }

      context.drawImage(
        video,
        rect.x * video.videoWidth,
        rect.y * video.videoHeight,
        rect.w * video.videoWidth,
        rect.h * video.videoHeight,
        0,
        0,
        width,
        height,
      );
    };

    const loop = () => {
      raf = requestAnimationFrame(loop);
      draw();
    };

    const onPlay = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(loop);
    };
    const onPause = () => {
      cancelAnimationFrame(raf);
      raf = 0;
      draw();
    };

    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('seeked', draw);
    video.addEventListener('loadeddata', draw);
    // 정지 중에도 크롭을 끄는 동안 결과가 따라와야 한다. rAF 를 계속 돌리면 정지 상태에서
    // 배터리를 태우므로, 사람 눈에 충분한 간격으로만 다시 그린다.
    const idle = window.setInterval(() => {
      if (video.paused) draw();
    }, IDLE_REDRAW_MS);

    if (video.paused) draw();
    else onPlay();

    return () => {
      cancelAnimationFrame(raf);
      window.clearInterval(idle);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('seeked', draw);
      video.removeEventListener('loadeddata', draw);
    };
  }, [videoRef]);

  return (
    <div className={styles.resultPane} style={{ flex }}>
      {crop === undefined || videoRef === undefined ? (
        <span className={styles.sourcePlaceholder}>{label}</span>
      ) : (
        // aria-hidden: 그림이다. 값은 크롭 사각형 쪽이 읽어 준다.
        <canvas ref={canvasRef} className={styles.resultCanvas} aria-hidden />
      )}
    </div>
  );
}
