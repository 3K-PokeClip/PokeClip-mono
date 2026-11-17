'use client';

import { useEffect, useState } from 'react';
import { msToClock } from './useHomeMockState';

/** 초가 바뀐 바로 뒤에 깨도록 더하는 여유. 타이머가 경계보다 살짝 일찍 깨면 같은 초를 한 번 더 그린다 */
const TICK_SLACK_MS = 15;

/**
 * 라이브 띠 LIVE 배지의 방송 경과(`1:24:03`). 홈은 방송 목록을 10초마다 읽는데, 읽은 시각으로 경과를 적으면 숫자가 10초씩
 * 건너뛰었다. 그래서 시작 시각만 받아 화면에서 매초 센다. 띠 전체와 사진은 다시 그리지 않도록 배지 글자만 따로 뗐다
 */
export function LiveUptime({ startedAtMs }: { startedAtMs: number | null }) {
  const now = useSecondTick(startedAtMs);
  return <>{startedAtMs === null ? '--:--:--' : msToClock(now - startedAtMs)}</>;
}

/**
 * 경과 초가 바뀔 때마다 지금 시각을 다시 준다. 1초 간격(setInterval)이 아니라 다음 초 경계에 맞춰 깬다: 시작 위상이 경계에 붙으면
 * 타이머 오차로 같은 초를 두 번 그리고 다음 초를 건너뛴다(2초씩 뛰는 것처럼 보인다). 탭이 숨으면 멈추고, 돌아오면 바로 맞춘다
 */
function useSecondTick(originMs: number | null): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (originMs === null) return;
    let timer: number | undefined;
    const stop = () => {
      if (timer !== undefined) window.clearTimeout(timer);
      timer = undefined;
    };
    const arm = () => {
      stop();
      const into = (((Date.now() - originMs) % 1000) + 1000) % 1000;
      timer = window.setTimeout(tick, 1000 - into + TICK_SLACK_MS);
    };
    const tick = () => {
      setNow(Date.now());
      arm();
    };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') stop();
      else tick();
    };
    // 지금 시각부터 맞추고 다음 초 경계를 건다. 시작 시각이 나중에 오면(모름 → 앎) 마운트 때 잡은 시각으로 세어 그만큼 틀린다.
    // 탭이 숨은 채로 열렸으면 돌아올 때(visibilitychange) 시작한다
    if (document.visibilityState !== 'hidden') tick();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [originMs]);
  return now;
}
