import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { withToastProvider } from '@/test/testProviders';
import { useHomeMockState } from './useHomeMockState';

// 홈 지난 방송 격자의 「다시 보기 준비 중」 — 끝난 지 한 시간 안인 ended 방송만 회차 경로를 물어 정한다(POK-568 목록과 같은 판정).

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const POLL_MS = 10_000;
const CONTROL = 'https://ctl.test';
const ROUND_URL_RE = /^https:\/\/ctl\.test\/api\/streams\/([^/]+)\/rounds\/([^/]+)\/rewind$/;

type RoundMode = 'ending' | 'ended' | 'vod' | 404 | 503;

/** 끝난 지 agoMs 지난 방송 줄. 회차 id = streamId · 영상 키 = `key-<streamId>` */
function endedRow(streamId: string, agoMs: number, extra: Record<string, unknown> = {}) {
  const endedAt = new Date(Date.now() - agoMs).toISOString();
  return {
    streamId,
    ingestStreamId: `key-${streamId}`,
    status: 'ended',
    relation: 'OWNER',
    startedAt: new Date(Date.now() - agoMs - HOUR).toISOString(),
    endedAt,
    vodExpiresAt: null,
    title: streamId,
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
    firstPdt: ready ? '2026-11-14T02:00:00.000Z' : null,
    headPdt: ready ? '2026-11-14T02:52:31.000Z' : null,
  });
}

interface Server {
  live?: unknown[];
  past?: unknown[];
  /** 회차마다 답. 정하지 않은 회차는 ending(준비 중) */
  modes?: Record<string, RoundMode>;
  /** 참인 회차는 답하지 않다가 끊기면 진짜 fetch 처럼 AbortError 로 끝난다 */
  hold?: (roundId: string) => boolean;
}

/** 홈이 부르는 창구 전부를 흉내 낸다. 물은 회차 · 모든 주소 · 회차 물음의 동시 수를 센다 */
function serve(s: Server) {
  const seen = { asked: [] as string[], urls: [] as string[], running: 0, peak: 0 };
  stubFetch(async (url, init) => {
    seen.urls.push(url);
    const round = ROUND_URL_RE.exec(url);
    if (round !== null) return answerRound(round[2]!, s, seen, init);
    if (url.includes('state=live'))
      return jsonResponse(200, { broadcasts: s.live ?? [], nextCursor: null });
    if (url.includes('state=past'))
      return jsonResponse(200, { broadcasts: s.past ?? [], nextCursor: null });
    if (url.endsWith('/broadcast-info')) return jsonResponse(200, { latest: null, series: [] });
    if (url.includes('/jump-cards')) return jsonResponse(200, { cards: [], nextCursor: null });
    if (url.startsWith('/api/clip/library'))
      return jsonResponse(200, { items: [], nextCursor: null });
    if (url.startsWith('/api/stream-keys')) return jsonResponse(200, { issued: false });
    if (url.includes('-link')) return jsonResponse(200, { linked: false });
    return jsonResponse(200, {
      id: 1,
      email: 'demo@example.com',
      name: '재환',
      profileImageUrl: null,
    });
  });
  return seen;
}

async function answerRound(
  roundId: string,
  s: Server,
  seen: { asked: string[]; running: number; peak: number },
  init: RequestInit | undefined,
): Promise<Response> {
  seen.asked.push(roundId);
  seen.running += 1;
  seen.peak = Math.max(seen.peak, seen.running);
  try {
    if (s.hold?.(roundId)) {
      return await new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('aborted', 'AbortError'));
        });
      });
    }
    await Promise.resolve();
    return roundAnswer(s.modes?.[roundId] ?? 'ending');
  } finally {
    seen.running -= 1;
  }
}

/** 가짜 시계를 ms 만큼 돌리고 그 사이 뜬 일을 다 끝낸다 */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

function badgeOf(result: { current: ReturnType<typeof useHomeMockState> }, id: string) {
  return result.current.vods.find((v) => v.id === id)?.badge?.kind;
}

async function renderHome() {
  const hook = renderHook(() => useHomeMockState(), { wrapper: withToastProvider });
  await waitFor(() => expect(hook.result.current.vods.length).toBeGreaterThan(0));
  return hook;
}

describe('useHomeMockState — 다시 보기 준비 중(회차 경로)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', CONTROL);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('방금 끝난 방송은 회차 답으로 정한다: 다시보기가 섰거나 없으면 「준비 중」 을 달지 않고, 준비 중이면 단다', async () => {
    serve({
      past: [endedRow('R', 5 * MINUTE), endedRow('N', 5 * MINUTE), endedRow('P', 5 * MINUTE)],
      modes: { R: 'vod', N: 'ended', P: 'ending' },
    });
    const { result } = await renderHome();

    expect(badgeOf(result, 'R')).toBeUndefined();
    expect(badgeOf(result, 'N')).toBeUndefined();
    expect(badgeOf(result, 'P')).toBe('preparing');
    // 다시보기가 없어도 지난 방송 화면(카드 · 채팅)으로 그대로 연다
    expect(result.current.vods.find((v) => v.id === 'N')?.href).toBe('/broadcast/vod/N');
  });

  it('다시보기가 서도 곧 지워질 방송의 「D-n 삭제」 는 그대로 단다', async () => {
    const vodExpiresAt = new Date(Date.now() + 2 * 24 * HOUR - MINUTE).toISOString();
    serve({ past: [endedRow('R', 5 * MINUTE, { vodExpiresAt })], modes: { R: 'vod' } });
    const { result } = await renderHome();

    expect(result.current.vods[0]?.badge).toEqual({ kind: 'dday', label: 'D-2' });
  });

  it('답을 못 받으면(잠시 불가 · 아직 모름) 「준비 중」 이다', async () => {
    serve({
      past: [endedRow('busy', 5 * MINUTE), endedRow('unknown', 5 * MINUTE)],
      modes: { busy: 503, unknown: 404 },
    });
    const { result } = await renderHome();

    expect(badgeOf(result, 'busy')).toBe('preparing');
    expect(badgeOf(result, 'unknown')).toBe('preparing');
  });

  it('답이 시한(8초) 안에 안 오면 끊고 「준비 중」 이다', async () => {
    const seen = serve({ past: [endedRow('slow', 5 * MINUTE)], hold: () => true });
    const hook = renderHook(() => useHomeMockState(), { wrapper: withToastProvider });
    await advance(100);
    expect(seen.asked).toEqual(['slow']);

    await advance(8_000);

    await waitFor(() => expect(badgeOf(hook.result, 'slow')).toBe('preparing'));
  });

  it('다시 묻는 답이 오기 전에는 「준비 중」 을 그대로 둔다', async () => {
    let hang = false;
    const seen = serve({ past: [endedRow('P', 5 * MINUTE)], hold: () => hang });
    const { result } = await renderHome();
    expect(badgeOf(result, 'P')).toBe('preparing');

    hang = true;
    await advance(POLL_MS);

    expect(seen.asked).toEqual(['P', 'P']);
    expect(badgeOf(result, 'P')).toBe('preparing');
  });

  it('제어면 주소가 없으면 묻지 않고 「준비 중」 이다', async () => {
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', '');
    const seen = serve({ past: [endedRow('P', 5 * MINUTE)], modes: { P: 'vod' } });
    const { result } = await renderHome();

    expect(seen.asked).toEqual([]);
    expect(badgeOf(result, 'P')).toBe('preparing');
  });

  it('끝난 지 한 시간이 지난 방송 · 방송 중 · 다시 보기로 올라온 방송은 회차를 묻지 않고, /playback 도 부르지 않는다', async () => {
    const seen = serve({
      live: [{ ...endedRow('L', 0), status: 'live', endedAt: null }],
      past: [
        endedRow('J', 5 * MINUTE),
        endedRow('old', HOUR + MINUTE),
        endedRow('V', 5 * MINUTE, { status: 'vod_ready' }),
      ],
      modes: { J: 'vod' },
    });
    const { result } = await renderHome();
    await advance(POLL_MS);

    expect(seen.asked).toEqual(['J']);
    expect(badgeOf(result, 'old')).toBeUndefined();
    expect(seen.urls.filter((u) => u.includes('/playback'))).toEqual([]);
  });

  it('홈 주기마다 준비 중인 방송만 다시 묻는다: 다시보기가 섰거나 없다고 굳은 방송은 다시 묻지 않는다', async () => {
    const seen = serve({
      past: [endedRow('R', 5 * MINUTE), endedRow('N', 5 * MINUTE), endedRow('P', 5 * MINUTE)],
      modes: { R: 'vod', N: 'ended' },
    });
    await renderHome();
    expect([...seen.asked].sort()).toEqual(['N', 'P', 'R']);

    await advance(POLL_MS);

    expect(seen.asked.slice(3)).toEqual(['P']);
  });

  it('회차 물음은 격자에 보이는 방송만, 동시에 6개까지다', async () => {
    const rows = Array.from({ length: 10 }, (_, i) => endedRow(`S-${i}`, (i + 1) * MINUTE));
    const seen = serve({ past: rows, hold: () => true });
    renderHook(() => useHomeMockState(), { wrapper: withToastProvider });
    await advance(100);
    expect(seen.peak).toBe(6);

    await advance(8_000);
    await advance(100);

    expect(new Set(seen.asked).size).toBe(8);
    expect(seen.asked).toEqual(expect.arrayContaining(rows.slice(0, 8).map((b) => b.streamId)));
  });
});
