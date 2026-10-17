'use client';

import { useEffect, useRef } from 'react';
import type { CropRect } from './cropMath';

// 미리보기 칸에 실제 영상을 그린다. 영상 노드는 편집기에 하나뿐이고(재생 어댑터가 쥔다) 원본 판·결과 칸들은
// 그 프레임을 각자 캔버스에 옮겨 그린다 — 결과 칸은 잡은 영역(crop, 0..1 정규화)만 잘라 그린다.
// 칸의 모양은 레이아웃이 crop 의 비율에 맞춰 두었으므로 그대로 늘려 그려도 왜곡이 없다.

export function VideoSurface({ video, crop }: { video: HTMLVideoElement; crop?: CropRect | null }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const cropRef = useRef(crop ?? null);
  cropRef.current = crop ?? null;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null) return undefined;
    const ctx = canvas.getContext('2d');
    if (ctx === null) return undefined;
    let raf = 0;
    const draw = () => {
      raf = requestAnimationFrame(draw);
      const width = Math.round(canvas.clientWidth * (window.devicePixelRatio || 1));
      const height = Math.round(canvas.clientHeight * (window.devicePixelRatio || 1));
      if (width === 0 || height === 0) return;
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      if (video.readyState < 2 || video.videoWidth === 0) return;
      const c = cropRef.current ?? { x: 0, y: 0, w: 1, h: 1 };
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
    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [video]);

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
    />
  );
}
