'use client';

import { useEffect, useRef } from 'react';
import type { CropRect } from './cropMath';

// 미리보기 칸에 실제 영상을 그린다. 영상 노드는 편집기에 하나뿐이고(재생 어댑터가 쥔다) 원본 판·결과 칸들은
// 그 프레임을 각자 캔버스에 옮겨 그린다 — 결과 칸은 잡은 영역(crop, 0..1 정규화)만 잘라 그린다.
// 칸의 모양은 레이아웃이 crop 의 비율에 맞춰 두었으므로 그대로 늘려 그려도 왜곡이 없다.
//
// 재생 중에만 영상 프레임마다 그린다(requestVideoFrameCallback, 없으면 rAF). 멈춘 영상은 그림이 안 바뀌므로 장면이 바뀌는 일이
// 있을 때 한 번만 그린다: 옮기기 끝(seeked) · 첫 장면(loadeddata) · 크롭 변경 · 칸 크기 변경. 칸마다 프레임을 돌리면 멈춘 편집기가
// 쉬지 못한다. 크기는 ResizeObserver로 잡아 둔다: 그릴 때마다 clientWidth를 읽으면 프레임마다 레이아웃을 강제한다.

const FULL_FRAME: CropRect = { x: 0, y: 0, w: 1, h: 1 };

function noop() {}

export function VideoSurface({ video, crop }: { video: HTMLVideoElement; crop?: CropRect | null }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const cropRef = useRef(crop ?? null);
  cropRef.current = crop ?? null;
  const paintRef = useRef<() => void>(noop);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null) return undefined;
    const ctx = canvas.getContext('2d');
    if (ctx === null) return undefined;

    let cssWidth = 0;
    let cssHeight = 0;
    const observer =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver((entries) => {
            const box = entries[entries.length - 1]?.contentRect;
            if (box === undefined) return;
            cssWidth = box.width;
            cssHeight = box.height;
            // 캔버스 크기를 바꾸면 그림이 지워지므로 멈춰 있어도 다시 그린다. 첫 알림이 첫 그림이다
            paint();
          });

    const paint = () => {
      // 관찰자가 없는 환경에서만 그때그때 잰다
      if (observer === null) {
        cssWidth = canvas.clientWidth;
        cssHeight = canvas.clientHeight;
      }
      const dpr = window.devicePixelRatio || 1;
      const width = Math.round(cssWidth * dpr);
      const height = Math.round(cssHeight * dpr);
      if (width === 0 || height === 0) return;
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      if (video.readyState < 2 || video.videoWidth === 0) return;
      const c = cropRef.current ?? FULL_FRAME;
      ctx.drawImage(
        video,
        c.x * video.videoWidth,
        c.y * video.videoHeight,
        c.w * video.videoWidth,
        c.h * video.videoHeight,
        0,
        0,
        width,
        height,
      );
    };
    paintRef.current = paint;

    let stopFrames: (() => void) | null = null;
    const startFrames = () => {
      if (stopFrames !== null) return;
      if (typeof video.requestVideoFrameCallback === 'function') {
        let handle = 0;
        const onFrame = () => {
          handle = video.requestVideoFrameCallback(onFrame);
          paint();
        };
        handle = video.requestVideoFrameCallback(onFrame);
        stopFrames = () => video.cancelVideoFrameCallback(handle);
      } else {
        let raf = 0;
        const onFrame = () => {
          raf = requestAnimationFrame(onFrame);
          paint();
        };
        raf = requestAnimationFrame(onFrame);
        stopFrames = () => cancelAnimationFrame(raf);
      }
    };
    const pauseFrames = () => {
      stopFrames?.();
      stopFrames = null;
    };
    const onStopped = () => {
      pauseFrames();
      paint(); // 마지막 프레임이 콜백보다 늦게 왔을 수 있다
    };

    observer?.observe(canvas);
    video.addEventListener('play', startFrames);
    video.addEventListener('pause', onStopped);
    video.addEventListener('ended', onStopped);
    video.addEventListener('seeked', paint);
    video.addEventListener('loadeddata', paint);
    // 재생 중에 도구를 바꿔 새로 붙은 칸은 play 이벤트를 못 듣는다
    if (!video.paused && !video.ended) startFrames();

    return () => {
      pauseFrames();
      observer?.disconnect();
      video.removeEventListener('play', startFrames);
      video.removeEventListener('pause', onStopped);
      video.removeEventListener('ended', onStopped);
      video.removeEventListener('seeked', paint);
      video.removeEventListener('loadeddata', paint);
      paintRef.current = noop;
    };
  }, [video]);

  // 크롭이 바뀌면 멈춰 있어도 다시 그린다. 값으로 본다: 허브가 같은 자리를 새 객체로 줘도 그림은 같다.
  // 관찰자가 없는 환경의 첫 그림도 여기다(알림이 없다). 관찰자가 있으면 아직 크기를 몰라 그리지 않고 첫 알림이 그린다
  const { x, y, w, h } = crop ?? FULL_FRAME;
  useEffect(() => {
    paintRef.current();
  }, [video, x, y, w, h]);

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
    />
  );
}
