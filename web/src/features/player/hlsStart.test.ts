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
    const want = startLoadPosition(first.fragments, startAtMs, 'rewind', 4, true);

    hls.trigger(EVENTS.LEVEL_LOADED, { details: first });
    hls.trigger(EVENTS.LEVEL_LOADED, { details: details(9) });

    expect(want).not.toBeNull();
    expect(hls.startLoad.mock.calls).toEqual([[want]]);
    expect(onLoadFrom.mock.calls).toEqual([[want]]);
    expect(hls.count(EVENTS.LEVEL_UPDATED)).toBe(0);
  });

  it.each<[string, boolean, number]>([
    ['끝난 목록(ENDLIST)이면 첫 내용 조각(영상 6초)', false, 6],
    ['방송 중이면 첫 내용 조각 + 그 조각 하나(영상 10초)', true, 10],
  ])('되감기 맨 왼쪽 시작은 그 목록이 방송 중인가로 정한다 — %s', (_name, live, want) => {
    const { hls, onLoadFrom } = arm({ kind: 'rewind', startAtMs: PDT0, strictStart: false });
    // 맨 앞 GAP 하나(6초) 뒤 4초 조각 900 개 — 합이 한 시간을 넘는다
    const fragments = [
      { start: 0, duration: 6, programDateTime: PDT0, gap: true },
      ...[...Array(900).keys()].map((i) => ({
        start: 6 + i * 4,
        duration: 4,
        programDateTime: PDT0 + 6_000 + i * 4_000,
      })),
    ];

    hls.trigger(EVENTS.LEVEL_LOADED, { details: { fragments, targetduration: 6, live } });

    expect(hls.startLoad.mock.calls).toEqual([[want]]);
    expect(onLoadFrom.mock.calls).toEqual([[want]]);
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

    const want = startLoadPosition(details(8).fragments, startAtMs, 'live', 4, true);
    expect(hls.startLoad.mock.calls).toEqual([[-1], [want]]);
    expect(onLoadFrom).not.toHaveBeenCalled();
  });

  describe('다시보기 — 시작 시각이 없어도 첫 LEVEL_LOADED 에서 받기 위치를 한 번 정한다', () => {
    /** 맨 앞 gaps 개가 GAP(3초)이고 그 뒤 4초 조각 6 개인 끝난 목록 — 첫 내용 조각은 영상 3 × gaps 초 */
    function vodDetails(gaps: number, allGap = false): LevelDetails {
      const fragments = [...Array(gaps + 6).keys()].map((i) => {
        const start = i < gaps ? i * 3 : gaps * 3 + (i - gaps) * 4;
        return {
          start,
          duration: i < gaps ? 3 : 4,
          programDateTime: PDT0 + start * 1000,
          gap: allGap || i < gaps,
        };
      });
      return { fragments, targetduration: 4, live: false } as unknown as LevelDetails;
    }

    it('시작 시각이 없고 맨 앞이 GAP 이면 첫 내용 조각에서 받는다', () => {
      const { hls, onLoadFrom } = arm({ kind: 'vod', startAtMs: null, strictStart: false });

      hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(3) });

      expect(hls.startLoad.mock.calls).toEqual([[9]]);
      expect(onLoadFrom.mock.calls).toEqual([[9]]);
    });

    it('시작 시각이 없고 맨 앞 GAP 이 없으면 목록 첫 조각(영상 0초)에서 받는다 — 지금과 같은 자리', () => {
      const { hls } = arm({ kind: 'vod', startAtMs: null, strictStart: false });

      hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(0) });

      expect(hls.startLoad.mock.calls).toEqual([[0]]);
    });

    it('시작 시각이 있으면 그 자리에서 받는다 — 맨 앞 GAP 안이면 첫 내용 조각으로 올린다', () => {
      const at = arm({ kind: 'vod', startAtMs: PDT0 + 20_000, strictStart: false });
      const early = arm({ kind: 'vod', startAtMs: PDT0 + 1_000, strictStart: false });

      at.hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(3) });
      early.hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(3) });

      expect(at.hls.startLoad.mock.calls).toEqual([[20]]);
      expect(early.hls.startLoad.mock.calls).toEqual([[9]]);
    });

    it('목록 끝 뒤의 시작 시각은 마지막 조각의 시작에서 받는다 — 받을 조각이 남는다', () => {
      const { hls } = arm({ kind: 'vod', startAtMs: PDT0 + 60 * 60_000, strictStart: false });

      hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(3) });

      expect(hls.startLoad.mock.calls).toEqual([[29]]);
    });

    it('모두 GAP 이면 시작 시각이 있든 없든 받기 위치는 null · hls.js 가 고르는 -1 로 받는다', () => {
      for (const startAtMs of [null, PDT0 + 20_000]) {
        const { hls, onLoadFrom } = arm({ kind: 'vod', startAtMs, strictStart: false });

        hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(0, true) });

        expect(hls.startLoad.mock.calls).toEqual([[-1]]);
        expect(onLoadFrom.mock.calls).toEqual([[null]]);
      }
    });

    /** 4초 조각 셋 — gapAt 번째만 GAP 인 끝난 목록 */
    function threeWithGapAt(gapAt: number): LevelDetails {
      const fragments = [0, 1, 2].map((i) => ({
        start: i * 4,
        duration: 4,
        programDateTime: PDT0 + i * 4_000,
        gap: i === gapAt,
      }));
      return { fragments, targetduration: 4, live: false } as unknown as LevelDetails;
    }

    it('시작 시각이 가운데 GAP 조각 안이면 다음 내용 조각의 시작에서 받는다', () => {
      const { hls, onLoadFrom } = arm({ kind: 'vod', startAtMs: PDT0 + 6_000, strictStart: false });

      hls.trigger(EVENTS.LEVEL_LOADED, { details: threeWithGapAt(1) });

      expect(hls.startLoad.mock.calls).toEqual([[8]]);
      expect(onLoadFrom.mock.calls).toEqual([[8]]);
    });

    it.each<[string, number]>([
      ['마지막 GAP 조각 안', 10_000],
      ['목록 끝 뒤', 60_000],
    ])(
      '마지막 조각이 GAP 이면 시작 시각이 %s 여도 마지막 내용 조각의 시작에서 받는다',
      (_name, atMs) => {
        const { hls } = arm({ kind: 'vod', startAtMs: PDT0 + atMs, strictStart: false });

        hls.trigger(EVENTS.LEVEL_LOADED, { details: threeWithGapAt(2) });

        expect(hls.startLoad.mock.calls).toEqual([[4]]);
      },
    );

    it('한 번만 정한다 — 그 뒤 목록에서는 startLoad 하지 않고, 라이브 기동 문지기도 달지 않는다', () => {
      const { hls } = arm({ kind: 'vod', startAtMs: null, strictStart: false });

      hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(3) });
      hls.trigger(EVENTS.LEVEL_LOADED, { details: vodDetails(0) });

      expect(hls.startLoad.mock.calls).toEqual([[9]]);
      expect(hls.count(EVENTS.MANIFEST_PARSED) + hls.count(EVENTS.LEVEL_UPDATED)).toBe(0);
    });
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

// 재생기의 pause 처리기는 takeAppPause 가 참이면 userPaused 를 부르지 않는다(useHlsPlayback.ts onPause). 그 분기는 여기서
// 다시 짜지 않는다 — 아래는 표시 메서드의 결과만 보고, 분기 배선은 재생기 훅 시험(useHlsPlayback.dvr404.test.ts 의
// holdFrame 넷)이 본다
describe('createSwapPause — 앱이 건 멈춤 표시(POK-528 계획 4-1)', () => {
  it('앱이 곧 멈춘다고 적으면 다음 pause 하나만 앱 멈춤으로 먹는다', () => {
    const swap = createSwapPause();
    expect(swap.takeAppPause()).toBe(false);
    swap.appPausing();
    expect(swap.takeAppPause()).toBe(true);
    expect(swap.takeAppPause()).toBe(false);
  });

  it('play 사건이 묵은 앱 멈춤 표시를 지운다 — 다음 사람 멈춤을 삼키지 않게', () => {
    const swap = createSwapPause();
    swap.appPausing();
    swap.userPlayed();
    expect(swap.takeAppPause()).toBe(false);
  });

  it('앱 멈춤 표시를 먹어도 사람 멈춤으로 적히지 않는다 — 넘김 되돌림이 표를 걸지 않는다', () => {
    const swap = createSwapPause();
    swap.appPausing();
    expect(swap.takeAppPause()).toBe(true);
    swap.keepIfUserPaused(3);
    expect(swap.holds(4)).toBe(false);
  });

  it('표시를 먹은 뒤의 사람 멈춤은 지금처럼 넘김 되돌림이 표를 건다 — 표시가 남아 삼키지 않는다', () => {
    const swap = createSwapPause();
    swap.appPausing();
    expect(swap.takeAppPause()).toBe(true);
    expect(swap.takeAppPause()).toBe(false);
    swap.userPaused();
    swap.keepIfUserPaused(3);
    expect(swap.holds(4)).toBe(true);
  });
});
