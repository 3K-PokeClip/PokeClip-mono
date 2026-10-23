'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { RecipeOutputV2 } from '@/api/clipEditor';
import { BLUR_SIGMA_AT_FULL } from './recipeLook';

// 업로드 창의 장면 그림(POK-381). 창을 여는 때는 아직 렌더 전이라 완성 영상이 없다: 편집기가 받아 둔 녹화 조각(브라우저 안
// 파일)에서 뽑는다. 편집기의 보이지 않는 영상 노드는 건드리지 않는다(옮기면 편집기 재생 위치가 움직인다) — 같은 파일로 영상
// 노드를 하나 더 만들어 차례로 옮겨 가며 그린다.
//
// 그림은 결과 모양(세로 9:16의 바탕 · 층 · 구분선)대로 그린다. 업로드 일꾼은 완성 영상에서 같은 시각을 뽑으므로 거의 같은
// 그림이 붙는다. 자막·작은 화면의 둥근 모서리·그림자는 그리지 않는다: 고르는 데는 충분하고, 자막은 편집기도 따로 얹는다.

/** 장면 그림을 뽑을 재료. 녹화가 없거나 아직 못 받았으면 창에 null을 준다(그림 없이 시각만 보인다) */
export interface SceneFrameSource {
  /** 편집기가 받아 둔 녹화 조각(blob: 주소) */
  src: string;
  /** 완성 영상 ms → 그 조각 안의 초 */
  videoSecondsAt: (offsetMs: number) => number;
  /** 결과 모양(편집기 화면 그대로) */
  output: RecipeOutputV2;
}

/** 뽑는 그림 크기. 미리보기 칸(264px)을 고밀도 화면에서도 덜 흐리게 */
const FRAME_WIDTH = 360;
const FRAME_HEIGHT = 640;
/** 옮기기가 이만큼 안 끝나면 그 시각은 건너뛴다(손상된 조각에서 줄이 멈추지 않게) */
const SEEK_TIMEOUT_MS = 4_000;
/** 조각을 이만큼 안에 못 읽으면(첫 장면이 안 옴) 재료를 못 쓰는 것으로 본다. 브라우저 안 파일이라 보통 1초 안이다 */
const LOAD_TIMEOUT_MS = 10_000;
/**
 * 띠에 올리거나 끌며 뽑은 그림(추천 · 띠 칸 · 고른 장면 밖)은 최근 이만큼만 남긴다. 장당 수십 KB라 3분 구간(720칸)을 다
 * 훑으면 수십 MB가 창을 닫을 때까지 남았다(PR #301 리뷰). 지운 자리에 다시 올리면 다시 뽑는다
 */
const EXTRA_FRAMES_KEPT = 24;
/** 못 뽑은 시각의 표시(옮기기 실패 · 시간 초과 · 그릴 수 없음). 같은 조각이면 또 실패하므로 다시 뽑지 않는다 */
const FAILED = '';

export interface SceneFrames {
  /** 뽑아 둔 그림 주소. 아직이거나 못 뽑았으면 null */
  frameOf: (offsetMs: number) => string | null;
  /** 그 시각의 그림을 못 얻는다(재료가 없거나 뽑기가 실패). 아직 뽑는 중이면 false */
  failed: (offsetMs: number) => boolean;
}

/**
 * `wanted`의 시각들을 차례로 뽑는다. `urgent`(띠에 올린 자리)는 줄 맨 앞, `keep`(고른 장면)은 그다음에 끼운다 — 둘 다 한
 * 칸이라 새 것이 오면 옛 것을 버린다(끌기 중 쌓이지 않는다). `keep`을 따로 받는 까닭: 띠에 포인터를 둔 채 키로 고르면
 * `urgent`가 계속 올린 자리라 고른 장면을 안 뽑았다(PR #301 2차 리뷰).
 * `wanted`와 `keep`의 그림은 계속 두고, 나머지는 최근 {@link EXTRA_FRAMES_KEPT}장만 둔다.
 */
export function useSceneFrames(
  source: SceneFrameSource | null,
  wanted: readonly number[],
  urgent: number | null,
  keep: number | null = null,
): SceneFrames {
  const [frames, setFrames] = useState<ReadonlyMap<number, string>>(() => new Map());
  /** 못 읽은 조각(blob 주소). 재료가 바뀌면 저절로 풀린다 */
  const [broken, setBroken] = useState<string | null>(null);
  const done = useRef(new Map<number, string>());
  const queue = useRef<number[]>([]);
  const urgentRef = useRef<number | null>(null);
  const keepRef = useRef<number | null>(null);
  const video = useRef<HTMLVideoElement | null>(null);
  const ready = useRef(false);
  const busy = useRef(false);
  const sourceRef = useRef(source);
  sourceRef.current = source;
  const pinned = useRef<ReadonlySet<number>>(new Set());
  pinned.current = new Set(keep === null ? wanted : [...wanted, keep]);

  const remember = useCallback((offsetMs: number, url: string) => {
    const map = done.current;
    map.set(offsetMs, url);
    // Map은 넣은 순서라 앞이 오래된 것이다
    const extra = [...map.keys()].filter((ms) => !pinned.current.has(ms));
    for (const ms of extra.slice(0, Math.max(0, extra.length - EXTRA_FRAMES_KEPT))) map.delete(ms);
    setFrames(new Map(map));
  }, []);

  const pump = useCallback(() => {
    const el = video.current;
    const src = sourceRef.current;
    if (busy.current || !ready.current || el === null || src === null) return;
    const next = urgentRef.current ?? keepRef.current ?? queue.current.shift();
    if (next === undefined) return;
    if (urgentRef.current === next) urgentRef.current = null;
    else if (keepRef.current === next) keepRef.current = null;
    if (done.current.has(next)) {
      pump();
      return;
    }
    busy.current = true;
    seekTo(el, src.videoSecondsAt(next))
      .then((ok) => {
        // 옮기는 사이 재료가 바뀌었으면 옛 노드의 그림이다: 버린다
        if (video.current !== el) return;
        // 못 뽑은 것도 적는다: 안 적으면 창이 「불러오는 중」에서 멈춘다(PR #301 리뷰)
        remember(next, (ok ? drawFrame(el, src.output) : null) ?? FAILED);
      })
      .finally(() => {
        busy.current = false;
        pump();
      });
  }, [remember]);

  // 재료마다 영상 노드 하나. 재료가 바뀌면(편집기가 조각을 갈아 끼우면) 뽑아 둔 그림도 버린다
  const src = source?.src ?? null;
  useEffect(() => {
    if (src === null || typeof document === 'undefined') return undefined;
    const el = document.createElement('video');
    el.muted = true;
    el.playsInline = true;
    el.preload = 'auto';
    // 첫 장면 전에 오류가 나거나 안 오면 이 조각은 못 쓴다: 창이 「불러오는 중」 대신 「못 불러와요」를 보이게 적는다
    // (옮기기 실패는 장면마다 적지만, 읽기 실패는 옮기기까지 가지 못한다 — PR #301 2차 리뷰)
    const giveUp = () => {
      if (!ready.current) setBroken(src);
    };
    const timer = globalThis.setTimeout(giveUp, LOAD_TIMEOUT_MS);
    const onReady = () => {
      globalThis.clearTimeout(timer);
      el.removeEventListener('error', giveUp);
      ready.current = true;
      pump();
    };
    el.addEventListener('loadeddata', onReady, { once: true });
    el.addEventListener('error', giveUp, { once: true });
    el.src = src;
    video.current = el;
    return () => {
      globalThis.clearTimeout(timer);
      el.removeEventListener('loadeddata', onReady);
      el.removeEventListener('error', giveUp);
      video.current = null;
      ready.current = false;
      done.current = new Map();
      queue.current = [];
      urgentRef.current = null;
      keepRef.current = null;
      setFrames(new Map());
      el.removeAttribute('src');
      el.load();
    };
  }, [src, pump]);

  // 뽑을 것을 줄에 둔다. 이미 뽑았거나 줄에 있으면 다시 넣지 않는다
  const wantedKey = wanted.join(',');
  useEffect(() => {
    if (src === null || wantedKey === '') return;
    for (const ms of wantedKey.split(',').map(Number)) {
      if (!done.current.has(ms) && !queue.current.includes(ms)) queue.current.push(ms);
    }
    pump();
  }, [src, wantedKey, pump]);

  useEffect(() => {
    if (src === null || urgent === null || done.current.has(urgent)) return;
    urgentRef.current = urgent;
    pump();
  }, [src, urgent, pump]);

  useEffect(() => {
    if (src === null || keep === null || done.current.has(keep)) return;
    keepRef.current = keep;
    pump();
  }, [src, keep, pump]);

  const frameOf = useCallback(
    (offsetMs: number) => {
      const url = frames.get(offsetMs);
      return url === undefined || url === FAILED ? null : url;
    },
    [frames],
  );
  const failed = useCallback(
    (offsetMs: number) => src === null || broken === src || frames.get(offsetMs) === FAILED,
    [frames, src, broken],
  );
  return useMemo(() => ({ frameOf, failed }), [frameOf, failed]);
}

/** 영상을 그 초로 옮기고 그 장면을 그릴 수 있게 되면 true. 시간 안에 안 되면 false */
function seekTo(el: HTMLVideoElement, seconds: number): Promise<boolean> {
  return new Promise((resolve) => {
    const end = Number.isFinite(el.duration) ? el.duration - 0.01 : seconds;
    const target = Math.max(0, Math.min(seconds, end));
    let settled = false;
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      el.removeEventListener('seeked', onSeeked);
      el.removeEventListener('error', onError);
      globalThis.clearTimeout(timer);
      resolve(ok);
    };
    const onSeeked = () => finish(true);
    const onError = () => finish(false);
    const timer = globalThis.setTimeout(() => finish(false), SEEK_TIMEOUT_MS);
    el.addEventListener('seeked', onSeeked);
    el.addEventListener('error', onError);
    el.currentTime = target;
  });
}

/** 지금 장면을 결과 모양대로 그려 JPEG 주소로. 그릴 수 없으면(캔버스 없음 · 영상 크기 0) null */
function drawFrame(el: HTMLVideoElement, output: RecipeOutputV2): string | null {
  const vw = el.videoWidth;
  const vh = el.videoHeight;
  if (vw === 0 || vh === 0) return null;
  const canvas = document.createElement('canvas');
  canvas.width = FRAME_WIDTH;
  canvas.height = FRAME_HEIGHT;
  const ctx = canvas.getContext('2d');
  if (ctx === null) return null;
  const w = FRAME_WIDTH;
  const h = FRAME_HEIGHT;
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, w, h);
  const background = output.background;
  if (background?.kind === 'COLOR') {
    ctx.fillStyle = background.color;
    ctx.fillRect(0, 0, w, h);
  } else if (background?.kind === 'BLUR') {
    // 원본을 결과에 꽉 차게 키워 흐린다 — 렌더와 같은 식(세기 100 = 표준편차가 결과 폭의 4%)
    const scale = Math.max(w / vw, h / vh);
    ctx.save();
    ctx.filter = `blur(${(background.strength / 100) * BLUR_SIGMA_AT_FULL * w}px)`;
    ctx.drawImage(el, (w - vw * scale) / 2, (h - vh * scale) / 2, vw * scale, vh * scale);
    ctx.restore();
  }
  for (const layer of output.layers) {
    const { crop: c, box: b } = layer;
    ctx.drawImage(el, c.x * vw, c.y * vh, c.w * vw, c.h * vh, b.x * w, b.y * h, b.w * w, b.h * h);
    if (layer.frame) {
      ctx.strokeStyle = layer.frame.color;
      ctx.lineWidth = layer.frame.width * w;
      ctx.strokeRect(b.x * w, b.y * h, b.w * w, b.h * h);
    }
  }
  for (const divider of output.dividers ?? []) {
    const thickness = divider.thickness * w;
    ctx.fillStyle = divider.color;
    ctx.fillRect(0, divider.y * h - thickness / 2, w, thickness);
  }
  return canvas.toDataURL('image/jpeg', 0.85);
}
