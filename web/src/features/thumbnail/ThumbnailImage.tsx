'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { pickThumbnailUrl, STILL_REFRESH_MS } from './thumbnailUrl';
import styles from './ThumbnailImage.module.css';

/** placeholder를 사진 아래에 남겨 두는 시간. 사진이 나타나는 시간(ThumbnailImage.module.css의 0.2s)에 여유를 더했다 */
const FADE_MS = 250;

// 썸네일 칸 안의 사진(POK-277). 사진이 없거나 못 받으면(만료·삭제) 원래 자리표시(fallback)를 그대로 보인다.
// 주소가 미리서명이고 크기가 제각각이라 next/image가 아니라 img다(Avatar와 같은 판단).
export function ThumbnailImage({
  src,
  fallback,
  refreshMs = STILL_REFRESH_MS,
  placeholder,
  pending = false,
}: {
  src: string | null | undefined;
  fallback: ReactNode;
  refreshMs?: number;
  /**
   * 첫 사진을 내려받는 동안 보일 것(반짝이는 회색 칸 등). 주면 사진은 다 받은 뒤 서서히 나타난다. 이미 그린 사진이 새 주소로 바뀔
   * 때(라이브 1분 갱신)는 다시 보이지 않는다: 브라우저가 새 사진을 받는 동안 앞 사진을 그대로 둔다
   */
  placeholder?: ReactNode;
  /**
   * 사진이 아직 없지만 곧 온다(라이브 첫 사진). 사진이 없는 동안 fallback 대신 placeholder를 보인다(못 받았으면 fallback).
   * 판정을 여기서 하는 까닭: 부모의 src는 그리는 주소(shown)보다 한 그리기 먼저 바뀌어, 부모가 src로 가르면 첫 주소가 오는 순간
   * fallback이 한 번 끼고 placeholder가 새로 붙는다(반짝임이 처음부터 다시 돈다)
   */
  pending?: boolean;
}) {
  const [shown, setShown] = useState<string | null>(src ?? null);
  const [errored, setErrored] = useState(false);
  // 이 img의 첫 사진: 받는 중(wait, 사진은 숨는다) → 나타나는 중(fade, placeholder는 사진 아래에 남는다) → 다 나타남(done).
  // 자리표시로 내려갔다(사진 없음 · 못 받음) 다시 올라오면 새 img라 처음부터 기다린다
  const [phase, setPhase] = useState<'wait' | 'fade' | 'done'>('wait');
  // 못 받은 뒤에는 이어 쓰기를 하지 않고 들어온 주소로 바로 바꾼다. 같은 사진의 새 서명이 와도 보던(실패한) 주소를 붙잡으면
  // 일시 오류 하나로 갱신 시간(최대 50분)까지 자리표시에 머문다(로컬 리뷰 1라운드)
  useEffect(() => {
    setShown((prev) =>
      errored ? (src ?? null) : pickThumbnailUrl(prev, src ?? null, Date.now(), refreshMs),
    );
  }, [src, refreshMs, errored]);
  // 새 주소면 다시 시도한다. 한 번 실패한 주소 때문에 다음 사진까지 막히지 않게
  useEffect(() => {
    setErrored(false);
  }, [shown]);
  const hasImg = Boolean(shown) && !errored;
  useEffect(() => {
    if (!hasImg) setPhase('wait');
  }, [hasImg]);
  // 사진이 다 나타난 뒤에 placeholder를 걷는다. 받자마자 걷으면 사진이 투명한 동안 칸 바탕이 비쳐 한 번 꺼졌다 켜진다(PR #465 리뷰)
  useEffect(() => {
    if (phase !== 'fade') return;
    const timer = window.setTimeout(() => setPhase('done'), FADE_MS);
    return () => window.clearTimeout(timer);
  }, [phase]);
  // 이미 받은 사진(다른 화면에 갔다 돌아옴)이면 붙는 순간 끝낸다. load는 다음 태스크에 와서, 기다리면 로딩 칸이 한 프레임 비치고
  // 페이드가 다시 돈다(PR #465 리뷰). ref는 커밋 중에 불려 그리기 전에 맞춰진다
  const settleIfReady = useCallback((img: HTMLImageElement | null) => {
    if (img?.complete && img.naturalWidth > 0) setPhase('done');
  }, []);
  if (!shown || errored) {
    // 첫 칸은 placeholder 자리다. 사진이 와도 같은 칸에 남아 같은 노드로 이어진다
    const awaiting = pending && !errored && placeholder !== undefined;
    return (
      <>
        {awaiting ? placeholder : null}
        {awaiting ? null : fallback}
      </>
    );
  }
  const layered = placeholder !== undefined;
  const waiting = layered && phase === 'wait';
  return (
    <>
      {layered && phase !== 'done' ? placeholder : null}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        ref={layered ? settleIfReady : undefined}
        className={styles.img}
        data-pending={waiting || undefined}
        src={shown}
        alt=""
        loading="lazy"
        // 나타날 placeholder가 없으면 바로 끝이다(타이머를 걸지 않는다)
        onLoad={() => setPhase((p) => (p !== 'wait' ? p : layered ? 'fade' : 'done'))}
        onError={() => setErrored(true)}
      />
    </>
  );
}
