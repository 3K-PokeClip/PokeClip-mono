'use client';

import { useEffect, useState } from 'react';
import { detectOs, type DetectedOs } from '@/lib/detectOs';

/**
 * 이 브라우저의 OS (POK-379). 첫 렌더는 항상 unknown이고 마운트 뒤에만 읽는다 —
 * 렌더 중에 navigator를 읽으면 서버 HTML과 달라져 hydration 경고가 난다.
 */
export function useDetectedOs(): DetectedOs {
  const [os, setOs] = useState<DetectedOs>('unknown');
  useEffect(() => {
    setOs(detectOs(navigator as Navigator & { userAgentData?: { platform?: string } }));
  }, []);
  return os;
}
