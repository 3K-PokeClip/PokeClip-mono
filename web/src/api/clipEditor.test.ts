import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  broadcastTitle,
  ClipApiError,
  fetchAllBroadcasts,
  fetchBroadcast,
  fetchJumpCard,
  mediaStreamId,
  requestRender,
  retryUpload,
  type UploadInfo,
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

  it('주문은 POST로 가고 응답 봉투를 그대로 돌려준다: 업로드 정보가 없으면 본문도 없다', async () => {
    const spy = stubFetch(() => jsonResponse(201, { id: 3, status: 'queued' }));

    const { created, clip } = await requestRender('s 1', 7);

    expect(created).toBe(true);
    expect(clip).toMatchObject({ id: 3, status: 'queued' });
    const [url, init] = spy.mock.calls[0] ?? [];
    expect(url).toBe('/api/clip/broadcasts/s%201/recipes/7/renders');
    expect(init?.method).toBe('POST');
    expect(init?.body).toBeUndefined();
  });

  it('200은 새 주문이 아니다: 같은 판이 이미 만들어지는 중이거나 만들어져 있다', async () => {
    stubFetch(() => jsonResponse(200, { id: 3, status: 'rendering' }));
    expect((await requestRender('s1', 7)).created).toBe(false);
  });
});

describe('영상 만들기 + 유튜브 올리기 주문 (POK-291)', () => {
  const info: UploadInfo = {
    title: '보스 막타',
    description: '설명',
    tags: ['롤', '보스 막타'],
    privacyStatus: 'unlisted',
    madeForKids: false,
    thumbnail: { source: 'scene', offsetMs: 12_000 },
  };

  async function partsOf(init: RequestInit | undefined) {
    expect(init?.body).toBeInstanceOf(FormData);
    const form = init?.body as FormData;
    const request = form.get('request') as Blob;
    expect(request.type).toBe('application/json');
    return { form, request: JSON.parse(await request.text()) as unknown };
  }

  it('업로드 정보는 multipart의 request 파트(JSON)로 간다: 형식은 브라우저가 경계까지 정한다', async () => {
    const spy = stubFetch(() => jsonResponse(201, { id: 3, status: 'queued' }));

    await requestRender('s1', 7, info);

    const [, init] = spy.mock.calls[0] ?? [];
    const { form, request } = await partsOf(init);
    expect(request).toEqual({ upload: info });
    expect(form.has('thumbnail')).toBe(false);
    // 직접 Content-Type을 박으면 경계(boundary)가 사라져 서버가 파트를 못 찾는다
    expect(new Headers(init?.headers).has('Content-Type')).toBe(false);
  });

  it('이미지를 올리면 thumbnail 파트에 파일이 실린다', async () => {
    const spy = stubFetch(() => jsonResponse(201, { id: 3, status: 'queued' }));
    const file = new File([new Uint8Array([0xff, 0xd8, 0xff])], 'cover.jpg', {
      type: 'image/jpeg',
    });

    await requestRender('s1', 7, { ...info, thumbnail: { source: 'file' } }, file);

    const { form, request } = await partsOf(spy.mock.calls[0]?.[1]);
    expect(request).toEqual({ upload: { ...info, thumbnail: { source: 'file' } } });
    const part = form.get('thumbnail') as File;
    expect(part.name).toBe('cover.jpg');
    expect(new Uint8Array(await part.arrayBuffer())).toEqual(new Uint8Array([0xff, 0xd8, 0xff]));
  });

  it('이미지를 고르지 않은 썸네일이면 파일을 들고 있어도 싣지 않는다: 서버가 400으로 거절한다', async () => {
    const spy = stubFetch(() => jsonResponse(201, { id: 3, status: 'queued' }));
    const file = new File([new Uint8Array([1])], 'x.png', { type: 'image/png' });

    await requestRender('s1', 7, info, file);

    const { form } = await partsOf(spy.mock.calls[0]?.[1]);
    expect(form.has('thumbnail')).toBe(false);
  });

  it('업로드 다시 시도는 본문 없이 POST하고 201·200을 가른다', async () => {
    const spy = stubFetch(() => jsonResponse(201, { id: 9, clipId: 5, status: 'queued' }));

    const { created, upload } = await retryUpload('s 1', 5);

    expect(created).toBe(true);
    expect(upload).toMatchObject({ id: 9, status: 'queued' });
    const [url, init] = spy.mock.calls[0] ?? [];
    expect(url).toBe('/api/clip/broadcasts/s%201/clips/5/uploads/retry');
    expect(init?.method).toBe('POST');
    expect(init?.body).toBeUndefined();

    stubFetch(() => jsonResponse(200, { id: 8, clipId: 5, status: 'uploading' }));
    expect((await retryUpload('s1', 5)).created).toBe(false);
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

describe('broadcastTitle — 방송을 보일 이름(POK-259)', () => {
  it('치지직 제목이 있으면 앞뒤 공백을 깎아 쓰고, 없거나 비면 방송 번호다', () => {
    expect(broadcastTitle({ streamId: 'S-1', title: '  롤 랭크 ' })).toBe('롤 랭크');
    expect(broadcastTitle({ streamId: 'S-1', title: '   ' })).toBe('S-1');
    expect(broadcastTitle({ streamId: 'S-1', title: null })).toBe('S-1');
    expect(broadcastTitle({ streamId: 'S-1' })).toBe('S-1');
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
