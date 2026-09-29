import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { withToastProvider } from '@/test/testProviders';
import { LIBRARY_FIXTURE } from './libraryFixture';
import { useLibraryMockState, type LibraryOptions } from './useLibraryMockState';

// 시안 1g 목업 8건과 얼린 시각을 주입한다 — 주입하지 않으면 훅이 clip 보관함 문을 부른다
function renderLibrary(options?: LibraryOptions) {
  return renderHook(() => useLibraryMockState({ ...LIBRARY_FIXTURE, ...options }), {
    wrapper: withToastProvider,
  });
}

function ids(result: ReturnType<typeof renderLibrary>['result']) {
  return result.current.clips.map((clip) => clip.id);
}

describe('useLibraryMockState', () => {
  it('시안 1g 기본값으로 시작한다 — 스트리머 · 8건 · 최근 편집순 · 전체 · 미선택', () => {
    const { result } = renderLibrary();

    expect(result.current.role).toBe('streamer');
    expect(result.current.totalCount).toBe(8);
    expect(result.current.sort).toBe('edited');
    expect(result.current.chip).toBe('all');
    expect(result.current.query).toBe('');
    expect(result.current.selectedId).toBeNull();
    expect(result.current.selectedClip).toBeNull();
    // 최근 편집순 — 시안의 카드 순서(id 순)가 아니라 editedAt이 정한다
    expect(ids(result)).toEqual([
      'lib2-3',
      'lib2-7',
      'lib2-2',
      'lib2-1',
      'lib2-5',
      'lib2-8',
      'lib2-4',
      'lib2-6',
    ]);
  });

  it('칩 수를 시점별로 센다 — 스트리머 작업 중 4 · 업로드 대기 2 · 발행됨 2 / 편집자 작업 중 3 · 반려됨 1', () => {
    const streamer = renderLibrary();
    expect(streamer.result.current.counts).toEqual({
      all: 8,
      working: 4,
      ready: 2,
      rejected: 0,
      published: 2,
    });

    const editor = renderLibrary({ role: 'editor' });
    expect(editor.result.current.counts).toEqual({
      all: 8,
      working: 3,
      ready: 2,
      rejected: 1,
      published: 2,
    });
  });

  it('승인 대기 수를 배너용으로 준다', () => {
    const { result } = renderLibrary();
    expect(result.current.pendingCount).toBe(1);
  });

  it('같은 카드를 두 번 고르면 해제된다', () => {
    const { result } = renderLibrary();

    act(() => result.current.select('lib2-1'));
    expect(result.current.selectedId).toBe('lib2-1');
    expect(result.current.selectedClip?.title).toBe('보스 막타 · 역전 순간');

    act(() => result.current.select('lib2-1'));
    expect(result.current.selectedId).toBeNull();
    expect(result.current.selectedClip).toBeNull();
  });

  it('다른 카드를 고르면 선택이 옮겨 가고 deselect가 푼다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });

    act(() => result.current.select('lib2-2'));
    expect(result.current.selectedId).toBe('lib2-2');

    act(() => result.current.deselect());
    expect(result.current.selectedId).toBeNull();
  });

  it('업로드는 스트리머면 발행됨, 편집자면 승인 대기로 옮긴다', () => {
    const streamer = renderLibrary({ selectedId: 'lib2-2' });
    act(() => streamer.result.current.upload('lib2-2'));
    expect(streamer.result.current.selectedClip?.status).toBe('published');
    // 목업 업로드로 발행된 것은 갈 곳(유튜브 주소)이 없다
    expect(streamer.result.current.selectedClip?.youtubeUrl).toBeUndefined();

    const editor = renderLibrary({ role: 'editor', selectedId: 'lib2-2' });
    act(() => editor.result.current.upload('lib2-2'));
    expect(editor.result.current.selectedClip?.status).toBe('pending');
    expect(editor.result.current.pendingCount).toBe(2);
  });

  it('업로드 대기가 아닌 편집본은 업로드해도 그대로다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });
    act(() => result.current.upload('lib2-1'));
    expect(result.current.selectedClip?.status).toBe('editing');
  });

  it('렌더 재시도는 업로드 대기로 돌린다 — 실패한 것만, 길이는 여전히 모른다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-8' });
    act(() => result.current.retryRender('lib2-8'));
    expect(result.current.selectedClip?.status).toBe('ready');
    expect(result.current.selectedClip?.durationSec).toBeNull();

    act(() => result.current.select('lib2-1'));
    act(() => result.current.retryRender('lib2-1'));
    expect(result.current.selectedClip?.status).toBe('editing');
  });

  it('삭제는 목록에서 빼고 선택도 푼다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-2' });
    act(() => result.current.remove('lib2-2'));

    expect(result.current.totalCount).toBe(7);
    expect(ids(result)).not.toContain('lib2-2');
    expect(result.current.selectedId).toBeNull();
    expect(result.current.counts.ready).toBe(1);
  });

  it('다른 편집본을 지우면 선택은 남는다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });
    act(() => result.current.remove('lib2-2'));
    expect(result.current.selectedId).toBe('lib2-1');
  });

  it('제목은 입력마다 저장된다 — selectedClip에도 바로 비친다', () => {
    const { result } = renderLibrary({ selectedId: 'lib2-1' });
    act(() => result.current.renameClip('lib2-1', '보스 막타'));
    expect(result.current.selectedClip?.title).toBe('보스 막타');
    expect(result.current.clips.find((c) => c.id === 'lib2-1')?.title).toBe('보스 막타');
  });

  it('검색·칩·정렬이 함께 걸린다 — 「랭크」 검색 + 업로드 대기 칩 → 1건', () => {
    const { result } = renderLibrary();

    act(() => result.current.setQuery('랭크'));
    expect(ids(result)).toEqual(['lib2-7']);
    // 칩 수는 검색과 무관하다 — 재고를 센다
    expect(result.current.counts.all).toBe(8);

    act(() => result.current.setChip('ready'));
    expect(ids(result)).toEqual(['lib2-7']);

    act(() => result.current.setQuery('보스'));
    expect(ids(result)).toEqual([]);
    expect(result.current.totalCount).toBe(8);
  });

  it('만료 임박순은 원본이 가장 먼저 사라질 것부터, 이미 만료된 것은 마지막이다', () => {
    const { result } = renderLibrary();
    act(() => result.current.setSort('expiry'));

    const order = ids(result);
    expect(order[0]).toBe('lib2-4'); // 7월 22일 라이브 — D-18
    expect(order[order.length - 1]).toBe('lib2-6'); // 5월 — 만료됨
  });

  it('clips를 주입하면 totalCount가 그것을 따른다 — 빈 배열이면 0', () => {
    const { result } = renderLibrary({ clips: [] });
    expect(result.current.totalCount).toBe(0);
    expect(result.current.clips).toEqual([]);
    expect(result.current.pendingCount).toBe(0);
  });
});

describe('useLibraryMockState — 서버 줄', () => {
  it('유튜브에 올리는 중인 편집본이 있으면 저절로 다시 읽어 올림으로 바뀐다', async () => {
    vi.useFakeTimers();
    let status = 'uploading';
    const entry = () => ({
      recipeId: 12,
      streamId: 's1',
      creatorId: '9',
      recipeVersion: 2,
      cut: { inAtMs: 0, outAtMs: 30_000 },
      status,
      broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
      latestClip: null,
      createdAt: '2026-09-20T11:00:00Z',
      updatedAt: '2026-09-20T12:00:00Z',
    });
    stubFetch((url) =>
      url.startsWith('/api/clip/library')
        ? jsonResponse(200, { items: [entry()], nextCursor: null })
        : jsonResponse(200, { id: 9, email: 'me@example.com' }),
    );
    const { result } = renderHook(() => useLibraryMockState(), { wrapper: withToastProvider });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.clips[0]?.subtitleLabel).toBe('유튜브에 올리는 중');

    status = 'uploaded';
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(result.current.clips[0]?.status).toBe('published');
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });
});

describe('useLibraryMockState — 유튜브 업로드·내려받기 (POK-111)', () => {
  const rendered = {
    id: 5,
    streamId: 's1',
    recipeId: 12,
    recipeVersion: 2,
    requestedBy: '9',
    status: 'rendered',
    progress: null,
    outputs: [{ outputId: 'o1', kind: 'video', s3Key: 'k' }],
    error: null,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
    upload: null,
  };
  const libraryEntry = {
    recipeId: 12,
    streamId: 's 1',
    creatorId: '9',
    recipeVersion: 2,
    cut: { inAtMs: 0, outAtMs: 30_000 },
    status: 'rendered',
    broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
    latestClip: rendered,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
  };
  const uploadReply = {
    id: 7,
    clipId: 5,
    outputId: 'o1',
    title: '보스 막타',
    status: 'queued',
    videoId: null as string | null,
    error: null,
    requestedBy: '9',
    createdAt: '2026-09-20T12:00:00Z',
    updatedAt: '2026-09-20T12:00:00Z',
  };

  function serve(onPost: (url: string, init?: RequestInit) => Response | Promise<Response>) {
    return stubFetch((url, init) => {
      if (init?.method === 'POST') return onPost(url, init);
      return url.startsWith('/api/clip/library')
        ? jsonResponse(200, { items: [libraryEntry], nextCursor: null })
        : jsonResponse(200, { id: 9 });
    });
  }

  async function mount() {
    const view = renderHook(() => useLibraryMockState(), { wrapper: withToastProvider });
    await vi.waitFor(() => expect(view.result.current.loading).toBe(false));
    return view;
  }

  function posts(spy: ReturnType<typeof stubFetch>) {
    return spy.mock.calls.filter(([, init]) => init?.method === 'POST');
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('패널 제목(앞뒤 공백 뺌)으로 가장 최근 영상을 올리고, 답을 받자마자 올리는 중으로 바뀐다', async () => {
    const spy = serve(() => jsonResponse(201, uploadReply));
    const { result } = await mount();

    act(() => result.current.renameClip('12', '  보스 막타 '));
    act(() => result.current.upload('12'));
    expect(result.current.sendingIds.has('12')).toBe(true);
    await vi.waitFor(() => expect(result.current.sendingIds.has('12')).toBe(false));

    const [url, init] = posts(spy)[0] ?? [];
    expect(url).toBe('/api/clip/broadcasts/s%201/clips/5/uploads');
    expect(JSON.parse(String(init?.body))).toEqual({ title: '보스 막타', outputId: 'o1' });
    expect(result.current.clips[0]?.entry?.status).toBe('uploading');
    expect(result.current.clips[0]?.subtitleLabel).toBe('유튜브에 올리는 중');
    expect(result.current.clips[0]?.title).toBe('보스 막타');
  });

  it('답을 기다리는 동안 다시 눌러도 한 번만 보낸다', async () => {
    let answer: (r: Response) => void = () => {};
    const spy = serve(() => new Promise<Response>((resolve) => (answer = resolve)));
    const { result } = await mount();

    act(() => {
      result.current.upload('12');
      result.current.upload('12');
    });
    act(() => result.current.upload('12'));
    await vi.waitFor(() => expect(posts(spy)).toHaveLength(1));
    await act(async () => {});
    expect(posts(spy)).toHaveLength(1);

    await act(async () => answer(jsonResponse(200, uploadReply)));
    await vi.waitFor(() => expect(result.current.sendingIds.size).toBe(0));
  });

  it('주문 전에 떠난 목록 읽기가 늦게 와도 올리는 중을 되돌리지 않는다', async () => {
    let listCalls = 0;
    let lateList: (r: Response) => void = () => {};
    stubFetch((url, init) => {
      if (init?.method === 'POST') return jsonResponse(201, uploadReply);
      if (url.startsWith('/api/clip/library')) {
        listCalls += 1;
        const body = { items: [libraryEntry], nextCursor: null };
        return listCalls === 1
          ? jsonResponse(200, body)
          : new Promise<Response>((resolve) => (lateList = () => resolve(jsonResponse(200, body))));
      }
      return jsonResponse(200, { id: 9 });
    });
    const { result } = await mount();

    act(() => result.current.refresh()); // 폴링처럼 읽기가 먼저 떠난다
    act(() => result.current.upload('12'));
    await vi.waitFor(() => expect(result.current.clips[0]?.entry?.status).toBe('uploading'));

    await act(async () => lateList(jsonResponse(200, {})));
    await act(async () => {});
    expect(result.current.clips[0]?.entry?.status).toBe('uploading');
  });

  it('이미 살아 있는 업로드를 돌려받으면(200) 새로 시작했다고 말하지 않는다', async () => {
    serve(() =>
      jsonResponse(200, {
        ...uploadReply,
        title: '먼저 올린 제목',
        status: 'uploaded',
        videoId: 'v1',
      }),
    );
    const { result } = await mount();

    act(() => result.current.renameClip('12', '내가 친 제목'));
    act(() => result.current.upload('12'));
    await vi.waitFor(() => expect(result.current.clips[0]?.entry?.status).toBe('uploaded'));

    expect(result.current.clips[0]?.title).toBe('먼저 올린 제목');
    expect(document.body.textContent).toContain('이미 주문된 업로드가 있어요');
    expect(document.body.textContent).not.toContain('유튜브 업로드를 시작했어요');
  });

  it('남은 제목 초안은 완성(올릴 수 있는) 편집본에만 얹는다 — 올린 뒤에는 서버 제목이 정본', async () => {
    let status = 'rendered';
    let upload: typeof uploadReply | null = null;
    stubFetch((url, init) => {
      if (init?.method === 'POST') return jsonResponse(503, { error: 'upload_unavailable' });
      return url.startsWith('/api/clip/library')
        ? jsonResponse(200, {
            items: [{ ...libraryEntry, status, latestClip: { ...rendered, upload } }],
            nextCursor: null,
          })
        : jsonResponse(200, { id: 9 });
    });
    const { result } = await mount();

    act(() => result.current.renameClip('12', '실패한 초안'));
    act(() => result.current.upload('12'));
    await vi.waitFor(() => expect(result.current.sendingIds.size).toBe(0));

    // 그사이 다른 기기에서 다른 제목으로 올렸다
    status = 'uploaded';
    upload = { ...uploadReply, title: '다른 기기 제목', status: 'uploaded', videoId: 'v2' };
    act(() => result.current.refresh());
    await vi.waitFor(() => expect(result.current.clips[0]?.entry?.status).toBe('uploaded'));
    expect(result.current.clips[0]?.title).toBe('다른 기기 제목');
  });

  it('영상 출력이 여럿이면 편집기가 고친 세로 출력을 올리고 틀고 받는다 — 첫 번째가 아니라', async () => {
    const twoOutputs = {
      ...rendered,
      outputs: [
        { outputId: 'sq', kind: 'video', s3Key: 'k1' },
        { outputId: 'v', kind: 'video', s3Key: 'k2' },
      ],
    };
    const spy = stubFetch((url, init) => {
      if (init?.method === 'POST' && url.endsWith('/uploads'))
        return jsonResponse(201, uploadReply);
      if (init?.method === 'POST') {
        return jsonResponse(200, {
          clipId: 5,
          expiresAt: '2026-09-20T13:00:00Z',
          files: [
            { outputId: 'sq', kind: 'video', fileName: 'sq.mp4', url: 'https://s3.example/sq.mp4' },
            { outputId: 'v', kind: 'video', fileName: 'v.mp4', url: 'https://s3.example/v.mp4' },
          ],
        });
      }
      if (url === '/api/clip/library/12') {
        return jsonResponse(200, {
          ...libraryEntry,
          latestClip: twoOutputs,
          recipe: {
            outputs: [
              { outputId: 'sq', aspect: 'SQUARE_1_1', layers: [] },
              { outputId: 'v', aspect: 'VERT_9_16', layers: [] },
            ],
          },
        });
      }
      return url.startsWith('/api/clip/library')
        ? jsonResponse(200, {
            items: [{ ...libraryEntry, latestClip: twoOutputs }],
            nextCursor: null,
          })
        : jsonResponse(200, { id: 9 });
    });
    const { result } = await mount();

    let url: string | null = null;
    await act(async () => {
      url = await result.current.previewUrl('12');
    });
    expect(url).toBe('https://s3.example/v.mp4');

    act(() => result.current.upload('12'));
    await vi.waitFor(() => expect(result.current.sendingIds.size).toBe(0));
    const upload = spy.mock.calls.find(
      ([u, init]) => init?.method === 'POST' && String(u).endsWith('/uploads'),
    );
    expect(JSON.parse(String(upload?.[1]?.body))).toEqual({ title: '편집본 #12', outputId: 'v' });
  });

  it('주문 답이 늦게 와도, 그사이 읽은 더 새 상태(올림)를 올리는 중으로 되돌리지 않는다', async () => {
    let answer: (r: Response) => void = () => {};
    let listStatus = 'rendered';
    let listUpload: typeof uploadReply | null = null;
    stubFetch((url, init) => {
      if (init?.method === 'POST') return new Promise<Response>((resolve) => (answer = resolve));
      return url.startsWith('/api/clip/library')
        ? jsonResponse(200, {
            items: [
              {
                ...libraryEntry,
                status: listStatus,
                latestClip: { ...rendered, upload: listUpload },
              },
            ],
            nextCursor: null,
          })
        : jsonResponse(200, { id: 9 });
    });
    const { result } = await mount();

    act(() => result.current.upload('12'));
    listStatus = 'uploaded';
    listUpload = { ...uploadReply, status: 'uploaded', videoId: 'v1' };
    act(() => result.current.refresh());
    await vi.waitFor(() => expect(result.current.clips[0]?.entry?.status).toBe('uploaded'));

    await act(async () => answer(jsonResponse(201, uploadReply)));
    await vi.waitFor(() => expect(result.current.sendingIds.size).toBe(0));
    expect(result.current.clips[0]?.entry?.status).toBe('uploaded');
  });

  it('주문 답이 늦게 왔는데 그사이 다른 영상이 최신이 됐으면 옛 업로드를 새 영상에 붙이지 않는다', async () => {
    let answer: (r: Response) => void = () => {};
    let latest = rendered;
    stubFetch((url, init) => {
      if (init?.method === 'POST') return new Promise<Response>((resolve) => (answer = resolve));
      return url.startsWith('/api/clip/library')
        ? jsonResponse(200, { items: [{ ...libraryEntry, latestClip: latest }], nextCursor: null })
        : jsonResponse(200, { id: 9 });
    });
    const { result } = await mount();

    act(() => result.current.upload('12'));
    latest = { ...rendered, id: 6 };
    act(() => result.current.refresh());
    await vi.waitFor(() => expect(result.current.clips[0]?.entry?.latestClip?.id).toBe(6));

    await act(async () => answer(jsonResponse(201, uploadReply)));
    await vi.waitFor(() => expect(result.current.sendingIds.size).toBe(0));
    expect(result.current.clips[0]?.entry?.latestClip?.upload).toBeNull();
    expect(result.current.clips[0]?.entry?.status).toBe('rendered');
  });

  it('규칙에 안 맞는 제목은 보내지 않는다', async () => {
    const spy = serve(() => jsonResponse(201, uploadReply));
    const { result } = await mount();

    act(() => result.current.renameClip('12', 'a <b>'));
    act(() => result.current.upload('12'));

    expect(posts(spy)).toHaveLength(0);
    expect(result.current.sendingIds.size).toBe(0);
  });

  it('거절되면 상태를 안 바꾸고 다시 누를 수 있다', async () => {
    const spy = serve(() => jsonResponse(503, { code: 'upload_unavailable' }));
    const { result } = await mount();

    act(() => result.current.upload('12'));
    await vi.waitFor(() => expect(result.current.sendingIds.size).toBe(0));
    expect(result.current.clips[0]?.entry?.status).toBe('rendered');

    act(() => result.current.upload('12'));
    await vi.waitFor(() => expect(posts(spy)).toHaveLength(2));
  });

  it('고친 제목은 다시 읽어도 남는다 — 올리기 전 초안이다', async () => {
    serve(() => jsonResponse(201, uploadReply));
    const { result } = await mount();

    act(() => result.current.renameClip('12', '초안 제목'));
    act(() => result.current.refresh());
    await vi.waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {});
    expect(result.current.clips[0]?.title).toBe('초안 제목');
  });

  it('미리보기·내려받기는 영상 파일 주소를 그때 받는다', async () => {
    const spy = serve((url) =>
      url.endsWith('/file-access')
        ? jsonResponse(200, {
            clipId: 5,
            expiresAt: '2026-09-20T13:00:00Z',
            files: [
              { outputId: 'o1', kind: 'srt', fileName: 'a.srt', url: 'https://s3.example/a.srt' },
              { outputId: 'o1', kind: 'video', fileName: 'a.mp4', url: 'https://s3.example/a.mp4' },
            ],
          })
        : jsonResponse(500),
    );
    const { result } = await mount();

    await expect(result.current.previewUrl('12')).resolves.toBe('https://s3.example/a.mp4');
    expect(posts(spy)[0]?.[0]).toBe('/api/clip/broadcasts/s%201/clips/5/file-access');

    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    act(() => result.current.download('12'));
    await vi.waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    expect((click.mock.contexts[0] as HTMLAnchorElement).href).toBe('https://s3.example/a.mp4');
    click.mockRestore();
  });

  it('영상이 없으면 주소를 묻지 않는다', async () => {
    libraryEntry.latestClip = null as unknown as typeof rendered;
    const spy = serve(() => jsonResponse(500));
    try {
      const { result } = await mount();
      await expect(result.current.previewUrl('12')).resolves.toBeNull();
      expect(posts(spy)).toHaveLength(0);
    } finally {
      libraryEntry.latestClip = rendered;
    }
  });
});
