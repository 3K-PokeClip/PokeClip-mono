'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { AUTO_HIDE_MS } from './playerMath';

// 컨트롤 자동 숨김 — usePlayerSimulation(목업)과 useHlsPlayback(실재생)이
// 같은 동작을 공유하도록 분리했다. 동작은 기존 시뮬레이션과 동일하다.

export interface ControlsAutoHide {
  controlsVisible: boolean;
  wake: () => void;
  sleep: () => void;
}

export function useControlsAutoHide(playing: boolean): ControlsAutoHide {
  const [controlsVisible, setControlsVisible] = useState(true);
  const hideTimer = useRef<number>(undefined);

  const armHide = useCallback(() => {
    window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => setControlsVisible(false), AUTO_HIDE_MS);
  }, []);

  // 마우스를 움직이지 않아도 자동 숨김이 걸려야 한다 (마운트 직후 포함). 재생을 시작하거나 멈출 때마다 다시 잰다.
  // 멈춰 있어도 건다: 호버 없이 깨운 컨트롤(키 · 터치)이 영상을 가린 채 남지 않게 한다. 호버 중 유지는 GlassPlayer가 맡는다(POK-518)
  useEffect(() => {
    armHide();
  }, [playing, armHide]);

  useEffect(() => () => window.clearTimeout(hideTimer.current), []);

  const wake = useCallback(() => {
    setControlsVisible(true);
    armHide();
  }, [armHide]);

  const sleep = useCallback(() => {
    window.clearTimeout(hideTimer.current);
    setControlsVisible(false);
  }, []);

  return { controlsVisible, wake, sleep };
}
