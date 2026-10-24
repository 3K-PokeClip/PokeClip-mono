'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  readAnalyticsChoice,
  setAnalyticsOptOut,
  subscribeAnalyticsPreference,
  type AnalyticsChoice,
} from '@/lib/analytics';
import { useToast } from '@/ui';

// 설정 › 계정의 「이용 분석 참여」 스위치(처리방침 7·9절). 선택은 이 브라우저에만 둔다 — 서버에 저장하지 않아 기기·브라우저마다
// 따로 끈다(2026-10-24 결정, 백엔드를 건드리지 않는다). 끄고 켜는 일은 lib/analytics의 setAnalyticsOptOut이 한다.

export interface AnalyticsOptOutState {
  /** 이 브라우저의 상태. 아직 모르면 undefined — 스위치를 잠근다 */
  choice: AnalyticsChoice | undefined;
  setParticipating: (next: boolean) => void;
}

/** 스위치로 바꿀 수 있는 상태인가 — 추적 금지·SDK 없음은 스위치와 상관없이 보내지 않는다 */
function switchable(choice: AnalyticsChoice | undefined): boolean {
  return choice === 'participating' || choice === 'opted_out';
}

export function useAnalyticsOptOutState(): AnalyticsOptOutState {
  const [choice, setChoice] = useState<AnalyticsChoice | undefined>(undefined);
  const { toast } = useToast();

  useEffect(() => {
    let alive = true;
    void readAnalyticsChoice().then((read) => {
      if (alive) setChoice(read);
    });
    // 같은 브라우저의 다른 탭에서 바꾸면 이 탭의 스위치도 따라간다
    const stop = subscribeAnalyticsPreference(({ optOut }) =>
      setChoice((prev) => (switchable(prev) ? (optOut ? 'opted_out' : 'participating') : prev)),
    );
    return () => {
      alive = false;
      stop();
    };
  }, []);

  const setParticipating = useCallback(
    (next: boolean) => {
      if (!switchable(choice)) return;
      setAnalyticsOptOut(!next);
      setChoice(next ? 'participating' : 'opted_out');
      toast({
        tone: 'success',
        title: next
          ? '이 브라우저에서 이용 분석을 다시 켰어요'
          : '이 브라우저에서 이용 분석을 껐어요',
      });
    },
    [choice, toast],
  );

  return { choice, setParticipating };
}
