'use client';

import { useSyncExternalStore } from 'react';
import type { EditorPlayback } from './editorPlayback';

// 재생 위치 저장소. 재생 중 위치는 프레임마다 바뀐다: 이 값을 React 상태로 들면 재생 어댑터를 부르는 화면(WiredStudio)과 상태 허브,
// 그 아래 화면 전체가 초당 60번 다시 돈다. 그래서 연속 값은 여기에만 두고, 재생선 · 시간 표기처럼 프레임마다 달라져야 하는 자리만
// 구독한다.
//
// 읽는 길은 셋이다.
// - 그리는 자리: usePlaybackValue. 고른 값이 그대로면 다시 그리지 않는다(시간 표기는 0.1초마다, 구간 판정은 넘나들 때만).
// - 동작 콜백(썸네일 고르기 · 구간 끝 찍기 · 업로드 창 열기): 구독하지 않고 누른 순간에 getSeconds()로 읽는다.
// - 어댑터의 currentSeconds: 멈춤 · 시크 · 구간 경계에서만 바뀌는 값이다. 재생 중에는 낡았으므로 판정에 쓰지 않는다.

export interface PlaybackClock {
  /** 지금 위치(초). 재생 중에는 프레임마다 바뀐다 */
  getSeconds: () => number;
  subscribe: (listener: () => void) => () => void;
}

/** 어댑터가 쥐는 쪽. 화면에는 읽는 쪽(PlaybackClock)만 넘긴다 */
export interface WritablePlaybackClock extends PlaybackClock {
  publish: (seconds: number) => void;
}

export function createPlaybackClock(initialSeconds: number): WritablePlaybackClock {
  let seconds = initialSeconds;
  const listeners = new Set<() => void>();
  return {
    getSeconds: () => seconds,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    publish(next) {
      if (next === seconds) return;
      seconds = next;
      for (const listener of listeners) listener();
    },
  };
}

/**
 * 재생 위치에서 고른 값을 구독한다. 고른 값이 달라질 때만 다시 그린다.
 * `select`는 같은 위치에 같은 값을 돌려줘야 한다: 객체를 새로 만들면 프레임마다 다시 그린다(원시값을 쓰거나 잡아 둔 객체를 돌려준다).
 */
export function usePlaybackValue<T>(clock: PlaybackClock, select: (seconds: number) => T): T {
  const snapshot = () => select(clock.getSeconds());
  return useSyncExternalStore(clock.subscribe, snapshot, snapshot);
}

/**
 * 재생 어댑터가 허브에 주는 것. `currentSeconds`의 뜻이 좁아진다 — 멈춤 · 시크 · 구간 경계에서만 바뀌고 재생 중 연속 값은 `clock`에 있다.
 */
export interface ClockedPlayback extends EditorPlayback {
  clock: PlaybackClock;
  /** 끄는 동안 재생 위치를 옮긴다. 저장소만 바뀌고 허브는 다시 그리지 않는다. 다 끌면 endScrub을 부른다 */
  scrubTo: (seconds: number) => void;
  /** 끌기가 끝났다: 마지막 위치를 허브에 올린다 */
  endScrub: () => void;
}
