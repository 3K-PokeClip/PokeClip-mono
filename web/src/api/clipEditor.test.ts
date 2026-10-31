import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  broadcastTitle,
  ClipApiError,
  fetchAllBroadcasts,
  fetchAllJumpCards,
  fetchBroadcast,
  forgetBroadcastRows,
  fetchJumpCard,
  fetchLibraryAll,
  mediaStreamId,
  openJumpCard,
  platformLabel,
  requestRender,
  retryUpload,
  setJumpCardHidden,
  type UploadInfo,
} from '@/api/clipEditor';
import { useAuthStore } from '@/stores/auth';
import { useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

// clip 창구 호출은 로그인 세션의 apiFetch를 거친다(POK-251). 실패는 ClipApiError로 옮겨 사유 코드를 지킨다.

afterEach(() => {
  vi.unstubAllGlobals();
  forgetBroadcastRows();
  vi.useRealTimers();
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
    categoryId: '24',
    privacyStatus: 'private',
    publishAt: '2026-10-23T09:00:00.000Z',
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

describe('openJumpCard: 열기 기록 실패는 삼킨다(POK-393)', () => {
  // 측정 재료 하나 때문에 편집을 막지 않는다: 부르는 쪽은 결과를 기다리지 않으므로 거부가 새면 처리 안 된 거부가 된다
  it('서버가 503을 줘도 거부 없이 끝난다', async () => {
    const spy = stubFetch(() => jsonResponse(503, { error: 'unavailable' }));
    await expect(openJumpCard('5')).resolves.toBeUndefined();
    expect(spy.mock.calls.map(([url, init]) => [String(url), init?.method])).toEqual([
      ['/api/clip/jump-cards/5/open', 'POST'],
    ]);
  });

  it('네트워크 오류여도 거부 없이 끝난다', async () => {
    stubFetch(() => Promise.reject(new TypeError('Failed to fetch')));
    await expect(openJumpCard('5')).resolves.toBeUndefined();
  });
});

describe('카드 목록 쪽 넘기기', () => {
  // 카드 목록 기본 쪽 크기가 50이고 오래된 것부터 온다 — 한 쪽만 보면 51번째 카드를 편집기가 못 연다(PR #200 codex)
  it('찾는 카드가 뒤쪽에 있으면 커서를 따라가 찾는다', async () => {
    const spy = stubFetch((url) =>
      url.includes('cursor=c2')
        ? jsonResponse(200, { cards: [{ id: 77 }], nextCursor: null })
        : jsonResponse(200, { cards: [{ id: 1 }], nextCursor: 'c2' }),
    );

    expect(await fetchJumpCard('s1', '77')).toMatchObject({ id: 77 });
    const urls = spy.mock.calls.map(([url]) => String(url));
    expect(urls).toHaveLength(2);
    // 가린 카드도 편집기로는 연다(POK-463): 쪽마다 가린 카드까지 달라고 한다
    for (const u of urls) expect(u).toContain('includeHidden=true');
    expect(urls[0]).toContain('limit=200');
  });

  it('기본은 가린 카드를 달라고 하지 않는다: 홈·지난 방송 목록의 카드 수에서 빠진다 (POK-463)', async () => {
    const spy = stubFetch(() => jsonResponse(200, { cards: [], nextCursor: null }));

    await fetchAllJumpCards('s1');

    expect(String(spy.mock.calls[0]?.[0])).not.toContain('includeHidden');
  });
});

describe('카드 가리기 · 되살리기 (POK-463)', () => {
  it('가리기는 POST, 되살리기는 DELETE이고 답의 카드를 돌려준다', async () => {
    const spy = stubFetch((_url, init) =>
      jsonResponse(200, { id: 8, hidden: init?.method === 'POST', eventSeq: 9 }),
    );

    expect(await setJumpCardHidden('8', true)).toMatchObject({ id: 8, hidden: true });
    expect(await setJumpCardHidden('8', false)).toMatchObject({ id: 8, hidden: false });
    expect(spy.mock.calls.map(([url, init]) => [String(url), init?.method])).toEqual([
      ['/api/clip/jump-cards/8/hide', 'POST'],
      ['/api/clip/jump-cards/8/hide', 'DELETE'],
    ]);
  });

  it('실패는 던진다: 화면이 알린다', async () => {
    stubFetch(() => jsonResponse(503, { error: 'authorization_unavailable' }));

    await expect(setJumpCardHidden('8', true)).rejects.toThrow();
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

describe('platformLabel: 방송 플랫폼 표시 이름(POK-398)', () => {
  it.each([
    ['CHZZK', '치지직'],
    ['SOOP', 'SOOP'],
  ])('%s → %s', (code, label) => {
    expect(platformLabel(code)).toBe(label);
  });

  // 모르는 코드를 치지직이라 적지 않는다. 태그를 숨긴다
  it.each([['CIME'], ['chzzk'], [''], [null], [undefined]])('%s → null', (code) => {
    expect(platformLabel(code)).toBeNull();
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

describe('고른 채널로 좁히기 (POK-390)', () => {
  const goneSignal = () => useChannelStore.getState().goneSignal;

  it('방송 전부 읽기는 쪽마다 streamerId를 싣는다', async () => {
    const spy = stubFetch((url) =>
      url.includes('cursor=p2')
        ? jsonResponse(200, { broadcasts: [row('b')], nextCursor: null })
        : jsonResponse(200, { broadcasts: [row('a')], nextCursor: 'p2' }),
    );

    await fetchAllBroadcasts('past', 3);

    const urls = spy.mock.calls.map(([url]) => new URL(String(url), 'http://localhost'));
    expect(urls).toHaveLength(2);
    expect(urls.map((u) => u.searchParams.get('streamerId'))).toEqual(['3', '3']);
  });

  it('번호가 없으면 streamerId를 안 싣는다: 옛 호출(섞어서)과 같다', async () => {
    const spy = stubFetch(() => jsonResponse(200, { broadcasts: [], nextCursor: null }));

    await fetchAllBroadcasts('past');
    await fetchAllBroadcasts('live', null);

    for (const [url] of spy.mock.calls) expect(String(url)).not.toContain('streamerId');
  });

  it('보관함 전부 읽기도 쪽마다 streamerId를 싣는다', async () => {
    const spy = stubFetch((url) =>
      url.includes('cursor=p2')
        ? jsonResponse(200, { items: [], nextCursor: null })
        : jsonResponse(200, { items: [], nextCursor: 'p2' }),
    );

    await fetchLibraryAll(3);

    const urls = spy.mock.calls.map(([url]) => new URL(String(url), 'http://localhost'));
    expect(urls.map((u) => u.searchParams.get('streamerId'))).toEqual(['3', '3']);
  });

  it('방송 한 줄 찾기는 채널 제한 없이 그대로다: 남의 채널 방송 바로가기를 연다', async () => {
    const spy = stubFetch(() => jsonResponse(200, { broadcasts: [row('x')], nextCursor: null }));

    await fetchBroadcast('x');

    for (const [url] of spy.mock.calls) expect(String(url)).not.toContain('streamerId');
  });

  it('고른 채널의 목록이 404면 끊김 신호를 한 번 보내고 오류는 그대로 던진다', async () => {
    stubFetch(() => jsonResponse(404, { error: 'broadcast_not_found' }));
    const before = goneSignal();

    await expect(fetchAllBroadcasts('past', 3)).rejects.toBeInstanceOf(ClipApiError);
    expect(goneSignal()).toBe(before + 1);
    // 어느 번호로 물었는지 싣는다: 내 채널이면 ChannelScope가 끊김 대신 세션 어긋남으로 본다
    expect(useChannelStore.getState().goneFor).toBe(3);

    await expect(fetchLibraryAll(3)).rejects.toMatchObject({ status: 404 });
    expect(goneSignal()).toBe(before + 2);
  });

  it('번호 없이 404거나 404가 아닌 실패는 신호를 안 보낸다', async () => {
    const before = goneSignal();
    stubFetch(() => jsonResponse(404, { error: 'broadcast_not_found' }));
    await fetchAllBroadcasts('past').catch(() => null);
    await fetchLibraryAll().catch(() => null);
    vi.unstubAllGlobals();
    stubFetch(() => jsonResponse(500, { error: 'boom' }));
    await fetchAllBroadcasts('past', 3).catch(() => null);

    expect(goneSignal()).toBe(before);
  });
});

describe('방송 한 줄 기억', () => {
  const finishedRow = (streamId: string) => ({
    ...row(streamId),
    endedAt: '2026-09-01T12:00:00Z',
    timelineOriginAt: '2026-09-01T10:00:01Z',
  });
  const liveRow = (streamId: string) => ({ ...row(streamId), status: 'live' });
  const listOf = (...rows: object[]) => jsonResponse(200, { broadcasts: rows, nextCursor: null });

  it('한 번 읽은 줄은 다시 묻지 않는다: 목록을 다시 걷지 않고 같은 줄을 준다', async () => {
    const spy = stubFetch((url) =>
      url.includes('state=live') ? listOf() : listOf(finishedRow('a'), finishedRow('b')),
    );

    await fetchBroadcast('a');
    const calls = spy.mock.calls.length;
    // 같은 목록에서 읽힌 다른 방송도 네트워크 없이 답한다
    expect(await fetchBroadcast('b')).toMatchObject({ streamId: 'b' });
    expect(await fetchBroadcast('a')).toMatchObject({ streamId: 'a' });

    expect(spy).toHaveBeenCalledTimes(calls);
  });

  it('같은 방송을 동시에 물으면 목록 걷기는 하나다', async () => {
    const spy = stubFetch((url) => (url.includes('state=live') ? listOf(liveRow('x')) : listOf()));

    const [first, second] = await Promise.all([fetchBroadcast('x'), fetchBroadcast('x')]);

    expect(first).toBe(second);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('목록 전체 읽기가 읽은 줄도 나눠 쓴다', async () => {
    const spy = stubFetch(() => listOf(finishedRow('z')));

    await fetchAllBroadcasts('past');
    const calls = spy.mock.calls.length;
    await fetchBroadcast('z');

    expect(spy).toHaveBeenCalledTimes(calls);
  });

  it('방송 중인 줄은 20초만 쓴다: 그 뒤에는 라이브가 끝났는지 다시 읽는다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const spy = stubFetch((url) => (url.includes('state=live') ? listOf(liveRow('x')) : listOf()));

    await fetchBroadcast('x');
    vi.advanceTimersByTime(19_000);
    await fetchBroadcast('x');
    expect(spy).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(2_000);
    await fetchBroadcast('x');
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('끝난 줄은 몇 분 쓴다: 5분이 지나면 다시 읽는다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const spy = stubFetch((url) =>
      url.includes('state=live') ? listOf() : listOf(finishedRow('a')),
    );

    await fetchBroadcast('a');
    const calls = spy.mock.calls.length;
    vi.advanceTimersByTime(4 * 60_000);
    await fetchBroadcast('a');
    expect(spy).toHaveBeenCalledTimes(calls);

    vi.advanceTimersByTime(60_000);
    await fetchBroadcast('a');
    expect(spy.mock.calls.length).toBeGreaterThan(calls);
  });

  it('끝났어도 기준점이 아직 없는 줄은 짧게만 쓴다: 늦게 채워지는 값을 오래 가리지 않는다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const spy = stubFetch((url) =>
      url.includes('state=live')
        ? listOf()
        : listOf({ ...finishedRow('a'), timelineOriginAt: null }),
    );

    await fetchBroadcast('a');
    const calls = spy.mock.calls.length;
    vi.advanceTimersByTime(21_000);
    await fetchBroadcast('a');

    expect(spy.mock.calls.length).toBeGreaterThan(calls);
  });

  it('fresh면 기억을 건너뛰고 다시 읽는다: 방송 시계가 상태를 보는 길', async () => {
    let ended = false;
    stubFetch((url) =>
      url.includes('state=live')
        ? listOf(...(ended ? [] : [liveRow('x')]))
        : listOf(...(ended ? [finishedRow('x')] : [])),
    );

    expect((await fetchBroadcast('x'))?.status).toBe('live');
    ended = true;
    expect((await fetchBroadcast('x'))?.status).toBe('live');
    expect((await fetchBroadcast('x', undefined, { fresh: true }))?.status).toBe('ended');
  });

  it('기다리는 쪽이 끊어도 같이 기다리던 다른 쪽은 답을 받는다', async () => {
    stubFetch((url) => (url.includes('state=live') ? listOf(liveRow('x')) : listOf()));
    const abort = new AbortController();

    const cancelled = fetchBroadcast('x', abort.signal);
    const patient = fetchBroadcast('x');
    abort.abort();

    await expect(cancelled).rejects.toBeDefined();
    expect(await patient).toMatchObject({ streamId: 'x' });
  });

  it('계정이 바뀌면 기억을 비운다: 앞 계정이 읽은 줄을 다음 계정에 주지 않는다', async () => {
    const spy = stubFetch((url) =>
      url.includes('state=live') ? listOf() : listOf(finishedRow('a')),
    );

    await fetchBroadcast('a');
    const calls = spy.mock.calls.length;
    useAuthStore.getState().clearTokens();
    await fetchBroadcast('a');

    expect(spy.mock.calls.length).toBeGreaterThan(calls);
  });

  it('없는 방송(null)은 기억하지 않는다', async () => {
    const spy = stubFetch(() => listOf());

    expect(await fetchBroadcast('none')).toBeNull();
    const calls = spy.mock.calls.length;
    await fetchBroadcast('none');

    expect(spy.mock.calls.length).toBeGreaterThan(calls);
  });
});
