'use client';

import { useEffect, useState } from 'react';

// 만료 카운트다운 (POK-103) — 페어링 코드의 10분 수명을 mm:ss로 보여준다.
// 마감(deadline)은 클라 시계 기준 epoch ms다 — 서버 expiresAt을 그대로 받아 Date.now()와
// 비교하면 시계가 어긋난 기기에서 정상 코드가 발급 즉시 만료로 보인다. 발급 훅이
// 응답 수신 순간 + TTL로 앵커해 넘기므로 여기는 같은 시계끼리만 비교한다. (리뷰 #74)
// 매 틱 deadline - now를 다시 계산한다. 누적 감산(-1초씩)은 백그라운드 탭에서
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

function remainingMs(deadline: number): number {
  return Math.max(0, deadline - Date.now());
}

export function useCountdown(deadline: number | null): Countdown {
  const [ms, setMs] = useState(() => (deadline === null ? 0 : remainingMs(deadline)));
  // deadline이 바뀐 "그 렌더"에서 즉시 재계산한다(파생 상태 패턴). 이펙트는 페인트 뒤에
  // 돌기 때문에, 이것이 없으면 새 코드의 첫 프레임이 이전 상태(ms=0)로 그려져
  // "만료됐어요"가 잠깐 표시되고 role=status가 낭독까지 한다. (리뷰 #74)
  const [prevDeadline, setPrevDeadline] = useState(deadline);
  if (prevDeadline !== deadline) {
    setPrevDeadline(deadline);
    setMs(deadline === null ? 0 : remainingMs(deadline));
  }

  useEffect(() => {
    if (deadline === null) return;
    const id = window.setInterval(() => {
      const next = remainingMs(deadline);
      setMs(next);
      if (next <= 0) window.clearInterval(id); // 만료 후 빈 틱을 계속 돌 이유가 없다
    }, 1000);
    return () => window.clearInterval(id);
  }, [deadline]);

  return { label: formatRemaining(ms), expired: deadline !== null && ms <= 0 };
}
