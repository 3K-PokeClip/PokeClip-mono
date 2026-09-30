import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ClipApiError,
  fetchAllBroadcasts,
  fetchBroadcast,
  fetchJumpCard,
  mediaStreamId,
  requestRender,
} from '@/api/clipEditor';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

// clip 창구 호출은 로그인 세션의 apiFetch를 거친다(POK-251). 실패는 ClipApiError로 옮겨 사유 코드를 지킨다.

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('clip 창구 호출', () => {
  it('거절은 ClipApiError로 온다 — 상태·사유 코드·칸이 그대로다', async () => {
    stubFetch(() => jsonResponse(409, { error: 'source_not_ready' }));

    const err = await requestRender('s1', 7).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ClipApiError);
    expect((err as ClipApiError).status).toBe(409);
    expect((err as ClipApiError).code).toBe('source_not_ready');
    expect((err as ClipApiError).field).toBeNull();
  });

  it('주문은 POST로 가고 응답 봉투를 그대로 돌려준다', async () => {
    const spy = stubFetch(() => jsonResponse(201, { id: 3, status: 'queued' }));

    const clip = await requestRender('s 1', 7);

    expect(clip).toMatchObject({ id: 3, status: 'queued' });
    const [url, init] = spy.mock.calls[0] ?? [];
    expect(url).toBe('/api/clip/broadcasts/s%201/recipes/7/renders');
    expect(init?.method).toBe('POST');
  });
});

function row(streamId: string) {
  return {
    streamId,
    status: 'ended',
    relation: 'OWNER',
    startedAt: '2026-09-01T10:00:00Z',
    endedAt: null,
    vodExpiresAt: null,
  };
}

describe('방송 목록 쪽 넘기기', () => {
  // 목록 기본 쪽 크기가 20이라 한 쪽만 보면 오래된 방송이 「없다」가 된다(POK-251 리뷰)
  it('찾는 방송이 뒤쪽에 있으면 커서를 따라가 찾는다', async () => {
    const spy = stubFetch((url) => {
      const u = new URL(url, 'http://localhost');
      if (u.searchParams.get('state') === 'live')
        return jsonResponse(200, { broadcasts: [], nextCursor: null });
      return u.searchParams.get('cursor') === 'p2'
        ? jsonResponse(200, { broadcasts: [row('old-1')], nextCursor: null })
        : jsonResponse(200, { broadcasts: [row('new-1')], nextCursor: 'p2' });
    });

    expect(await fetchBroadcast('old-1')).toMatchObject({ streamId: 'old-1' });
    const pastCalls = spy.mock.calls
      .map(([url]) => String(url))
      .filter((url) => url.includes('state=past'));
    expect(pastCalls).toHaveLength(2);
    expect(pastCalls[0]).toContain('limit=100');
    expect(pastCalls[1]).toContain('cursor=p2');
  });

  it('찾으면 그 쪽에서 멈춘다 — 뒤쪽을 더 읽지 않는다', async () => {
    const spy = stubFetch((url) =>
      url.includes('state=live')
        ? jsonResponse(200, { broadcasts: [row('live-1')], nextCursor: 'more' })
        : jsonResponse(404, { error: 'unexpected' }),
    );

    expect(await fetchBroadcast('live-1')).toMatchObject({ streamId: 'live-1' });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('끝까지 없으면 null이다', async () => {
    stubFetch(() => jsonResponse(200, { broadcasts: [row('x')], nextCursor: null }));
    expect(await fetchBroadcast('nope')).toBeNull();
  });

  it('전부 읽기는 마지막 쪽까지 모은다', async () => {
    stubFetch((url) =>
      url.includes('cursor=p2')
        ? jsonResponse(200, { broadcasts: [row('b')], nextCursor: null })
        : jsonResponse(200, { broadcasts: [row('a')], nextCursor: 'p2' }),
    );
    expect((await fetchAllBroadcasts('past')).map((b) => b.streamId)).toEqual(['a', 'b']);
  });
});

describe('카드 목록 쪽 넘기기', () => {
  // 카드 목록 기본 쪽 크기가 50이고 오래된 것부터 온다 — 한 쪽만 보면 51번째 카드를 편집기가 못 연다(PR #200 codex)
  it('찾는 카드가 뒤쪽에 있으면 커서를 따라가 찾고, 숨긴 카드도 찾는다', async () => {
    const spy = stubFetch((url) =>
      url.includes('cursor=c2')
        ? jsonResponse(200, { cards: [{ id: 77, hidden: true }], nextCursor: null })
        : jsonResponse(200, { cards: [{ id: 1, hidden: false }], nextCursor: 'c2' }),
    );

    expect(await fetchJumpCard('s1', '77')).toMatchObject({ id: 77 });
    const urls = spy.mock.calls.map(([url]) => String(url));
    expect(urls).toHaveLength(2);
    expect(urls[0]).toContain('includeHidden=true');
    expect(urls[0]).toContain('limit=200');
  });
});

describe('mediaStreamId — 영상 주소의 키(POK-233)', () => {
  it('영상 경로의 키가 있으면 그것, 없거나 비면 방송 번호다', () => {
    expect(mediaStreamId({ streamId: 'S-20260930-010000-k-1', ingestStreamId: 'k' })).toBe('k');
    expect(mediaStreamId({ streamId: 'old-key' })).toBe('old-key');
    expect(mediaStreamId({ streamId: 'old-key', ingestStreamId: null })).toBe('old-key');
    expect(mediaStreamId({ streamId: 'old-key', ingestStreamId: '' })).toBe('old-key');
  });
});
