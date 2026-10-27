import type Hls from 'hls.js';
import type { Events, LevelDetails } from 'hls.js';

// 라이브 기동 문지기(POK-200 c13 · 설계 r9 2절 기동 · 6절 다변 목록 표). 다변 목록(index.m3u8)은 매체 목록을 받기 전에는
// 어느 자리에서 시작할지 정할 수 없다. 그래서 autoStartLoad:false 재생기에 처리기를 달아 MANIFEST_PARSED 에서 목록 받기만
// 깨우고, 첫 LEVEL_UPDATED 에서 위치를 정한다. 이 사건은 stream-controller 의 onLevelLoaded 안에서 tick 보다 먼저 동기로
// 나서, 여기서 부른 startLoad(pos) 의 tick 이 목표 조각을 먼저 청한다. LEVEL_LOADED 는 stream-controller 처리기가 앱보다
// 먼저 돌며 라이브 시작 조각을 먼저 청하므로 쓰지 않는다. 이 순서는 hlsStart.hlsjs.test.ts 가 진짜 hls.js 로 고정한다.

/** 첫 목록을 본 결정 — 그 자리(미디어 시각 초)에서 받기를 시작하거나, 못 본 녹화가 잘리니 시작하지 않는다 */
export type LiveStart = { kind: 'load'; position: number } | { kind: 'truncated' };

/**
 * autoStartLoad:false 인 hls 에 기동 처리기를 단다. 첫 LEVEL_UPDATED 에서 decide(details) 를 한 번 부른다. 잘림이면
 * stopLoad() 뒤 onTruncated 를 다음 마이크로태스크에 부른다 — 처리기 안에서 바로 해체하면 onLevelLoaded 의 나머지가
 * 부서진 컨트롤러로 돈다. 처리기는 재생기 하나에 한 번만 돈다: startLoad 가 level 컨트롤러도 다시 깨워 목록을 한 번 더
 * 받을 수 있어서다. events 는 hls.js 의 Events 값이다 — hls.js 를 동적 import 하는 호출부가 넘긴다
 */
export function armLiveStart(
  hls: Hls,
  events: typeof Events,
  decide: (details: LevelDetails) => LiveStart,
  onTruncated: () => void,
): void {
  hls.once(events.MANIFEST_PARSED, () => hls.startLoad(-1));
  hls.once(events.LEVEL_UPDATED, (_event, data) => {
    const start = decide(data.details);
    if (start.kind === 'load') {
      hls.startLoad(start.position);
      return;
    }
    hls.stopLoad();
    queueMicrotask(onTruncated);
  });
}
