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

  useEffect(() => {
    if (expiresAt === null) return;
    setMs(remainingMs(expiresAt)); // 코드가 갈리면(재발급) 즉시 새 기준으로
    const id = window.setInterval(() => {
      const next = remainingMs(expiresAt);
      setMs(next);
      if (next <= 0) window.clearInterval(id); // 만료 후 빈 틱을 계속 돌 이유가 없다
    }, 1000);
    return () => window.clearInterval(id);
  }, [expiresAt]);

  return { label: formatRemaining(ms), expired: expiresAt !== null && ms <= 0 };
}
