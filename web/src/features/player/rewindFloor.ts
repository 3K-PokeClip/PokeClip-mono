import type Hls from 'hls.js';
import type { LevelDetails } from 'hls.js';
import { EDGE_MARGIN_TD } from './playbackEdge';

// 되감기 재생기의 창 하한 지키기(설계 5.3.1절 · 진단 28_). 되감기 목록은 ENDLIST 없는 미닫이 live 목록이라, 목록이 갱신될 때
// 재생점이 첫 조각 앞이면 hls.js 1.7.3 의 synchronizeToLiveEdge 가 재생점을 라이브 끝으로 옮긴다(stream-controller.ts
// 688-735 · 일시정지를 보지 않고 끄는 설정도 없다). hls.js 는 같은 갱신에서 LEVEL_UPDATED 를 그보다 먼저 동기로 내보낸다
// (674행). 그 처리기에서 재생점을 창 하한으로 옮겨 두면 hls.js 가 옮길 까닭이 사라진다 — 튐을 되돌리지 않고 일어나지 않게 한다.

/**
 * 재생점이 첫 조각에서 이 배수 × TD 안으로 들어오면 옮긴다. hls.js 는 첫 조각 앞(−0.25초)부터 옮긴다 — 한 조각 앞당겨 다음
 * 목록 갱신(≈ 1 TD 뒤)이 오기 전에 먼저 옮긴다
 */
const FLOOR_TRIGGER_TD = 1;

export interface RewindFloorInput {
  hls: Pick<Hls, 'hasEnoughToStart' | 'startLoad'>;
  video: Pick<HTMLVideoElement, 'currentTime' | 'readyState'>;
  details: Pick<LevelDetails, 'fragmentStart' | 'targetduration' | 'live'>;
  /** 기동 전 받기 위치(앱이 startLoad 에 준 자리 · 기동 전 시크 자리). 모르면 null */
  loadFrom: number | null;
}

/** 옮긴 갈래와 자리(영상 초). seek = currentTime 을 옮김 · reload = 기동 전이라 받기 위치를 다시 잡음 */
export interface RewindFloorMove {
  kind: 'seek' | 'reload';
  to: number;
}

/**
 * LEVEL_UPDATED 처리기에서 부른다. 재생점이 창 밖으로 떨어지기 직전이면 창 하한(첫 조각 + EDGE_MARGIN)으로 옮기고 그 자리를,
 * 아니면 null 을 낸다. ENDLIST 목록은 hls.js 가 옮기지 않으므로(stream-controller.ts 688 `newDetails.live`) 그대로 둔다.
 * 기동 전(조각이 아직 붙지 않음)에는 hls.js 가 currentTime 이 아니라 받기 위치를 보고 그것을 라이브 끝으로 바꾸므로
 * (base-stream-controller.ts playhead · stream-controller.ts 731), 받기 위치를 startLoad 로 다시 잡는다
 */
export function guardRewindFloor({
  hls,
  video,
  details,
  loadFrom,
}: RewindFloorInput): RewindFloorMove | null {
  if (!details.live) return null;
  const td = details.targetduration;
  const beforeStart = !hls.hasEnoughToStart || video.readyState === 0;
  const position = beforeStart ? loadFrom : video.currentTime;
  if (position === null || position >= details.fragmentStart + FLOOR_TRIGGER_TD * td) return null;
  const floor = details.fragmentStart + EDGE_MARGIN_TD * td;
  if (beforeStart) {
    hls.startLoad(floor);
    return { kind: 'reload', to: floor };
  }
  video.currentTime = floor;
  return { kind: 'seek', to: floor };
}
