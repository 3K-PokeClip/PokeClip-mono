import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { subscribeApiActivity } from '@/api/client';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient, makeQueryWrapper, withToastProvider } from '@/test/testProviders';
import { useVodListMockState, vodListQueryOptions } from './useVodListMockState';
import { FRESH_ROW_VOD } from './vodRoundBadge';

// 지난 방송 목록 훅 — 방송마다 카드 수와 다시보기 표지(POK-568: 회차 경로를 물어 끝난 뒤 들어온 화면과 같은 규칙으로 정한다).

const HOUR = 3_600_000;
const MINE_START = Date.parse('2026-09-29T10:00:00Z');
const CONTROL = 'https://ctl.test';
const ROUND_URL_RE = /^https:\/\/ctl\.test\/api\/streams\/([^/]+)\/rounds\/([^/]+)\/rewind$/;

const api = vi.hoisted(() => ({
  /** 목록 끝에 더할 방송 줄 */
  extra: [] as Record<string, unknown>[],
  /** 있으면 기본 두 줄 대신 이 줄들만 목록으로 준다 */
  only: null as Record<string, unknown>[] | null,
  /** 목록·카드 읽기에 넘긴 끊기 신호 */
  signals: [] as (AbortSignal | undefined)[],
  /** 카드 읽기 횟수 · 동시에 도는 수 · 그 최댓값 */
  cardReads: 0,
  cardsRunning: 0,
  cardsPeak: 0,
  /** 카드 읽기가 답하지 않는다: 읽는 도중에 내려가는 시험 */
  holdCards: false,
}));
vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  fetchAllJumpCards: async (_streamId: string, signal?: AbortSignal) => {
    api.signals.push(signal);
    api.cardReads += 1;
    api.cardsRunning += 1;
    api.cardsPeak = Math.max(api.cardsPeak, api.cardsRunning);
    if (api.holdCards) await new Promise(() => undefined);
    await Promise.resolve();
    api.cardsRunning -= 1;
    return [];
  },
  fetchAllBroadcasts: (_state: string, _streamerId: number | null, signal?: AbortSignal) => {
    api.signals.push(signal);
    return Promise.resolve(
      api.only ?? [
        {
          streamId: 'S-mine',
          ingestStreamId: 'key-shared',
          status: 'ended',
          relation: 'OWNER',
          startedAt: new Date(MINE_START).toISOString(),
          endedAt: new Date(MINE_START + HOUR).toISOString(),
          vodExpiresAt: null,
          title: '새벽 랭크',
        },
        {
          streamId: 'S-other',
          ingestStreamId: 'key-shared',
          status: 'ended',
          relation: 'OWNER',
          startedAt: new Date(MINE_START - 24 * HOUR).toISOString(),
          endedAt: new Date(MINE_START - 23 * HOUR).toISOString(),
          vodExpiresAt: null,
        },
        ...api.extra,
      ],
    );
  },
}));

afterEach(() => {
  api.extra = [];
  api.only = null;
  api.signals = [];
  api.cardReads = 0;
  api.cardsRunning = 0;
  api.cardsPeak = 0;
  api.holdCards = false;
});

describe('useVodListMockState — 내려갈 때', () => {
  it('읽던 목록·카드를 끊는다: 채널을 바꿔 다시 그려지면 옛 채널 요청을 이어 가지 않는다(PR #342 2차 리뷰)', async () => {
    api.holdCards = true;
    const { unmount } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() => expect(api.cardReads).toBeGreaterThan(0));
    expect(api.signals.every((s) => s !== undefined && !s.aborted)).toBe(true);

    unmount();

    expect(api.signals.every((s) => s?.aborted)).toBe(true);
  });
});

describe('useVodListMockState — 방송마다 카드 수', () => {
  const rowsBeyond = (count: number) =>
    Array.from({ length: count }, (_, i) => ({
      streamId: `S-${i}`,
      status: 'ended',
      relation: 'OWNER',
      startedAt: new Date(MINE_START - (i + 2) * 24 * HOUR).toISOString(),
      endedAt: new Date(MINE_START - (i + 2) * 24 * HOUR + HOUR).toISOString(),
      vodExpiresAt: null,
    }));

  it('방송이 많아도 동시에 6개까지만 묻는다: 처음 들어올 때 전체 이력이 한꺼번에 몰리지 않게', async () => {
    api.extra = rowsBeyond(30);
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });

    await waitFor(() => expect(result.current.totalCount).toBe(32));

    expect(api.cardReads).toBe(32);
    expect(api.cardsPeak).toBe(6);
  });

  it('화면을 떠났다 돌아오면 받아 둔 목록을 바로 그리고 카드를 다시 세지 않는다', async () => {
    const client = createTestQueryClient();
    const wrapper = makeQueryWrapper(client);
    const first = renderHook(() => useVodListMockState(), { wrapper });
    await waitFor(() => expect(first.result.current.totalCount).toBe(2));
    const counted = api.cardReads;
    first.unmount();

    const second = renderHook(() => useVodListMockState(), { wrapper });

    expect(second.result.current.loading).toBe(false);
    expect(second.result.current.totalCount).toBe(2);
    await waitFor(() => expect(client.isFetching()).toBe(0));
    expect(api.cardReads).toBe(counted);
  });

  it('행 제목은 치지직 제목이고, 제목이 없는 방송은 번호다(POK-259)', async () => {
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() => expect(result.current.visuals['S-mine']?.title).toBe('새벽 랭크'));
    expect(result.current.visuals['S-other']?.title).toBe('S-other');
  });
});

// ── 회차 경로 표지(POK-568) ──

type RoundMode = 'ending' | 'ended' | 'vod' | 404 | 503;

/** 끝난 지 agoMs 지난 방송 줄. 회차 id = streamId · 영상 키 = `key-<streamId>` */
function endedRow(streamId: string, agoMs: number, extra: Record<string, unknown> = {}) {
  const endedAt = new Date(Date.now() - agoMs).toISOString();
  return {
    streamId,
    ingestStreamId: `key-${streamId}`,
    status: 'ended',
    relation: 'OWNER',
    startedAt: endedAt,
    endedAt,
    vodExpiresAt: null,
    ...extra,
  };
}

/** 회차 경로 답(계약3 7-2절 본문 꼴). 404 는 「아직 모름」, 503 은 「잠시 불가」 */
function roundAnswer(mode: RoundMode): Response {
  if (mode === 404 || mode === 503) return jsonResponse(mode, { error: 'x' });
  const ready = mode === 'vod';
  return jsonResponse(200, {
    ready,
    mode,
    url: ready ? `${CONTROL}/vod/x/index.m3u8` : null,
    firstPdt: ready ? '2026-09-29T10:00:00.000Z' : null,
    headPdt: ready ? '2026-09-29T11:00:00.000Z' : null,
  });
}

/**
 * 제어면 흉내. 회차 경로마다 answer 로 답하고, 물은 회차 · 받은 신호 · 동시에 도는 수를 센다. hold 가 참인 회차는
 * 답하지 않다가 끊기면 진짜 fetch 처럼 AbortError 로 끝난다
 */
function stubControl(
  answer: (roundId: string) => RoundMode,
  hold: (roundId: string) => boolean = () => false,
) {
  const seen = {
    asked: [] as string[],
    signals: [] as (AbortSignal | undefined)[],
    running: 0,
    peak: 0,
  };
  const spy = stubFetch(async (url, init) => {
    const match = ROUND_URL_RE.exec(url);
    if (match === null) return jsonResponse(500, {});
    const roundId = match[2]!;
    seen.asked.push(roundId);
    seen.signals.push(init?.signal ?? undefined);
    seen.running += 1;
    seen.peak = Math.max(seen.peak, seen.running);
    try {
      if (hold(roundId)) {
        return await new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        });
      }
      await Promise.resolve();
      return roundAnswer(answer(roundId));
    } finally {
      seen.running -= 1;
    }
  });
  return { seen, spy };
}

/** 줄마다 정한 답, 정하지 않은 줄은 ending(준비 중) */
function modeByRow(modes: Record<string, RoundMode>): (roundId: string) => RoundMode {
  return (roundId) => modes[roundId] ?? 'ending';
}

/** 가짜 시계를 ms 만큼 돌리고 그 사이 뜬 일을 다 끝낸다 */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

function rowOf(result: { current: ReturnType<typeof useVodListMockState> }, streamId: string) {
  return {
    status: result.current.broadcasts.find((b) => b.streamId === streamId)?.status,
    vodMissing: result.current.visuals[streamId]?.vodMissing === true,
  };
}

describe('useVodListMockState — 회차 경로 표지(POK-568)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', CONTROL);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('회차 답으로 표지를 정한다: vod 면 「다시 보기 가능」, ended 면 다시보기 없음, ending 이면 준비 중', async () => {
    api.only = [
      endedRow('R', 3 * 24 * HOUR),
      endedRow('N', 3 * 24 * HOUR),
      endedRow('P', 3 * 24 * HOUR),
    ];
    stubControl(modeByRow({ R: 'vod', N: 'ended' }));
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);

    expect(rowOf(result, 'R')).toEqual({ status: 'vod_ready', vodMissing: false });
    expect(rowOf(result, 'N')).toEqual({ status: 'ended', vodMissing: true });
    expect(rowOf(result, 'P')).toEqual({ status: 'ended', vodMissing: false });
  });

  it('ready · none 으로 굳은 줄과 보관 만료 줄은 다시 묻지 않는다: 준비 중인 줄만 다음 주기에 다시 묻는다', async () => {
    const old = 3 * 24 * HOUR;
    api.only = [
      endedRow('R', old),
      endedRow('N', old),
      endedRow('P', old),
      endedRow('E', old, { vodExpiresAt: new Date(Date.now() - HOUR).toISOString() }),
    ];
    const { seen } = stubControl(modeByRow({ R: 'vod', N: 'ended' }));
    renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);
    expect([...seen.asked].sort()).toEqual(['N', 'P', 'R']);

    await advance(60_000);

    expect(seen.asked.slice(3)).toEqual(['P']);
  });

  it('오래된 미결 줄은 한 주기 20개까지 오래전에 물은 것부터 묻고, 방금 끝난 줄은 상한 밖이다(X2)', async () => {
    const olds = Array.from({ length: 25 }, (_, i) => endedRow(`old-${i}`, 3 * 24 * HOUR));
    const fresh = ['fresh-0', 'fresh-1', 'fresh-2'].map((id) => endedRow(id, 10 * 60_000));
    api.only = [...olds, ...fresh];
    const { seen } = stubControl(() => 'ending');
    renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);
    expect(seen.asked).toHaveLength(28);

    await advance(60_000);
    const second = seen.asked.slice(28);
    expect(second).toHaveLength(23);
    expect(second).toEqual(expect.arrayContaining(['fresh-0', 'fresh-1', 'fresh-2']));
    const skipped = olds.map((b) => b.streamId).filter((id) => !second.includes(id));
    expect(skipped).toHaveLength(5);

    await advance(60_000);
    // 지난 주기에 차례가 안 온 다섯이 이번엔 맨 먼저다: 고정 순서면 뒤쪽 줄은 영영 안 물어진다
    expect(seen.asked.slice(51)).toEqual(expect.arrayContaining(skipped));
  });

  it('한 번 묻고 답을 못 받은 줄은 상한 안이다: 늘 답하지 못하는 줄이 처음 보는 줄처럼 매 주기 다 물어지지 않는다(K2 (나))', async () => {
    api.only = Array.from({ length: 25 }, (_, i) => endedRow(`S-${i}`, 3 * 24 * HOUR));
    const { seen } = stubControl(() => 503);
    renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);
    expect(seen.asked).toHaveLength(25);

    await advance(60_000);

    expect(seen.asked.slice(25)).toHaveLength(20);
  });

  it('회차 물음도 동시에 6개까지다', async () => {
    api.only = Array.from({ length: 30 }, (_, i) => endedRow(`S-${i}`, 3 * 24 * HOUR));
    const { seen } = stubControl(() => 'ending');
    renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);

    expect(seen.asked).toHaveLength(30);
    expect(seen.peak).toBe(6);
  });

  it('8초 안에 답이 없으면 끊고 「잠시 불가」 로 접는다: 표지는 그대로이고 목록은 다른 줄의 답과 함께 선다', async () => {
    const old = 3 * 24 * HOUR;
    api.only = [endedRow('slow', old), endedRow('done', old)];
    const { seen } = stubControl(
      () => 'ended',
      (id) => id === 'slow',
    );
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(7_900);
    expect(result.current.loading).toBe(true);

    await advance(200);

    expect(result.current.loading).toBe(false);
    expect(result.current.failed).toBe(false);
    expect(seen.signals[seen.asked.indexOf('slow')]?.aborted).toBe(true);
    expect(rowOf(result, 'slow')).toEqual({ status: 'ended', vodMissing: false });
    expect(rowOf(result, 'done')).toEqual({ status: 'ended', vodMissing: true });
  });

  it('쿼리가 끊기면 회차 요청도 끊고 기억은 그대로 둔다: 내려간 화면의 물음을 이어 가지 않는다', async () => {
    const client = createTestQueryClient();
    let hang = false;
    api.only = [endedRow('P', 3 * 24 * HOUR)];
    const { seen } = stubControl(
      () => 'ending',
      () => hang,
    );
    const { unmount } = renderHook(() => useVodListMockState(), {
      wrapper: makeQueryWrapper(client),
    });
    await advance(100);
    const key = vodListQueryOptions(null).queryKey;
    const askedAt = client.getQueryData(key)?.memory.P?.vod.askedAt;
    expect(askedAt).toEqual(expect.any(Number));

    hang = true;
    await advance(60_000);
    expect(seen.asked).toEqual(['P', 'P']);
    unmount();

    expect(seen.signals[1]?.aborted).toBe(true);
    expect(client.getQueryData(key)?.memory.P?.vod.askedAt).toBe(askedAt);
  });

  it('제어면 주소가 없으면 회차를 묻지 않는다: 끝난 방송은 「VOD 준비 중」 이다', async () => {
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', '');
    api.only = [endedRow('P', 3 * 24 * HOUR)];
    const { spy } = stubControl(() => 'ended');
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);

    expect(result.current.totalCount).toBe(1);
    expect(spy).not.toHaveBeenCalled();
    expect(rowOf(result, 'P')).toEqual({ status: 'ended', vodMissing: false });
  });

  it('회차 물음은 요청 활동으로 알린다: 채널 전환 화면이 그 답(시한으로 끝난 것까지)을 기다린다', async () => {
    const events: string[] = [];
    const stop = subscribeApiActivity(({ type }) => events.push(type));
    api.only = [endedRow('A', 3 * 24 * HOUR), endedRow('B', 3 * 24 * HOUR)];
    stubControl(
      () => 'ending',
      () => true,
    );
    try {
      renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
      await advance(100);
      expect(events.filter((t) => t === 'start')).toHaveLength(2);
      expect(events.filter((t) => t === 'end')).toHaveLength(0);

      await advance(8_000);
      expect(events.filter((t) => t === 'end')).toHaveLength(2);
    } finally {
      stop();
    }
  });

  it('404 유예의 원점은 명부 endedAt 이다: 오래전에 끝난 줄의 404 는 곧바로 다시보기 없음이고, 끝난 시각을 모르면 아직 준비 중이다', async () => {
    api.only = [endedRow('old', 3 * 24 * HOUR), endedRow('unknown', 0, { endedAt: null })];
    stubControl(() => 404);
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);

    expect(rowOf(result, 'old')).toEqual({ status: 'ended', vodMissing: true });
    expect(rowOf(result, 'unknown')).toEqual({ status: 'ended', vodMissing: false });
  });

  it('첫 적재는 처음 보는 줄을 상한 없이 다 묻고, 다음 주기는 굳은 줄을 빼고 오래된 미결 줄만 20개까지 묻는다(X2)', async () => {
    api.only = Array.from({ length: 60 }, (_, i) => endedRow(`S-${i}`, 3 * 24 * HOUR));
    const { seen } = stubControl((id) => (Number(id.slice(2)) % 2 === 0 ? 'ended' : 'ending'));
    renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await advance(100);
    expect(seen.asked).toHaveLength(60);

    await advance(60_000);

    const second = seen.asked.slice(60);
    expect(second).toHaveLength(20);
    expect(second.every((id) => Number(id.slice(2)) % 2 === 1)).toBe(true);
  });

  it('카드 수만 센 채 회차를 못 물은 기억(옛 꼴 · 끊긴 주기)도 처음 보는 줄이다: 상한 없이 다 묻는다(K7)', async () => {
    const client = createTestQueryClient();
    const rows = Array.from({ length: 25 }, (_, i) => endedRow(`S-${i}`, 3 * 24 * HOUR));
    const counted = { cardCount: 0, countedAt: Date.now() };
    const memory = Object.fromEntries(
      rows.map((b, i) => [b.streamId, i < 12 ? counted : { ...counted, vod: FRESH_ROW_VOD }]),
    );
    client.setQueryData(
      vodListQueryOptions(null).queryKey,
      { rows: [], visuals: {}, memory } as never,
      { updatedAt: Date.now() - 60_000 },
    );
    api.only = rows;
    const { seen } = stubControl(() => 'ending');
    renderHook(() => useVodListMockState(), { wrapper: makeQueryWrapper(client) });
    await advance(100);

    expect(seen.asked).toHaveLength(25);
  });
});
