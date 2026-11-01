'use client';

// 미디어가 없을 때의 편집기 재생 — 플레이헤드만 흐르는 목업 (상태 허브에서 떼어냄).
//
// 실재생 어댑터가 오면 같은 EditorPlayback 계약을 구현해 허브에 주입한다 — 허브는 둘을 구분하지
// 않는다. 라이브 화면의 usePlayerSimulation ↔ useHlsPlayback 짝과 같은 구조다.
//
// 틱을 rAF 가 아니라 setInterval 로 둔 것은 의도다 — 가짜 타이머로 재생 동작을 검증할 수 있어야
// 하는데 vitest 는 기본으로 rAF 를 가짜로 만들지 않는다.

import { useCallback, useEffect, useRef, useState } from 'react';
import { boundaryAction, type EditorPlaybackOptions, type PlaybackBounds } from './editorPlayback';
import { createPlaybackClock, type ClockedPlayback } from './playbackClock';

const TICK_MS = 100;
const TICK_SECONDS = TICK_MS / 1000;

function clamp(seconds: number, durationSeconds: number): number {
  return Math.min(durationSeconds, Math.max(0, seconds));
}

export function useEditorPlaybackSimulation(options: EditorPlaybackOptions): ClockedPlayback {
  const { durationSeconds, initialSeconds } = options;

  const [playing, setPlaying] = useState(false);
  const [currentSeconds, setCurrentSeconds] = useState(initialSeconds);

  // 위치의 정본은 저장소다. 상태는 멈춤 · 시크 · 구간 경계에서만 올리는 사본이다(실재생과 같은 규칙) —
  // 틱 안에서 setState 업데이터에 부수효과(정지)를 섞으면 StrictMode 의 이중 호출에서 갈라진다.
  const [clock] = useState(() => createPlaybackClock(initialSeconds));
  const durationRef = useRef(durationSeconds);
  durationRef.current = durationSeconds;
  const rateRef = useRef(1);
  // 구간은 상태 허브가 effect 로 밀어 넣는다. ref 라서 값이 바뀌어도 틱이 재시작하지 않는다 —
  // 배속·구간을 바꿀 때마다 인터벌을 다시 걸면 그 순간의 100ms 가 통째로 밀린다.
  const boundsRef = useRef<PlaybackBounds | null>(null);

  /** 위치를 허브까지 올린다: 멈춤 · 시크 · 구간 경계에서만. 재생 틱은 clock.publish만 부른다 */
  const settle = useCallback(
    (seconds: number) => {
      clock.publish(seconds);
      setCurrentSeconds(seconds);
    },
    [clock],
  );

  useEffect(() => {
    if (!playing) return undefined;
    const timer = setInterval(() => {
      const bounds = boundsRef.current;
      const step = TICK_SECONDS * rateRef.current;
      const position = clock.getSeconds();
      if (bounds === null) {
        clock.publish(clamp(position + step, durationRef.current));
        return;
      }
      // 지금 위치가 이미 구간 밖인지 먼저 본다 (구간 앞에서 재생을 시작한 경우)
      const now = boundaryAction(position, bounds);
      if (now.kind === 'seek') {
        settle(now.toSeconds);
        return;
      }
      if (now.kind === 'stop') {
        setPlaying(false);
        settle(now.atSeconds);
        return;
      }
      // 한 걸음 간 뒤 끝을 넘는지 본다 — 넘어간 뒤에 되돌리면 한 틱만큼 구간 밖이 재생된다
      const next = position + step;
      const after = boundaryAction(next, bounds);
      if (after.kind === 'seek') {
        settle(after.toSeconds);
        return;
      }
      if (after.kind === 'stop') {
        setPlaying(false);
        settle(after.atSeconds);
        return;
      }
      clock.publish(clamp(next, durationRef.current));
    }, TICK_MS);
    return () => clearInterval(timer);
  }, [playing, clock, settle]);

  return {
    playing,
    currentSeconds,
    clock,
    durationSeconds,
    // 목업에는 실패할 미디어가 없다
    error: null,
    togglePlay: useCallback(() => {
      setPlaying((value) => !value);
      // 멈추면 흐르던 위치가 허브에 닿아야 한다. 틱 안의 정지처럼 업데이터 밖에서 올린다
      setCurrentSeconds(clock.getSeconds());
    }, [clock]),
    seekTo: useCallback((seconds: number) => settle(clamp(seconds, durationRef.current)), [settle]),
    seekBy: useCallback(
      (delta: number) => settle(clamp(clock.getSeconds() + delta, durationRef.current)),
      [clock, settle],
    ),
    scrubTo: useCallback(
      (seconds: number) => clock.publish(clamp(seconds, durationRef.current)),
      [clock],
    ),
    endScrub: useCallback(() => setCurrentSeconds(clock.getSeconds()), [clock]),
    setRate: useCallback((rate: number) => {
      rateRef.current = rate;
    }, []),
    setBounds: useCallback((bounds: PlaybackBounds) => {
      boundsRef.current = bounds;
    }, []),
  };
}
