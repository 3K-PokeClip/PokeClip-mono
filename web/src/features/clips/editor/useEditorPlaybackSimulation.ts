'use client';

// 미디어가 없을 때의 편집기 재생 — 플레이헤드만 흐르는 목업 (POK-238에서 상태 허브에서 떼어냄).
//
// 실재생(useEditorHlsPlayback)과 같은 EditorPlayback 계약을 돌려주므로 상태 허브는 둘을 구분하지
// 않는다. 라이브 화면의 usePlayerSimulation ↔ useHlsPlayback 짝과 같은 구조다.
//
// 틱을 rAF 가 아니라 setInterval 로 둔 것은 의도다 — 가짜 타이머로 재생 동작을 검증할 수 있어야
// 하는데 vitest 는 기본으로 rAF 를 가짜로 만들지 않는다.

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  boundaryAction,
  type EditorPlayback,
  type EditorPlaybackOptions,
  type PlaybackBounds,
} from './editorPlayback';

const TICK_MS = 100;
const TICK_SECONDS = TICK_MS / 1000;

function clamp(seconds: number, durationSeconds: number): number {
  return Math.min(durationSeconds, Math.max(0, seconds));
}

export function useEditorPlaybackSimulation(options: EditorPlaybackOptions): EditorPlayback {
  const { durationSeconds, initialSeconds } = options;

  const [playing, setPlaying] = useState(false);
  const [currentSeconds, setCurrentSeconds] = useState(initialSeconds);

  // 위치의 정본은 ref 다. 상태는 그리기 위한 사본이다 —
  // 틱 안에서 setState 업데이터에 부수효과(정지)를 섞으면 StrictMode 의 이중 호출에서 갈라진다.
  const positionRef = useRef(initialSeconds);
  const durationRef = useRef(durationSeconds);
  durationRef.current = durationSeconds;
  const rateRef = useRef(1);
  // 구간은 상태 허브가 effect 로 밀어 넣는다. ref 라서 값이 바뀌어도 틱이 재시작하지 않는다 —
  // 배속·구간을 바꿀 때마다 인터벌을 다시 걸면 그 순간의 100ms 가 통째로 밀린다.
  const boundsRef = useRef<PlaybackBounds | null>(null);

  const commit = useCallback((seconds: number) => {
    positionRef.current = seconds;
    setCurrentSeconds(seconds);
  }, []);

  useEffect(() => {
    if (!playing) return undefined;
    const timer = setInterval(() => {
      const bounds = boundsRef.current;
      const step = TICK_SECONDS * rateRef.current;
      if (bounds === null) {
        commit(clamp(positionRef.current + step, durationRef.current));
        return;
      }
      // 지금 위치가 이미 구간 밖인지 먼저 본다 (구간 앞에서 재생을 시작한 경우)
      const now = boundaryAction(positionRef.current, bounds);
      if (now.kind === 'seek') {
        commit(now.toSeconds);
        return;
      }
      if (now.kind === 'stop') {
        setPlaying(false);
        commit(now.atSeconds);
        return;
      }
      // 한 걸음 간 뒤 끝을 넘는지 본다 — 넘어간 뒤에 되돌리면 한 틱만큼 구간 밖이 재생된다
      const next = positionRef.current + step;
      const after = boundaryAction(next, bounds);
      if (after.kind === 'seek') {
        commit(after.toSeconds);
        return;
      }
      if (after.kind === 'stop') {
        setPlaying(false);
        commit(after.atSeconds);
        return;
      }
      commit(clamp(next, durationRef.current));
    }, TICK_MS);
    return () => clearInterval(timer);
  }, [playing, commit]);

  return {
    playing,
    currentSeconds,
    durationSeconds,
    // 목업에는 실패할 미디어가 없다
    error: null,
    togglePlay: useCallback(() => setPlaying((value) => !value), []),
    seekTo: useCallback((seconds: number) => commit(clamp(seconds, durationRef.current)), [commit]),
    seekBy: useCallback(
      (delta: number) => commit(clamp(positionRef.current + delta, durationRef.current)),
      [commit],
    ),
    setRate: useCallback((rate: number) => {
      rateRef.current = rate;
    }, []),
    setBounds: useCallback((bounds: PlaybackBounds) => {
      boundsRef.current = bounds;
    }, []),
  };
}
