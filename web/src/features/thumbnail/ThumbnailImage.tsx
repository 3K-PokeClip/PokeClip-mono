'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { pickThumbnailUrl, STILL_REFRESH_MS } from './thumbnailUrl';
import styles from './ThumbnailImage.module.css';

// 썸네일 칸 안의 사진(POK-277). 사진이 없거나 못 받으면(만료·삭제) 원래 자리표시(fallback)를 그대로 보인다.
// 주소가 미리서명이고 크기가 제각각이라 next/image가 아니라 img다(Avatar와 같은 판단).
export function ThumbnailImage({
  src,
  fallback,
  refreshMs = STILL_REFRESH_MS,
}: {
  src: string | null | undefined;
  fallback: ReactNode;
  refreshMs?: number;
}) {
  const [shown, setShown] = useState<string | null>(src ?? null);
  const [errored, setErrored] = useState(false);
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
  if (!shown || errored) return <>{fallback}</>;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      className={styles.img}
      src={shown}
      alt=""
      loading="lazy"
      onError={() => setErrored(true)}
    />
  );
}
