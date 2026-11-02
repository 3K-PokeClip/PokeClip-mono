import { M3U8Parser, PlaylistLevelType } from 'hls.js';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { RewindStatusResult } from '@/api/rewindStatus';
import { stubFetch } from '@/test/mockFetch';
import { pos, resetEventKit, stamped, startRun, streamPoll } from './handoffTestKit';
import { decideLiveStart } from './hlsStart';
import { HANDOFF_SKIP_NOTICE } from './liveHandoff';
import { probeLiveWindow } from './liveWindowProbe';
import {
  openSession,
  transition,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';

// M5c 실측 모양(2026-10-28 — 라이브 TD 6 · 실제 조각 4초)을 탐침 → P1 → 재생기 기동 판정까지 한 줄로 잇는다
// (POK-200 c13 · PR 검수 cx M). 하한 계산만 따로 재면 셋 가운데 하나가 다른 여유를 써도 못 잡는다.
// 탐침의 하한이 3 × TD(18초)면 P 를 잘라 넘기지 않고, 기동 판정이 3 × TD 면 넘긴 뒤 잘림으로 되돌린다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-28T05:00:00.000Z');
/** 되감기 목록 끝 E */
const E = T0 + 60 * MIN;
/** 라이브 목록 끝 — 되감기보다 3초 앞선다 */
const LIVE_END = E + 3_000;
/** 넘길 자리 — 되감기 띠(E − 1 TD) 안이고 라이브 끝 8초 앞이다(M5c 의 P = 끝 − 8.0초) */
const P = LIVE_END - 8_000;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
/** 라이브 조각 길이(초) — 맨 앞 셋 합 12.2초 · TD 6 이라 3 × TD 면 18초 */
const LIVE_FRAGS = [4.2, 4, 4, 4, 4, 4];
const LIVE_FIRST = LIVE_END - LIVE_FRAGS.reduce((sum, d) => sum + d, 0) * 1000;

/** MediaMTX 모양 라이브 매체 목록 — TD 6 · PDT 는 끝 두 조각에만 단다(2026-10-27 로컬 실측) */
function livePlaylist(): string {
  const lines = [
    '#EXTM3U',
    '#EXT-X-VERSION:9',
    '#EXT-X-TARGETDURATION:6',
    '#EXT-X-MEDIA-SEQUENCE:90',
  ];
  let at = LIVE_FIRST;
  LIVE_FRAGS.forEach((duration, i) => {
    if (i >= LIVE_FRAGS.length - 2) {
      lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(at).toISOString()}`);
    }
    lines.push(`#EXTINF:${duration.toFixed(3)},`, `seg${90 + i}.mp4`);
    at += duration * 1000;
  });
  return lines.join('\n');
}

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  return { kind: 'ok', status: { ready: true, mode: 'live', url, firstPdtMs, headPdtMs } };
}

function feed(start: PlaybackSessionState, events: SessionEvent[], now: number) {
  let state = start;
  const effects: SessionEffect[] = [];
  for (const event of events) {
    const next = transition(state, stamped(state, event), now);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

let info: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  resetEventKit();
  info = vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  info.mockRestore();
  vi.unstubAllGlobals();
});

/** A1 을 P 에서 보는 되감기 세션 — 목록 끝 E · 문이 섰고 위치 사건 전이다 */
function watchingAtP(now: number): PlaybackSessionState {
  startRun();
  const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
  return feed(
    fresh,
    [
      streamPoll(liveReady(A1, T0, E)),
      { type: 'seek', targetMs: P, view: { lowerMs: P + 1, endMs: E + 28_000 } },
      {
        type: 'list-loaded',
        url: A1,
        firstMs: T0,
        lastEndMs: E,
        targetDurationS: 6,
        headSpanMs: 12_000,
      },
      streamPoll(liveReady(A1, T0, E), X_A, now),
    ],
    now,
  ).state;
}

it('M5c 모양 라이브 목록 — 탐침 하한은 맨 앞 조각 셋 길이라 P 에서 넘기고, 재생기 기동도 P 에서 시작한다', async () => {
  const text = livePlaylist();
  stubFetch(() => new Response(text, { status: 200 }));
  const now = E + 1_000;

  // 탐침 요청 → 진짜 탐침(목록 받기 · 해석 · 하한)
  const asked = feed(watchingAtP(now), [pos(P)], now);
  expect(asked.effects).toEqual([{ kind: 'probe-live', seq: 1, url: LIVE_URL }]);
  const result = await probeLiveWindow(LIVE_URL, new AbortController().signal);
  expect(result).toEqual({ kind: 'ok', floorMs: LIVE_FIRST + 12_200, endMs: LIVE_END });

  // P1 — 하한(끝 − 12초)이 P(끝 − 8초) 앞이라 P 에서 엄격 시작으로 넘긴다
  const done = feed(asked.state, [{ type: 'live-probe', seq: 1, result }], now + 300);
  expect(done.state.phase).toEqual({ kind: 'live' });
  expect(done.state.source).toMatchObject({ kind: 'live', startAtMs: P, strictStart: true });

  // 재생기 기동 — 같은 목록의 첫 LEVEL_UPDATED 에서 잘림이 아니라 P 의 미디어 시각에서 받는다
  const details = M3U8Parser.parseLevelPlaylist(text, LIVE_URL, 0, PlaylistLevelType.MAIN, 0, null);
  const { startAtMs, strictStart } = done.state.source;
  expect(decideLiveStart(details, startAtMs!, strictStart)).toEqual({
    kind: 'load',
    position: (P - LIVE_FIRST) / 1000,
  });
});

/**
 * M5b 실측 모양(2026-10-30) — 재송출 직후 MediaMTX 라이브 목록은 맨 앞이 EXT-X-GAP 조각 여섯(5.933초씩)이고, 첫 내용
 * 조각이 P 보다 3.4초 뒤다. PDT 는 내용 조각 끝 둘에만 단다
 */
function gapLedPlaylist(contentFirstMs: number): string {
  const lines = [
    '#EXTM3U',
    '#EXT-X-VERSION:10',
    '#EXT-X-TARGETDURATION:6',
    '#EXT-X-MEDIA-SEQUENCE:1',
  ];
  for (let i = 0; i < 6; i += 1) lines.push('#EXT-X-GAP', '#EXTINF:5.93333,', 'gap.mp4');
  [0, 1, 2].forEach((i) => {
    if (i >= 1) {
      lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(contentFirstMs + i * 4_000).toISOString()}`);
    }
    lines.push('#EXTINF:4.000,', `seg${7 + i}.mp4`);
  });
  return lines.join('\n');
}

it('M5b 모양(맨 앞 GAP 여섯) — 하한은 첫 내용 조각부터라 P 를 자르는 것으로 보고 넘기지 않고, 재생기 기동도 잘림이다', async () => {
  const contentFirst = P + 3_400;
  const text = gapLedPlaylist(contentFirst);
  stubFetch(() => new Response(text, { status: 200 }));
  const now = E + 1_000;
  const asked = feed(watchingAtP(now), [pos(P)], now);
  const result = await probeLiveWindow(LIVE_URL, new AbortController().signal);
  // GAP 을 세면 하한 = 첫 GAP + 앞 셋(17.8초)이라 P 앞에 선다(실측 599754 < P 614156)
  expect(result).toEqual({
    kind: 'ok',
    floorMs: contentFirst + 12_000,
    endMs: contentFirst + 12_000,
  });

  // P2 — 하한이 P 뒤라 넘기지 않고 LIVE 안내를 한 번 한다
  const done = feed(asked.state, [{ type: 'live-probe', seq: 1, result }], now + 300);
  expect(done.state.phase.kind).toBe('rewind');
  expect(done.state.source.url).toBe(A1);
  expect(done.effects).toContainEqual({ kind: 'notice', text: HANDOFF_SKIP_NOTICE });

  // 재생기의 첫 목록 재검사도 같은 하한이다 — hls.js 가 GAP 으로 읽은 조각을 빼고 잰다
  const details = M3U8Parser.parseLevelPlaylist(text, LIVE_URL, 0, PlaylistLevelType.MAIN, 0, null);
  expect(details.fragments.filter((f) => f.gap)).toHaveLength(6);
  expect(decideLiveStart(details, P, true)).toEqual({ kind: 'truncated' });
});

it('모두 GAP 인 첫 목록 — 하한을 정하지 못하므로 엄격 시작이어도 재생기의 기본 시작 위치(-1)에서 받는다(계약3)', () => {
  const lines = [
    '#EXTM3U',
    '#EXT-X-VERSION:10',
    '#EXT-X-TARGETDURATION:6',
    '#EXT-X-MEDIA-SEQUENCE:1',
  ];
  lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(P - 6_000).toISOString()}`);
  for (let i = 0; i < 5; i += 1) lines.push('#EXT-X-GAP', '#EXTINF:6.000,', 'gap.mp4');
  const details = M3U8Parser.parseLevelPlaylist(
    lines.join('\n'),
    LIVE_URL,
    0,
    PlaylistLevelType.MAIN,
    0,
    null,
  );
  expect(details.fragments.every((f) => f.gap)).toBe(true);
  for (const strictStart of [true, false]) {
    expect(decideLiveStart(details, P, strictStart)).toEqual({ kind: 'load', position: -1 });
  }
});
