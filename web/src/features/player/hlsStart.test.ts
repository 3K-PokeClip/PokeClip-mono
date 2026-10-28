import type Hls from 'hls.js';
import type { Events, LevelDetails } from 'hls.js';
import { describe, expect, it, vi } from 'vitest';
import { armStart, createSwapPause, trackLoadFrom, type StartSource } from './hlsStart';
import { startLoadPosition } from './playbackEdge';

// 재생기 기동 · 교체 보조(POK-200 c13). useHlsPlayback 이 재생기 하나마다 거는 세 부품을
// 따로 잰다 — 기동 처리기 달기 · 기동 전 받기 위치 장부 · 멈춘 채 갈아 끼우기 표.
// 진짜 hls.js 의 사건 순서는 hlsStart.hlsjs.test.ts 몫이다.

type Handler = (event: string, data: unknown) => void;

const EVENTS = {
  MANIFEST_PARSED: 'hlsManifestParsed',
  LEVEL_LOADED: 'hlsLevelLoaded',
  LEVEL_UPDATED: 'hlsLevelUpdated',
} as unknown as typeof Events;

/** 처리기 등록과 받기 호출만 흉내 낸다 */
class FakeHls {
  readonly handlers = new Map<string, Handler[]>();
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  on(event: string, handler: Handler) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  once(event: string, handler: Handler) {
    const wrapped: Handler = (e, data) => {
      const rest = (this.handlers.get(event) ?? []).filter((h) => h !== wrapped);
      this.handlers.set(event, rest);
      handler(e, data);
    };
    this.on(event, wrapped);
  }
  trigger(event: string, data: unknown = {}) {
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  count(event: string): number {
    return this.handlers.get(event)?.length ?? 0;
  }
}

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 영상 0초부터 4초 조각 n 개(PDT0 부터) */
function details(n: number): LevelDetails {
  const fragments = [...Array(n).keys()].map((i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: PDT0 + i * 4_000,
  }));
  return { fragments, targetduration: 4, live: true } as unknown as LevelDetails;
}

function arm(source: StartSource) {
  const hls = new FakeHls();
  const onLoadFrom = vi.fn();
  const onTruncated = vi.fn();
  armStart(hls as unknown as Hls, EVENTS, source, { set: onLoadFrom }, onTruncated);
  return { hls, onLoadFrom, onTruncated };
}

describe('armStart — 시작 시각이 있는 재생기의 기동 처리기', () => {
  it('시작 시각이 없으면 처리기를 달지 않는다 — hls.js 가 스스로 시작한다', () => {
    for (const kind of ['live', 'rewind'] as const) {
      const { hls } = arm({ kind, startAtMs: null, strictStart: false });
      expect(hls.handlers.size).toBe(0);
    }
  });

  it('되감기는 첫 LEVEL_LOADED 에서 한 번만 그 자리를 받고 받기 위치로 적는다', () => {
    const startAtMs = PDT0 + 10_000;
    const { hls, onLoadFrom } = arm({ kind: 'rewind', startAtMs, strictStart: false });
    const first = details(8);
    const want = startLoadPosition(first.fragments, startAtMs, 'rewind', 4);

    hls.trigger(EVENTS.LEVEL_LOADED, { details: first });
    hls.trigger(EVENTS.LEVEL_LOADED, { details: details(9) });

    expect(want).not.toBeNull();
    expect(hls.startLoad.mock.calls).toEqual([[want]]);
    expect(onLoadFrom.mock.calls).toEqual([[want]]);
    expect(hls.count(EVENTS.LEVEL_UPDATED)).toBe(0);
  });

  it('되감기 목록에 시각 표시가 없으면 받기 위치는 null · hls.js 가 고르는 -1 로 받는다', () => {
    const { hls, onLoadFrom } = arm({ kind: 'rewind', startAtMs: PDT0, strictStart: false });
    const undated = { fragments: [{ start: 0, duration: 4, programDateTime: null }] };

    hls.trigger(EVENTS.LEVEL_LOADED, { details: { ...undated, targetduration: 4 } });

    expect(hls.startLoad.mock.calls).toEqual([[-1]]);
    expect(onLoadFrom.mock.calls).toEqual([[null]]);
  });

  it('라이브는 기동 문지기에 맡긴다 — 첫 LEVEL_UPDATED 에서 자리를 정하고 LEVEL_LOADED 는 보지 않는다', () => {
    const startAtMs = PDT0 + 20_000;
    const { hls, onLoadFrom } = arm({ kind: 'live', startAtMs, strictStart: true });

    expect(hls.count(EVENTS.LEVEL_LOADED)).toBe(0);
    hls.trigger(EVENTS.MANIFEST_PARSED);
    hls.trigger(EVENTS.LEVEL_UPDATED, { details: details(8) });

    const want = startLoadPosition(details(8).fragments, startAtMs, 'live', 4);
    expect(hls.startLoad.mock.calls).toEqual([[-1], [want]]);
    expect(onLoadFrom).not.toHaveBeenCalled();
  });

  it('strictStart 라이브의 시작이 잘리면 받기를 멈추고 다음 마이크로태스크에 알린다', async () => {
    const { hls, onTruncated } = arm({ kind: 'live', startAtMs: PDT0, strictStart: true });

    hls.trigger(EVENTS.LEVEL_UPDATED, { details: details(8) });
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
    expect(onTruncated).not.toHaveBeenCalled();
    await Promise.resolve();

    expect(onTruncated).toHaveBeenCalledTimes(1);
    expect(hls.startLoad).not.toHaveBeenCalled();
  });
});

describe('trackLoadFrom — 기동 전 받기 위치 장부', () => {
  function ledger(currentTime: number) {
    const video = { currentTime };
    return { video, load: trackLoadFrom(video) };
  }

  it('처음엔 비었고 set 한 자리를 그대로 낸다', () => {
    const { load } = ledger(0);
    expect(load.current()).toBeNull();
    load.set(12);
    expect(load.current()).toBe(12);
    load.set(null);
    expect(load.current()).toBeNull();
  });

  it('기동 전 시크는 받기 위치를 그 자리로 옮기고, 기동 뒤 시크는 옮기지 않는다', () => {
    const { video, load } = ledger(30);
    load.noteSeeking(false);
    expect(load.current()).toBeNull();
    load.noteSeeking(true);
    expect(load.current()).toBe(30);
    video.currentTime = 40;
    load.noteSeeking(false);
    expect(load.current()).toBe(30);
  });

  it('떼기(MEDIA_DETACHING)는 앞서 적은 마지막 재생점을 받기 위치로 삼는다 — 떼기 뒤의 0 은 적지 않는다', () => {
    const { video, load } = ledger(25);
    load.notePlayhead();
    video.currentTime = 0;
    load.notePlayhead();
    load.rememberLastPlayhead();
    expect(load.current()).toBe(25);
  });

  it('재생점을 적은 적이 없으면 떼기는 받기 위치를 바꾸지 않는다', () => {
    const { load } = ledger(0);
    load.set(7);
    load.rememberLastPlayhead();
    expect(load.current()).toBe(7);
  });

  it('시크 중의 목표도 재생점으로 적는다 — seeked 전에 떼어도 그 목표에서 받는다', () => {
    const { video, load } = ledger(50);
    load.noteSeeking(false);
    video.currentTime = 0;
    load.rememberLastPlayhead();
    expect(load.current()).toBe(50);
  });

  it('네트워크 복구는 지금 재생점을 받기 위치로 삼되 0 · 무한대는 두고 앞 값을 지킨다', () => {
    const { video, load } = ledger(18);
    load.rememberPlayhead();
    expect(load.current()).toBe(18);
    for (const time of [0, Number.POSITIVE_INFINITY, Number.NaN]) {
      video.currentTime = time;
      load.rememberPlayhead();
      expect(load.current()).toBe(18);
    }
  });

  it('기동이 끝나면(hasEnoughToStart) 받기 위치를 버리고, 끝나기 전이면 둔다', () => {
    const { load } = ledger(0);
    load.set(9);
    load.dropIfStarted(false);
    expect(load.current()).toBe(9);
    load.dropIfStarted(true);
    expect(load.current()).toBeNull();
  });
});

describe('createSwapPause — 멈춘 채 갈아 끼우기 표', () => {
  it('사람이 멈추지 않았으면 넘김 되돌림이 표를 걸지 않는다', () => {
    const swap = createSwapPause();
    swap.keepIfUserPaused(3);
    expect(swap.holds(4)).toBe(false);
  });

  it('사람이 멈췄으면 건 세대보다 새 재생기 하나만 틀지 않고 표를 지운다', () => {
    const swap = createSwapPause();
    swap.userPaused();
    swap.keepIfUserPaused(3);
    expect(swap.holds(4)).toBe(true);
    expect(swap.holds(5)).toBe(false);
  });

  it('건 세대 이하의 재생기는 틀지 않되 표를 먹지 않는다 — 옛 재생기가 먼저 먹지 않게', () => {
    const swap = createSwapPause();
    swap.keep(3);
    expect(swap.holds(3)).toBe(true);
    expect(swap.holds(2)).toBe(true);
    expect(swap.holds(4)).toBe(true);
    expect(swap.holds(4)).toBe(false);
  });

  it('사람이 다시 틀면 표와 멈춤을 함께 지운다', () => {
    const swap = createSwapPause();
    swap.userPaused();
    swap.keep(3);
    swap.userPlayed();
    expect(swap.holds(4)).toBe(false);
    swap.keepIfUserPaused(4);
    expect(swap.holds(5)).toBe(false);
  });
});
