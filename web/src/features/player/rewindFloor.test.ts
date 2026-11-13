import { describe, expect, it } from 'vitest';
import type { DatedFragment } from './dvrWindow';
import { startLoadPosition } from './playbackEdge';
import { guardRewindFloor, type RewindFloorInput } from './rewindFloor';

// 되감기 창 하한 지키기의 순수 몫(POK-573). 방송 중 되감기 하한 = 첫 내용 조각 + 그 조각 하나의 길이이고, 재생점이 목록 첫
// 조각보다 앞일 때만 옮긴다. 진짜 hls.js 와의 순서는 rewindFloor.hlsjs.test.ts 몫이다.

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 조각 길이(초) — 실제 조각 */
const D = 4;
/** gohlslib 모양 — TD 는 지금까지 본 조각 최대값으로 올라가기만 해 실제 조각보다 크다 */
const TD = 6;

type Details = RewindFloorInput['details'];

/** 영상 firstS 초부터 D 초 조각 n 개(영상 0초 = PDT0) */
function fragmentsFrom(firstS: number, n = 30): DatedFragment[] {
  return Array.from({ length: n }, (_, i) => {
    const start = firstS + i * D;
    return { start, duration: D, programDateTime: PDT0 + start * 1000 };
  });
}

/** 그 조각들의 목록 — live 가 거짓이면 ENDLIST 목록이다 */
function detailsOf(fragments: readonly DatedFragment[], live = true): Details {
  const fragmentStart = fragments[0]?.start ?? 0;
  return { fragmentStart, targetduration: TD, live, fragments } as unknown as Details;
}

/** 재생 중(기동 뒤)인 재생기에 목록 갱신 하나를 준다 — 옮겼으면 그 자리 */
function guard(details: Details, playhead: number): number | undefined {
  const video = { currentTime: playhead, readyState: 4 };
  const hls = { hasEnoughToStart: true, startLoad: () => {} };
  return guardRewindFloor({ hls, video, details, loadFrom: null })?.to;
}

describe('guardRewindFloor — 방송 중 되감기 하한 = 첫 내용 조각 + 그 조각 하나', () => {
  it('재생점이 첫 조각 앞이면 첫 내용 조각 + 그 조각 길이로 옮긴다', () => {
    expect(guard(detailsOf(fragmentsFrom(0)), -0.5)).toBe(4);
    // 맨 앞 GAP(6초) 뒤 첫 내용 조각 5초 — GAP 을 세지 않는다
    const gapped: DatedFragment[] = [
      { start: 0, duration: 6, programDateTime: PDT0, gap: true },
      { start: 6, duration: 5, programDateTime: PDT0 + 6_000 },
      ...fragmentsFrom(11, 5),
    ];
    expect(guard(detailsOf(gapped), -0.5)).toBe(11);
  });

  it('조각이 하나도 없어 길이를 모르면 첫 조각 + 1 × TD 로 둔다', () => {
    expect(guard(detailsOf([]), -0.5)).toBe(TD);
  });

  it('두 조각 빠진 목록에서 재생점이 새 첫 조각 앞이면 새 하한으로 옮긴다', () => {
    expect(guard(detailsOf(fragmentsFrom(0)), -1)).toBe(4);
    expect(guard(detailsOf(fragmentsFrom(8)), 7)).toBe(12);
  });

  it('재생점이 첫 조각과 하한 사이(1 TD 안)면 옮기지 않는다', () => {
    // 첫 조각 0 · TD 6 · 조각 4초 — 재생점 2 는 하한 4 앞이지만 목록 안이다
    expect(guard(detailsOf(fragmentsFrom(0)), 2)).toBeUndefined();
    expect(guard(detailsOf(fragmentsFrom(0)), 0)).toBeUndefined();
  });

  it('하한에 멈춘 재생점은 둘째 빠짐에서 한 번만 옮긴다', () => {
    let playhead = 4;
    const moves: Array<[number, number]> = [];
    [0, 4, 8, 8].forEach((firstS, update) => {
      const to = guard(detailsOf(fragmentsFrom(firstS)), playhead);
      if (to === undefined) return;
      moves.push([update, to]);
      playhead = to;
    });
    expect(moves).toEqual([[2, 12]]);
  });

  it('ENDLIST 목록에서는 옮기지 않는다', () => {
    expect(guard(detailsOf(fragmentsFrom(0), false), -5)).toBeUndefined();
  });

  it.each<[number, number | undefined]>([
    [0.1, undefined],
    [1, undefined],
    [3.9, undefined],
    [4, undefined],
    [4.1, 8.1],
  ])(
    '갱신 사이 조각 시작이 %s초 앞으로 가도 조각 하나 길이 안이면 하한에 멈춘 재생점을 옮기지 않는다',
    (shiftS, want) => {
      expect(guard(detailsOf(fragmentsFrom(shiftS)), 4)).toBe(want);
    },
  );
});

/**
 * 맨 왼쪽 착지 뒤 목록 갱신을 흉내 낸다. 조각 D 초 · TD 6 · 맨 앞 GAP 없음 · 시크 시각 0. 서버 목록은 phi + jD 에 조각 하나씩
 * 빠지고(시크 때 첫 조각 영상 0초), 앱 목록 갱신은 tau + k·u 에 와서 그 시각까지(같은 시각 포함) 빠진 것을 모두 본다. 착지는
 * 서버보다 n 빠짐 낡은 목록의 맨 왼쪽(제품 startLoadPosition)이다. 재생점은 1배속이되 착지 뒤 s 초 · 옮긴 뒤 sAfter 초
 * 멎는다. 시각은 정수 ms 로 센다 — 같은 시각 빠짐 · 경계 비교가 부동소수 오차로 갈리지 않게
 */
interface Scenario {
  n: number;
  uMs: number;
  phiMs: number;
  tauMs: number;
  sMs: number;
  sAfterMs: number;
  untilMs: number;
}

interface Run {
  landing: number;
  /** [갱신 시각(초), 옮긴 자리(영상 초)] */
  moves: Array<[number, number]>;
}

function serverFirstMs(sc: Scenario, tMs: number): number {
  if (tMs < sc.phiMs) return 0;
  return (Math.floor((tMs - sc.phiMs) / (D * 1000)) + 1) * D * 1000;
}

function simulate(sc: Scenario): Run {
  const stale = fragmentsFrom(-sc.n * D);
  const landing = startLoadPosition(stale, PDT0 - 60_000, 'rewind', TD, true);
  if (landing === null) throw new Error('착지 자리를 못 정했다');
  let anchor = { atMs: 0, fromMs: Math.round(landing * 1000), stallMs: sc.sMs };
  const moves: Array<[number, number]> = [];
  for (let tMs = sc.tauMs; tMs <= sc.untilMs; tMs += sc.uMs) {
    const elapsedMs = tMs - anchor.atMs;
    const playheadMs = anchor.fromMs + elapsedMs - Math.min(elapsedMs, anchor.stallMs);
    const to = guard(detailsOf(fragmentsFrom(serverFirstMs(sc, tMs) / 1000)), playheadMs / 1000);
    if (to === undefined) continue;
    moves.push([tMs / 1000, to]);
    anchor = { atMs: tMs, fromMs: Math.round(to * 1000), stallMs: sc.sAfterMs };
  }
  return { landing, moves };
}

function scenario(n: number, u: number, phi: number, tau: number, s: number, sAfter = 0): Scenario {
  const ms = (seconds: number) => Math.round(seconds * 1000);
  return {
    n,
    uMs: ms(u),
    phiMs: ms(phi),
    tauMs: ms(tau),
    sMs: ms(s),
    sAfterMs: ms(sAfter),
    untilMs: 120_000,
  };
}

describe('맨 왼쪽 착지 뒤 목록 갱신 — 착지 자리와 창 하한 지키기의 옮김', () => {
  it('1배속 재생 중 4초마다 한 조각씩 20번 빠져도 착지(첫 조각 + 조각 하나) 뒤 옮기지 않는다', () => {
    const run = simulate({ ...scenario(0, 4, 1, 1, 0), untilMs: 80_000 });
    expect(run.landing).toBe(D);
    expect(run.moves).toEqual([]);
  });

  it.each<[string, Scenario, Array<[number, number]>]>([
    ['착지 정체 없음 · 갱신 지연 0', scenario(0, 4, 0.1, 0.1, 0), []],
    ['정체 0.5초가 갱신 지연(0.1초)을 넘음', scenario(0, 4, 0.1, 0.1, 0.5), [[4.1, 12]]],
    ['정체 1초 ≤ 빠짐 위상 2초', scenario(0, 4, 2, 2, 1), []],
    ['정체 3초가 빠짐 위상 2초를 넘음', scenario(0, 4, 2, 2, 3), [[6, 12]]],
    ['정체 3초 ≤ 갱신 지연 3초 + 위상', scenario(0, 4, 0.1, 3.1, 3), []],
    ['정체 3.5초가 갱신 지연을 넘음', scenario(0, 4, 0.1, 3.1, 3.5), [[7.1, 12]]],
    ['갱신 6초 주기 — 지연이 0 인 갱신에서 옮김', scenario(0, 6, 0.1, 0.1, 0.5), [[12.1, 20]]],
    ['갱신 6초 주기 · 정체 1초', scenario(0, 6, 1, 3, 1), []],
    ['갱신 6초 주기 · 정체 1.5초', scenario(0, 6, 1, 3, 1.5), [[9, 16]]],
    ['낡은 목록 착지 · 정체 0.5초 ≤ 첫 갱신 1초', scenario(1, 4, 2, 1, 0.5), []],
    ['낡은 목록 착지 · 정체 1.5초 > 첫 갱신 1초', scenario(1, 4, 2, 1, 1.5), [[5, 8]]],
    [
      '낡은 목록 착지 · 시크가 갱신 직전이면 정체가 작아도 옮김',
      scenario(1, 4, 3.9, 0.1, 0.5),
      [[4.1, 8]],
    ],
    ['낡은 목록 착지 · 정체 없음', scenario(1, 4, 3.9, 0.1, 0), []],
    ['낡은 목록 착지 · 갱신 6초 주기면 정체 없이도 옮김', scenario(1, 6, 0.1, 1, 0), [[1, 8]]],
    ['옮긴 뒤 정체 3초는 다시 옮기지 않는다', scenario(0, 6, 0.1, 0.1, 0.5, 3), [[12.1, 20]]],
    [
      '옮길 때마다 4.5초 멎으면 12초마다 되풀이한다',
      scenario(0, 6, 0.1, 0.1, 0.5, 4.5),
      [12.1, 24.1, 36.1, 48.1, 60.1, 72.1, 84.1, 96.1, 108.1].map((t, i) => [t, 20 + i * 12]),
    ],
  ])('%s', (_name, sc, moves) => {
    const run = simulate(sc);
    expect(run.landing).toBe(D - sc.n * D);
    expect(run.moves).toEqual(moves);
  });
});
