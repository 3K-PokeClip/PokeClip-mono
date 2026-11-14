import { OPEN_STREAM_FACTS, streamFactsAfter, type StreamFacts } from './playbackEdge';
import { A1, NOW, P20, T0, feed, liveReady, poll, rewindAt } from './relistTestKit';
import type { PlaybackSessionState, SessionEvent } from './rewindSwitch';

// 방송이 끝났는가(recordingEndedOf)를 실제 세션 사건 순서로 시험하는 도구. 상태기계에 사건을 먹이면서, 스트림 답은
// usePlaybackSession 이 하듯 같은 답 · 같은 X 로 스트림 사실(streamFactsAfter)에도 접는다.

/** 사건 하나 — 그때의 세션 상태로 만든다(폴링 답의 물음 순번 · 404 의 세대가 차례를 따른다) */
export type Step = (state: PlaybackSessionState) => SessionEvent;

export interface Played {
  state: PlaybackSessionState;
  facts: StreamFacts;
}

/** start 에서 사건을 차례로 먹인다. facts 는 세션을 연 뒤의 스트림 사실이다(기본은 연 직후) */
export function play(
  start: PlaybackSessionState,
  steps: readonly Step[],
  facts: StreamFacts = OPEN_STREAM_FACTS,
): Played {
  let state = start;
  let folded = facts;
  for (const step of steps) {
    const event = step(state);
    // 상태기계와 같은 늦은 답 버림을 접기도 따른다(streamFactsAfter 가 순번으로 거른다)
    if (event.type === 'stream-poll') folded = streamFactsAfter(folded, state, event);
    state = feed(state, [event]).state;
  }
  return { state, facts: folded };
}

/** 방송 중 A1 을 P20 에서 되감는 세션에 사건을 먹인다 — 첫 사건은 A1 이 아직 라이브라는 스트림 답이다 */
export function playWatching(...steps: Step[]): Played {
  return play(rewindAt(P20), [() => poll(liveReady(A1, T0, NOW)), ...steps]);
}
