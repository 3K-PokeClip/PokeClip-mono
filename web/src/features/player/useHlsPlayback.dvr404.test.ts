import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 되감기 조각 404 뒤 (가) 가 세운 새 되감기 재생기의 시작(POK-528 c3 — 계획 3-3 「남는
// 차이」 · 8-3 「추정 여유」 · P21). 세션은 새 목록의 조각 길이를 모르므로 시작 자리를
// S = F + 지금 목록의 여유로 추정해 넘긴다. 새 재생기는 첫 LEVEL_LOADED 에서 새 목록
// 첫 조각 + 맨 앞 조각 셋 길이 이상으로 자른다(startLoadPosition). 추정이 맞을 때 ·
// 새 여유가 더 클 때 · 조회 뒤 하한이 더 밀렸을 때의 실제 받기 자리와 재생기 알림 수를
// 실행해 보고 고정한다. 재생기 코드는 c3 에서 바뀌지 않는다 — 지금 동작 고정 시험이다.
// hls.js 흉내는 useHlsPlayback.start.test.ts 꼴이다(처음부터 off() 를 둔다).

interface Details {
  fragments: Array<{ start: number; duration: number; programDateTime: number }>;
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static isSupported = () => true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
  };
  readonly handlers = new Map<string, Handler[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = null;
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  readonly destroy = vi.fn();
  constructor(readonly config: { autoStartLoad?: boolean }) {
    fake.instances.push(this);
  }
  on(event: string, handler: Handler) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  off() {}
  once(event: string, handler: Handler) {
    const wrapped: Handler = (e, data) => {
      this.handlers.set(
        event,
        (this.handlers.get(event) ?? []).filter((h) => h !== wrapped),
      );
      handler(e, data);
    };
    this.on(event, wrapped);
  }
  trigger(event: string, data: unknown) {
    const { details } = (data ?? {}) as { details?: Details };
    if (details !== undefined) this.latestLevelDetails = details;
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  attachMedia() {}
  loadSource() {}
}

/** 제어면 답의 첫 조각 F */
const F = Date.parse('2026-10-25T05:00:00.000Z');
/** 세션이 아는 지금 목록의 여유(맨 앞 4초 조각 셋) */
const SESSION_MARGIN_MS = 12_000;
/** 세션이 넘긴 시작 자리 S = F + 지금 목록의 여유 */
const S = F + SESSION_MARGIN_MS;

/** 새 목록 — 첫 조각 firstMs(영상 0초) · segmentS 초 조각 n 개 · 미닫이 live 목록 */
function details(firstMs: number, segmentS: number, n = 10): Details {
  const fragments = [...Array(n).keys()].map((i) => ({
    start: i * segmentS,
    duration: segmentS,
    programDateTime: firstMs + i * segmentS * 1_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: segmentS, live: true };
}

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** (가) 가 세운 새 되감기 재생기를 S 에서 세우고 첫 목록을 hls.js 순서대로 쏜다 */
async function bootAtS(list: Details, hooks: PlaybackHooks) {
  const source: PlaybackSource = {
    kind: 'rewind',
    url: 'https://m/dvr/k/a1/index.m3u8',
    startAtMs: S,
    generation: 2,
    strictStart: false,
  };
  const ref = { current: document.createElement('video') };
  renderHook(() => useHlsPlayback(ref, source, {}, undefined, undefined, false, undefined, hooks));
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  const hls = lastHls();
  hls.trigger(FakeHls.Events.MANIFEST_PARSED, {});
  hls.trigger(FakeHls.Events.LEVEL_LOADED, { details: list });
  hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: list });
  await Promise.resolve();
  return hls;
}

beforeEach(() => {
  vi.useFakeTimers({ now: F + 60_000 });
  fake.instances.length = 0;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(() => 0);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('(가) 의 시작 하한 추정 S — 새 재생기가 실제로 받는 자리(P21 · 지금 동작 고정)', () => {
  it('추정 = 새 여유면 S 의 영상 시각에서 받는다 — 더 자르지 않음 · 재생기 알림 0', async () => {
    const onNotice = vi.fn();
    const hls = await bootAtS(details(F, 4), { onNotice });
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('새 여유가 더 크면(6초 조각) F + 새 여유로 말없이 더 자른다 — 재생기 알림 0(한계)', async () => {
    const onNotice = vi.fn();
    const hls = await bootAtS(details(F, 6), { onNotice });
    expect(hls.startLoad.mock.calls).toEqual([[18]]);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('조회 뒤 하한이 한 조각 더 밀리면(F′ = F + 4초) F′ + 여유로 말없이 더 자른다 — 재생기 알림 0(한계)', async () => {
    const onNotice = vi.fn();
    const shifted = F + 4_000;
    const hls = await bootAtS(details(shifted, 4), { onNotice });
    // 영상 12초 = F′ + 12초 = S + 4초
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(shifted + 12_000 - S).toBe(4_000);
    expect(onNotice).not.toHaveBeenCalled();
  });
});
