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

  // 마우스를 움직이지 않아도 재생이 시작되면 자동 숨김이 걸려야 한다 (마운트 직후 포함).
  // 일시정지 중엔 어차피 컨트롤을 강제 표시하므로 타이머만 풀어 둔다.
  useEffect(() => {
    if (playing) armHide();
    else window.clearTimeout(hideTimer.current);
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
