import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BroadcastRow } from '@/api/clipEditor';
import type { XAnswer } from '@/features/player/rewindSwitch';
import { REWIND_POLL_MS } from '@/features/player/useRewindStatus';
import { jsonResponse } from '@/test/mockFetch';

// 편집기 원천 폴링 · 목록 받기(POK-473 계획 prw2 5절 E4b-1 · 설계 5절 1–2 · 14.2 D3). 판정 순서는 editorSourceState(E4a)가
// 맡으므로 여기서는 배선을 본다 — 어느 경로를 언제 묻는가 · 목록을 어느 주소에서 받는가 · 조각 실패 사건에 무엇을 싣는가 ·
// base 가 언제 정해지는가 · 한 번 읽기가 같은 판정을 내는가. 출입증 모듈은 부르기만 하므로 가짜다.
vi.mock('@/api/playbackAccess', () => ({
  ensurePlaybackAccess: vi.fn(() => null),
  isPlaybackExpired: vi.fn(() => false),
  ensureMediaAccess: vi.fn(() => null),
  reportPlaybackForbidden: vi.fn(),
}));
vi.mock('@/api/editors', () => ({ fetchDelegationsAsEditor: vi.fn() }));
vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  fetchMeLoose: vi.fn(),
  isBroadcastInChannel: vi.fn(),
}));
vi.mock('@/features/broadcast/streamSelection', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/broadcast/streamSelection')>()),
  resolveLiveStreamId: vi.fn(),
}));

const access = await import('@/api/playbackAccess');
const { isBroadcastInChannel } = await import('@/api/clipEditor');
const { fetchDelegationsAsEditor } = await import('@/api/editors');
const { resolveLiveStreamId } = await import('@/features/broadcast/streamSelection');
const { editorXResolver, streamerIdOf } = await import('./broadcastStreamer');
const { readEditorSourceOnce, useEditorSource } = await import('./useEditorSource');

const BASE = 'https://media.example';
const ROUND = `${BASE}/api/streams/key-1/rounds/round-a/rewind`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const LIVE_LIST = `${BASE}/dvr/key-1/index.m3u8`;
const VOD_LIST = `${BASE}/vod/key-1/round-a/index.m3u8`;
const OLD_LIST = `${BASE}/dvr/key-1/old/index.m3u8`;
const STARTED_MS = Date.parse('2026-11-12T01:00:00.000Z');
const PDT0 = STARTED_MS + 7_300;

const BROADCAST: BroadcastRow = {
  streamId: 'round-a',
  status: 'live',
  relation: 'OWNER',
  startedAt: new Date(STARTED_MS).toISOString(),
  endedAt: null,
  vodExpiresAt: null,
  ingestStreamId: 'key-1',
};
const ENDED: BroadcastRow = { ...BROADCAST, status: 'ended', endedAt: '2026-11-12T03:00:00.000Z' };

function rewind(mode: string, url: string | null) {
  const ready = url !== null;
  const pdt = (ms: number) => (ready ? new Date(ms).toISOString() : null);
  return { ready, mode, url, firstPdt: pdt(PDT0), headPdt: pdt(PDT0 + 12_000) };
}

/** 목록 글 — seq 마다 4초 조각(첫 PDT = firstPdtMs) */
function playlist(listUrl: string, seqs: number[], firstPdtMs = PDT0): string {
  const dir = listUrl.replace(/\/index\.m3u8$/, '');
  const lines = ['#EXTM3U', '#EXT-X-VERSION:6', `#EXT-X-MAP:URI="${dir}/init/i.mp4"`];
  seqs.forEach((seq, i) => {
    lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(firstPdtMs + i * 4_000).toISOString()}`);
    lines.push('#EXTINF:4.000,', `${dir}/seg/${String(seq).padStart(7, '0')}.m4s`);
  });
  return `${lines.join('\n')}\n`;
}

type Answer = { status: number; body?: unknown } | (() => never);

/** 경로마다 지금 줄 답 — 시험이 바꿔 가며 국면을 옮긴다 */
const server: { round: Answer; stream: Answer; lists: Map<string, Answer> } = {
  round: { status: 200, body: rewind('live', null) },
  stream: { status: 200, body: rewind('live', LIVE_LIST) },
  lists: new Map(),
};

function answerOf(answer: Answer | undefined): Response {
  if (answer === undefined) return new Response(null, { status: 404 });
  if (typeof answer === 'function') return answer();
  if (typeof answer.body === 'string') return new Response(answer.body, { status: answer.status });
  return jsonResponse(answer.status, answer.body);
}

const fetchSpy = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
  const url = String(input);
  if (url === ROUND) return answerOf(server.round);
  if (url === STREAM) return answerOf(server.stream);
  return answerOf(server.lists.get(url));
});

function listCalls(): string[] {
  return fetchSpy.mock.calls.map(([url]) => String(url)).filter((url) => url.endsWith('.m3u8'));
}

function callsTo(url: string): number {
  return fetchSpy.mock.calls.filter(([u]) => String(u) === url).length;
}

/** 그 주소로 마지막에 건 요청의 끊기 신호 */
function lastSignalTo(url: string): AbortSignal | undefined {
  return fetchSpy.mock.calls.filter(([u]) => String(u) === url).at(-1)?.[1]?.signal ?? undefined;
}

/** 시험이 답을 내줄 때까지 걸려 있는 목록 답 */
function heldAnswer() {
  let release: (res: Response) => void = () => {};
  const pending = new Promise<Response>((resolve) => {
    release = resolve;
  });
  return { answer: (() => pending) as unknown as Answer, release };
}

async function settle(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
    for (let i = 0; i < 20; i++) await vi.advanceTimersByTimeAsync(0);
  });
}

const KNOWN_A: XAnswer = { kind: 'known', id: 'round-a' };

function mount(broadcast = BROADCAST, resolveX = vi.fn(async (): Promise<XAnswer> => KNOWN_A)) {
  return renderHook(() => useEditorSource(broadcast, resolveX));
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', BASE);
  vi.stubGlobal('fetch', fetchSpy);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  server.round = { status: 200, body: rewind('live', null) };
  server.stream = { status: 200, body: rewind('live', LIVE_LIST) };
  server.lists = new Map([
    [LIVE_LIST, { status: 200, body: playlist(LIVE_LIST, [300, 301, 302]) }],
    [VOD_LIST, { status: 200, body: playlist(VOD_LIST, [300, 301, 302, 303]) }],
  ]);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('useEditorSource: 회차 · 스트림 경로를 폴링하고 원천 주소에서만 목록을 받는다', () => {
  it('방송 중이면 스트림 경로 url 이 원천이 되고 목록은 그 주소에서만 받는다', async () => {
    const { result } = mount();
    await settle();

    expect(result.current.state).toEqual({ kind: 'ready', mode: 'live', url: LIVE_LIST });
    expect(listCalls()).toEqual([LIVE_LIST]);
    expect(result.current.list?.url).toBe(LIVE_LIST);
    expect(result.current.list?.mode).toBe('live');
    expect(result.current.list?.playlist.rows.map((r) => r.seq)).toEqual([300, 301, 302]);
  });

  it('스트림 답은 10초마다 다시 묻고, 지금 방송 번호를 모르면 그 답을 버린다', async () => {
    const resolveX = vi.fn(async (): Promise<XAnswer> => ({ kind: 'unknown' }));
    const { result } = mount(BROADCAST, resolveX);
    await settle();
    await settle(REWIND_POLL_MS);

    expect(callsTo(STREAM)).toBe(2);
    expect(resolveX).toHaveBeenCalledTimes(2);
    expect(result.current.state).toEqual({ kind: 'checking' });
    expect(listCalls()).toEqual([]);
  });

  it('스트림 경로가 다른 방송(R58)이면 finalizing 이고 스트림 경로를 멈춘다', async () => {
    const resolveX = vi.fn(async (): Promise<XAnswer> => ({ kind: 'known', id: 'round-b' }));
    const { result } = mount(BROADCAST, resolveX);
    await settle();
    const asked = callsTo(STREAM);
    await settle(REWIND_POLL_MS * 2);

    expect(result.current.state.kind).toBe('finalizing');
    expect(callsTo(STREAM)).toBe(asked);
    expect(callsTo(ROUND)).toBeGreaterThan(1);
  });

  it('끝난 방송이 vod 로 서면 회차 경로 url 에서 목록을 받고 스트림 경로는 묻지 않는다', async () => {
    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    const { result } = mount(ENDED);
    await settle();

    expect(result.current.state).toEqual({ kind: 'ready', mode: 'vod', url: VOD_LIST });
    expect(listCalls()).toEqual([VOD_LIST]);
    expect(callsTo(STREAM)).toBe(0);
  });

  it('목록이 403 이면 출입증을 버리고 다시 받은 뒤 한 번 더 받는다', async () => {
    let first = true;
    server.lists.set(LIVE_LIST, () => {
      const res = first
        ? new Response(null, { status: 403 })
        : new Response(playlist(LIVE_LIST, [300]), { status: 200 });
      first = false;
      return res as never;
    });
    const { result } = mount();
    await settle();

    expect(access.reportPlaybackForbidden).toHaveBeenCalledWith('key-1');
    expect(listCalls()).toEqual([LIVE_LIST, LIVE_LIST]);
    expect(result.current.list?.playlist.rows).toHaveLength(1);
  });

  it('출입증이 만료(410)면 expired 이고 목록을 받지 않는다', async () => {
    vi.mocked(access.ensurePlaybackAccess).mockResolvedValue({ kind: 'expired' });
    const { result } = mount();
    await settle();

    expect(result.current.state).toEqual({ kind: 'expired' });
    expect(listCalls()).toEqual([]);
  });

  it('제어면 주소가 없으면 묻지 않고 unavailable 이다', async () => {
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', '');
    const { result } = mount(ENDED);
    await settle();

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result.current.state).toEqual({ kind: 'unavailable' });
    expect(result.current.base).toBe(STARTED_MS);
  });
});

describe('useEditorSource: base 확정 규칙(timelineOriginAt → 끝난 방송 vod 첫 PDT → startedAt)', () => {
  it('서버 기준점이 있으면 답 전부터 그 값이다', async () => {
    const origin = '2026-11-12T01:00:02.500Z';
    const { result } = mount({ ...ENDED, timelineOriginAt: origin });

    expect(result.current.base).toBe(Date.parse(origin));
    await settle();
  });

  it('방송 중이면 답 전부터 startedAt 이다', async () => {
    const { result } = mount();

    expect(result.current.base).toBe(STARTED_MS);
    await settle();
  });

  it('끝난 방송은 vod 목록이 오기 전에는 정하지 않고, 오면 그 첫 PDT 로 정한다', async () => {
    server.round = { status: 200, body: rewind('ending', null) };
    const { result } = mount(ENDED);
    await settle();
    expect(result.current.base).toBeNull();

    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    await settle(REWIND_POLL_MS);

    expect(result.current.base).toBe(PDT0);
  });

  it('끝난 방송이 끝내 vod 없이 끝나면(ended) startedAt 으로 정한다', async () => {
    server.round = { status: 200, body: rewind('ended', null) };
    const { result } = mount(ENDED);
    await settle();

    expect(result.current.state).toEqual({ kind: 'goneOrNone' });
    expect(result.current.base).toBe(STARTED_MS);
  });

  it('한 번 정한 base 는 세션 동안 바뀌지 않는다(뒤에 온 방송 줄에 기준점이 실려도)', async () => {
    const resolveX = vi.fn(async (): Promise<XAnswer> => KNOWN_A);
    const { result, rerender } = renderHook(({ row }) => useEditorSource(row, resolveX), {
      initialProps: { row: BROADCAST },
    });
    await settle();

    rerender({ row: { ...BROADCAST, timelineOriginAt: '2026-11-12T01:00:02.500Z' } });

    expect(result.current.base).toBe(STARTED_MS);
  });
});

describe('useEditorSource: R58 입력 — 이 방송의 채널 번호로만 묻는다(A3 반례)', () => {
  const A = 10;
  const B = 20;

  beforeEach(() => {
    vi.mocked(fetchDelegationsAsEditor).mockResolvedValue([
      { id: 1, streamerId: B, streamerName: 'b', streamerProfileImageUrl: null, grantedAt: '' },
      { id: 2, streamerId: A, streamerName: 'a', streamerProfileImageUrl: null, grantedAt: '' },
    ]);
    vi.mocked(isBroadcastInChannel).mockImplementation(async (_id, streamerId) => streamerId === A);
    // 채널 구분 없이(null) 물으면 live 첫 줄 = 다른 채널 B 의 방송
    vi.mocked(resolveLiveStreamId).mockImplementation(async (streamerId) =>
      streamerId === A ? 'round-a' : 'round-b',
    );
  });

  it('위임 A · B 중 B 가 live 첫 줄이어도 A 방송 편집기는 A 번호로 물어 같은 방송이라 url 을 쓴다', async () => {
    const editor = { ...BROADCAST, relation: 'EDITOR' };
    const resolveX = editorXResolver(streamerIdOf(editor), 'key-1');
    const { result } = renderHook(() => useEditorSource(editor, resolveX));
    await settle();

    expect(result.current.state).toEqual({ kind: 'ready', mode: 'live', url: LIVE_LIST });
    expect(vi.mocked(resolveLiveStreamId).mock.calls.every(([id]) => id === A)).toBe(true);
  });

  it('채널 번호를 못 정하면 늘 모름이라 스트림 답을 버린다(섞어 묻지 않는다)', async () => {
    vi.mocked(fetchDelegationsAsEditor).mockResolvedValue([]);
    const editor = { ...BROADCAST, relation: 'EDITOR' };
    const resolveX = editorXResolver(streamerIdOf(editor), 'key-1');
    const { result } = renderHook(() => useEditorSource(editor, resolveX));
    await settle();

    expect(result.current.state).toEqual({ kind: 'checking' });
    expect(resolveLiveStreamId).not.toHaveBeenCalled();
  });
});

describe('useEditorSource: 조각 실패 사건(D3 6–9행)', () => {
  it('404 조각이 다시 받은 목록에서 빠졌고 방송 중이면 outsideDvr — 사건은 그 창의 목록 주소를 싣는다', async () => {
    const { result } = mount();
    await settle();
    server.lists.set(LIVE_LIST, { status: 200, body: playlist(LIVE_LIST, [301, 302, 303]) });

    await act(() => result.current.reportSegmentFailure('gone', 300, LIVE_LIST));

    expect(result.current.state).toEqual({
      kind: 'outsideDvr',
      segment: { mode: 'live', listUrl: LIVE_LIST },
    });
    expect(listCalls()).toEqual([LIVE_LIST, LIVE_LIST]);
    expect(result.current.list?.playlist.rows.map((r) => r.seq)).toEqual([301, 302, 303]);
  });

  it('404 조각이 아직 목록에 있으면 goneOrNone 이다', async () => {
    const { result } = mount();
    await settle();

    await act(() => result.current.reportSegmentFailure('gone', 301, LIVE_LIST));

    expect(result.current.state).toEqual({
      kind: 'goneOrNone',
      segment: { mode: 'live', listUrl: LIVE_LIST },
    });
  });

  it('지금 원천이 아닌 목록(낡은 창)의 실패는 목록을 받지 않고 버린다', async () => {
    const { result } = mount();
    await settle();

    await act(() => result.current.reportSegmentFailure('gone', 300, OLD_LIST));

    expect(result.current.state).toEqual({ kind: 'ready', mode: 'live', url: LIVE_LIST });
    expect(listCalls()).toEqual([LIVE_LIST]);
  });

  it('vod 원천의 404 는 목록에서 빠졌어도 방송 중이 아니라 goneOrNone 이고 목록을 다시 받지 않는다', async () => {
    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    const { result } = mount(ENDED);
    await settle();

    await act(() => result.current.reportSegmentFailure('gone', 999, VOD_LIST));

    expect(result.current.state).toEqual({
      kind: 'goneOrNone',
      segment: { mode: 'vod', listUrl: VOD_LIST },
    });
    expect(listCalls()).toEqual([VOD_LIST]);
  });

  it('갱신 뒤에도 403 이면 회차 경로를 한 번 물어 404 면 goneOrNone, 정상이면 access 다', async () => {
    const { result } = mount();
    await settle();
    await act(() => result.current.reportSegmentFailure('forbiddenAfterRefresh', 301, LIVE_LIST));
    expect(result.current.state.kind).toBe('access');

    const second = mount();
    await settle();
    server.round = { status: 404 };
    await act(() =>
      second.result.current.reportSegmentFailure('forbiddenAfterRefresh', 301, LIVE_LIST),
    );

    expect(second.result.current.state.kind).toBe('goneOrNone');
  });

  it('다시 받기가 실패하면 들고 있는 목록으로 판정한다', async () => {
    const { result } = mount();
    await settle();
    server.lists.set(LIVE_LIST, { status: 503 });

    await act(() => result.current.reportSegmentFailure('gone', 299, LIVE_LIST));

    expect(result.current.state.kind).toBe('outsideDvr');
    expect(result.current.list?.playlist.rows.map((r) => r.seq)).toEqual([300, 301, 302]);
  });

  it('끊김(회차 ending) 중 받던 조각의 실패는 덮인 원천에 쌓이고, 같은 회차 재접속 뒤에도 같은 주소면 남고, vod 로 옮기면 풀린다', async () => {
    const { result } = mount();
    await settle();
    server.round = { status: 200, body: rewind('ending', null) };
    await settle(REWIND_POLL_MS);
    expect(result.current.state.kind).toBe('finalizing');

    await act(() => result.current.reportSegmentFailure('gone', 301, LIVE_LIST));
    expect(result.current.state).toMatchObject({
      kind: 'finalizing',
      cause: 'round',
      under: { kind: 'goneOrNone', segment: { mode: 'live', listUrl: LIVE_LIST } },
    });

    server.round = { status: 200, body: rewind('live', null) };
    await settle(REWIND_POLL_MS);
    expect(result.current.state).toEqual({
      kind: 'goneOrNone',
      segment: { mode: 'live', listUrl: LIVE_LIST },
    });

    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    await settle(REWIND_POLL_MS);
    expect(result.current.state).toEqual({ kind: 'ready', mode: 'vod', url: VOD_LIST });
  });
});

describe('useEditorSource: 목록 받기 실패 · 늦은 답(r1 cx H1 · H2)', () => {
  it('vod 첫 목록 받기가 503 이면 실패로 알리고 10초 뒤 다시 받아, 서버가 회복되면 목록과 base 가 선다', async () => {
    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    server.lists.set(VOD_LIST, { status: 503 });
    const { result } = mount(ENDED);
    await settle();
    expect(result.current.state).toEqual({ kind: 'ready', mode: 'vod', url: VOD_LIST });
    expect([result.current.list, result.current.base, result.current.listFailed]).toEqual([
      null,
      null,
      true,
    ]);

    server.lists.set(VOD_LIST, { status: 200, body: playlist(VOD_LIST, [300, 301]) });
    await settle(REWIND_POLL_MS);

    expect(callsTo(VOD_LIST)).toBe(2);
    expect(result.current.list?.url).toBe(VOD_LIST);
    expect(result.current.base).toBe(PDT0);
    expect(result.current.listFailed).toBe(false);
  });

  it('목록을 받기 전에는 실패로 알리지 않는다', async () => {
    const answer = heldAnswer();
    server.lists.set(LIVE_LIST, answer.answer);
    const { result } = mount();
    await settle();

    expect(result.current.list).toBeNull();
    expect(result.current.listFailed).toBe(false);
    answer.release(new Response(playlist(LIVE_LIST, [300]), { status: 200 }));
    await settle();
  });

  it('첫 목록 받기가 실패한 뒤 내려가면 더 받지 않는다', async () => {
    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    server.lists.set(VOD_LIST, { status: 503 });
    const { unmount } = mount(ENDED);
    await settle();

    unmount();
    await settle(REWIND_POLL_MS * 3);

    expect(callsTo(VOD_LIST)).toBe(1);
  });

  it('live 다시 받기가 걸린 사이 vod 로 옮기면, 늦게 온 live 답은 vod 목록을 덮지 않고 null 이다', async () => {
    const { result } = mount();
    await settle();
    const answer = heldAnswer();
    server.lists.set(LIVE_LIST, answer.answer);
    let reloaded: Promise<unknown> = Promise.resolve();
    act(() => {
      reloaded = result.current.reloadList();
    });

    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    await settle(REWIND_POLL_MS);
    expect(result.current.list?.url).toBe(VOD_LIST);
    answer.release(new Response(playlist(LIVE_LIST, [300, 301, 302, 303]), { status: 200 }));
    await settle();

    await expect(reloaded).resolves.toBeNull();
    expect(result.current.state).toEqual({ kind: 'ready', mode: 'vod', url: VOD_LIST });
    expect(result.current.list?.url).toBe(VOD_LIST);
    expect(result.current.list?.mode).toBe('vod');
  });

  it('원천이 바뀌면 이전 원천에 걸린 목록 요청을 끊는다', async () => {
    const { result } = mount();
    await settle();
    const answer = heldAnswer();
    server.lists.set(LIVE_LIST, answer.answer);
    act(() => {
      void result.current.reloadList();
    });
    await settle();
    expect(callsTo(LIVE_LIST)).toBe(2);
    expect(lastSignalTo(LIVE_LIST)?.aborted).toBe(false);

    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    await settle(REWIND_POLL_MS);

    expect(lastSignalTo(LIVE_LIST)?.aborted).toBe(true);
    expect(lastSignalTo(VOD_LIST)?.aborted).toBe(false);
    answer.release(new Response(null, { status: 503 }));
    await settle();
  });

  it('내려가면 걸린 목록 요청을 끊는다', async () => {
    const { result, unmount } = mount();
    await settle();
    server.lists.set(LIVE_LIST, heldAnswer().answer);
    act(() => {
      void result.current.reloadList();
    });
    await settle();
    expect(callsTo(LIVE_LIST)).toBe(2);

    unmount();

    expect(lastSignalTo(LIVE_LIST)?.aborted).toBe(true);
  });

  it('조각 실패의 다시 받기가 걸린 사이 vod 로 옮기면 그 사건을 싣지 않는다', async () => {
    const { result } = mount();
    await settle();
    const answer = heldAnswer();
    server.lists.set(LIVE_LIST, answer.answer);
    let reported: Promise<void> = Promise.resolve();
    act(() => {
      reported = result.current.reportSegmentFailure('gone', 299, LIVE_LIST);
    });

    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    await settle(REWIND_POLL_MS);
    answer.release(new Response(playlist(LIVE_LIST, [301]), { status: 200 }));
    await settle();
    await reported;

    expect(result.current.state).toEqual({ kind: 'ready', mode: 'vod', url: VOD_LIST });
    expect(result.current.list?.url).toBe(VOD_LIST);
  });
});

describe('useEditorSource: reloadList(저장 때 목록 한 번 다시 받기)', () => {
  it('방송 중이면 원천 주소에서 다시 받아 그 목록을 돌려주고 들고 있는 목록도 바꾼다', async () => {
    const { result } = mount();
    await settle();
    server.lists.set(LIVE_LIST, { status: 200, body: playlist(LIVE_LIST, [300, 301, 302, 303]) });

    const got = await act(() => result.current.reloadList());

    expect(got?.rows.map((r) => r.seq)).toEqual([300, 301, 302, 303]);
    expect(result.current.list?.playlist).toBe(got);
  });

  it('다시 받기가 실패하면 null 이고 들고 있는 목록은 그대로다', async () => {
    const { result } = mount();
    await settle();
    server.lists.set(LIVE_LIST, { status: 503 });

    const got = await act(() => result.current.reloadList());

    expect(got).toBeNull();
    expect(result.current.list?.playlist.rows).toHaveLength(3);
  });

  it('끝난 방송(vod)은 목록이 바뀌지 않아 다시 받지 않고 들고 있는 목록을 준다', async () => {
    server.round = { status: 200, body: rewind('vod', VOD_LIST) };
    const { result } = mount(ENDED);
    await settle();

    const got = await act(() => result.current.reloadList());

    expect(got).toBe(result.current.list?.playlist);
    expect(listCalls()).toEqual([VOD_LIST]);
  });
});

describe('readEditorSourceOnce: 미리 받기용 한 번 읽기 — 훅과 같은 판정', () => {
  it('방송 중이면 회차 · 스트림 경로를 한 번씩 묻고 원천 목록과 base 를 준다', async () => {
    const got = await readEditorSourceOnce(BROADCAST, async () => KNOWN_A);

    expect(got.state).toEqual({ kind: 'ready', mode: 'live', url: LIVE_LIST });
    expect(got.list?.url).toBe(LIVE_LIST);
    expect(got.base).toBe(STARTED_MS);
    expect([callsTo(ROUND), callsTo(STREAM)]).toEqual([1, 1]);
  });

  it('스트림 경로가 다른 방송이면 finalizing 이고 목록을 받지 않는다', async () => {
    const got = await readEditorSourceOnce(BROADCAST, async () => ({
      kind: 'known',
      id: 'round-b',
    }));

    expect(got.state.kind).toBe('finalizing');
    expect(got.list).toBeNull();
    expect(listCalls()).toEqual([]);
  });

  it('끝난 방송은 회차 경로 vod 목록의 첫 PDT 가 base 다', async () => {
    server.round = { status: 200, body: rewind('vod', VOD_LIST) };

    const got = await readEditorSourceOnce(ENDED, async () => KNOWN_A);

    expect(got.state).toEqual({ kind: 'ready', mode: 'vod', url: VOD_LIST });
    expect(got.base).toBe(PDT0);
    expect(callsTo(STREAM)).toBe(0);
  });
});
