'use client';

import { useEffect, useState } from 'react';

// 만료 카운트다운 (POK-103) — 페어링 코드의 10분 수명을 mm:ss로 보여준다.
// 매 틱 expiresAt - now를 다시 계산한다. 누적 감산(-1초씩)은 백그라운드 탭에서
// setInterval이 스로틀되면 실제 시각과 어긋나 "만료된 코드가 살아 보이는" 화면을 만든다.

export interface Countdown {
  /** mm:ss — 만료 후엔 00:00에 고정. */
  label: string;
  expired: boolean;
}

export function formatRemaining(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, '0');
  const seconds = String(totalSeconds % 60).padStart(2, '0');
  return `${minutes}:${seconds}`;
}

function remainingMs(expiresAt: string): number {
  return Math.max(0, new Date(expiresAt).getTime() - Date.now());
}

export function useCountdown(expiresAt: string | null): Countdown {
  const [ms, setMs] = useState(() => (expiresAt === null ? 0 : remainingMs(expiresAt)));
  // expiresAt이 바뀐 "그 렌더"에서 즉시 재계산한다(파생 상태 패턴). 이펙트는 페인트 뒤에
  // 돌기 때문에, 이것이 없으면 새 코드의 첫 프레임이 이전 상태(ms=0)로 그려져
  // "만료됐어요"가 잠깐 표시되고 role=status가 낭독까지 한다. (리뷰 #74)
  const [prevExpiresAt, setPrevExpiresAt] = useState(expiresAt);
  if (prevExpiresAt !== expiresAt) {
    setPrevExpiresAt(expiresAt);
    setMs(expiresAt === null ? 0 : remainingMs(expiresAt));
  }

  useEffect(() => {
    if (expiresAt === null) return;
    const id = window.setInterval(() => {
      const next = remainingMs(expiresAt);
      setMs(next);
      if (next <= 0) window.clearInterval(id); // 만료 후 빈 틱을 계속 돌 이유가 없다
    }, 1000);
    return () => window.clearInterval(id);
  }, [expiresAt]);

  return { label: formatRemaining(ms), expired: expiresAt !== null && ms <= 0 };
}
