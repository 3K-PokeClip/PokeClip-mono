'use client';

import { useRef } from 'react';
import { DEFAULT_VOLUME, restoredVolume } from './playerMath';
import type { PlayerSimulation } from './usePlayerSimulation';

/** 소리 켜고 끄기(POK-517). 음소거 단추와 m 단축키가 같은 동작이다 */
export function useSoundToggle(sim: PlayerSimulation) {
  // 슬라이더를 만지기 직전 음량. 끄는 값을 따라가며 기억하면 0 바로 앞 값(1)이 남아 되살려도 안 들린다
  const lastVolumeRef = useRef(DEFAULT_VOLUME);
  return {
    rememberVolume: () => {
      if (sim.volume > 0) lastVolumeRef.current = sim.volume;
    },
    toggleSound: () => {
      // 음량 0에서 음소거만 뒤집으면 아이콘도 소리도 그대로라 죽은 단추다
      if (sim.volume === 0) sim.setVolume(restoredVolume(lastVolumeRef.current));
      else sim.toggleMute();
    },
  };
}
