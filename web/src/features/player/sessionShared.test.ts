import { describe, expect, it } from 'vitest';
import { restartBySpec, resumeBySpec } from './dvr404TestKit';
import { openSession, type PlaybackSessionState, type PlaybackSource } from './rewindSwitch';
import { RELIST_FROM_FLOOR_MS, confirmedAtMs, restartAtMs, resumeAtMs } from './sessionShared';

// 이어 볼 자리 판정 하나(POK-528 c7 — 계획 3-2 · 8-0 의 resumeAtMs 표). 되감기 소스는
// 확인된 위치 → 끝나지 않은 시크 → 이 소스의 시작 요청(「목록 하한에서」 는 모름), 그 밖의
// 소스는 lastAtMs 다. 감싸개(dvr404TestKit)의 사본과 같은 값인지도 함께 본다.

const L = 1_000_000;
const Q = 2_000_000;
const S0 = 3_000_000;
const GENERATION = 4;

type Position = 'confirmed' | 'seeking' | 'requested' | 'floor' | 'none';

/** 소스의 시작 요청 — 목록 하한에서(0) · 없음 · 그 밖은 S0 */
function startOf(position: Position): number | null {
  if (position === 'floor') return RELIST_FROM_FLOOR_MS;
  return position === 'none' ? null : S0;
}

/** 소스 종류 kind · 위치 상태 position 의 손 상태 — 위치 칸만 다르다 */
function stateOf(kind: PlaybackSource['kind'], position: Position): PlaybackSessionState {
  const base = openSession({ roundId: 'r-A', accessKey: 'r-A|k', liveUrl: 'https://m/live' });
  const source: PlaybackSource = {
    kind,
    url: 'https://m/a',
    startAtMs: startOf(position),
    generation: GENERATION,
    strictStart: false,
  };
  const lastAtMs = position === 'none' || position === 'floor' ? null : L;
  return {
    ...base,
    source,
    lastAtMs,
    positionGeneration: position === 'confirmed' ? GENERATION : position === 'seeking' ? null : 1,
    pendingSeekMs: position === 'seeking' ? Q : null,
  };
}

describe('resumeAtMs — 이어 볼 자리(계획 3-2)', () => {
  it.each<[PlaybackSource['kind'], Position, number | null]>([
    ['rewind', 'confirmed', L],
    ['rewind', 'seeking', Q],
    ['rewind', 'requested', S0],
    ['rewind', 'floor', null],
    ['rewind', 'none', null],
    ['vod', 'confirmed', L],
    ['vod', 'seeking', L],
    ['vod', 'requested', L],
    ['vod', 'none', null],
    ['live', 'confirmed', L],
    ['live', 'requested', L],
    ['live', 'none', null],
  ])('%s · %s → %s', (kind, position, expected) => {
    const state = stateOf(kind, position);
    expect(resumeAtMs(state)).toBe(expected);
    expect(resumeBySpec(state)).toBe(expected);
  });

  it('restartAtMs — 모르면 목록 하한에서(0) · 아는 자리는 그대로', () => {
    expect(restartAtMs(stateOf('rewind', 'none'))).toBe(RELIST_FROM_FLOOR_MS);
    expect(restartAtMs(stateOf('rewind', 'floor'))).toBe(RELIST_FROM_FLOOR_MS);
    expect(restartAtMs(stateOf('rewind', 'requested'))).toBe(S0);
    expect(restartBySpec(stateOf('rewind', 'seeking'))).toBe(Q);
  });

  it('confirmedAtMs — 지금 소스 세대가 낸 위치만', () => {
    expect(confirmedAtMs(stateOf('rewind', 'confirmed'))).toBe(L);
    expect(confirmedAtMs(stateOf('rewind', 'requested'))).toBeNull();
    expect(confirmedAtMs(stateOf('vod', 'seeking'))).toBeNull();
  });
});
