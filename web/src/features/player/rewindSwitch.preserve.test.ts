import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import {
  openSession,
  transition,
  type PlaybackSessionState,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { pos, resetEventKit, streamPoll } from './handoffTestKit';

// 꼬리 넘김이 들어와도 현행 그대로여야 하는 행의 시험(POK-200 c13 — 설계 r9 11절 T12 등).
// 기존 모듈과 시험 도우미만 읽는다 — 제품 파일을 되돌린 트리(음성 대조)에서도 이 파일이 로드되게.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const A3 = 'https://media.pokeclip.com/dvr/key-1/a3/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  const status: RewindStatus = { ready: true, mode: 'live', url, firstPdtMs, headPdtMs };
  return { kind: 'ok', status };
}

function run(start: PlaybackSessionState, events: SessionEvent[]): PlaybackSessionState {
  return events.reduce((state, event) => transition(state, event, NOW).state, start);
}

beforeEach(resetEventKit);

describe('끝남 대기의 이음 지점(현행 보존)', () => {
  it('T12: 다음 url 로 이은(R16) 직후의 끝 도달은 마지막 위치에서 셋째 url 로 잇는다', () => {
    const p = T0 + 20 * MIN;
    const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
    const joined = run(fresh, [
      streamPoll(liveReady(A1, T0, NOW)),
      { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
      { type: 'list-loaded', url: A1, firstMs: T0, lastEndMs: NOW, targetDurationS: 6 },
      pos(p),
      streamPoll(liveReady(A2, NOW - 2_000, NOW + MIN), X_A),
      pos(NOW),
      { type: 'end-reached' },
    ]);
    expect(joined.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: NOW });
    const waiting = run(joined, [{ type: 'end-reached' }]);
    expect(waiting.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: true });
    const resumed = run(waiting, [streamPoll(liveReady(A3, NOW - 1_000, NOW + 2 * MIN), X_A)]);
    expect(resumed.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(resumed.source).toMatchObject({ kind: 'rewind', url: A3, startAtMs: NOW });
  });
});
