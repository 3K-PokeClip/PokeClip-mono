'use client';

import { useEffect, useState } from 'react';

/**
 * 0..n-1 중 하나를 마운트 뒤에 고른다 — 렌더 중 Math.random은 서버 HTML과 어긋난다.
 * 홈 큰 칸의 포키 포즈·포키의 팁이 함께 쓴다(POK-378).
 */
export function useRandomIndex(n: number): number | null {
  const [picked, setPicked] = useState<number | null>(null);
  useEffect(() => {
    setPicked(Math.floor(Math.random() * n));
  }, [n]);
  return picked;
}
