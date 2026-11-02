'use client';

import { useEffect, useState, type RefObject } from 'react';
import { watchBuffering } from './bufferingWatch';

/** 그 영상 노드가 끊겨 기다리는 중인가(POK-439) */
export function useMediaBuffering(videoRef: RefObject<HTMLVideoElement | null>): boolean {
  const [buffering, setBuffering] = useState(false);
  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return undefined;
    const stop = watchBuffering(video, setBuffering);
    return () => {
      stop();
      setBuffering(false);
    };
  }, [videoRef]);
  return buffering;
}
