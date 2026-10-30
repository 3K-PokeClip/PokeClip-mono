import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useEditorVideoPlayback } from './useEditorVideoPlayback';

// 편집기 미리보기 창은 녹화 구간 하나 안에 둔다(POK-253). 재생 서버는 틈 안에서 시작하는 요청에 404다(실측).

const BASE = Date.parse('2026-09-30T01:50:00Z');
const PIECES = [
  { fromSeconds: 0, toSeconds: 14 },
  { fromSeconds: 24, toSeconds: 38 },
];

function requestedWindow(fetchSpy: ReturnType<typeof vi.fn>) {
  const url = new URL(String(fetchSpy.mock.calls[0]![0]), 'http://localhost');
  const fromSeconds = (Date.parse(url.searchParams.get('start')!) - BASE) / 1000;
  return { fromSeconds, toSeconds: fromSeconds + Number(url.searchParams.get('duration')) };
}

describe('useEditorVideoPlayback — 녹화 구간', () => {
  let fetchSpy: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    fetchSpy = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetchSpy);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('재접속 직후 카드는 앞 여유를 틈까지 늘리지 않는다 — 틈에서 시작하면 미리보기가 통째로 실패했다', () => {
    renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 26, endSeconds: 30 },
      }),
    );
    expect(requestedWindow(fetchSpy)).toEqual({ fromSeconds: 24, toSeconds: 38 });
  });

  it('틈 앞 구간의 카드는 틈 앞에서 끝난다 — 틈을 건너 달라 하지 않는다', () => {
    renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 5, endSeconds: 10 },
      }),
    );
    expect(requestedWindow(fetchSpy)).toEqual({ fromSeconds: 0, toSeconds: 14 });
  });

  it('구간이 통째로 틈 안이면 받지 않고 미리보기 실패로 알린다 — 엉뚱한 녹화를 보이지 않는다(PR #205 codex)', () => {
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 114,
        pieces: [
          { fromSeconds: 0, toSeconds: 14 },
          { fromSeconds: 100, toSeconds: 114 },
        ],
        initialRange: { startSeconds: 50, endSeconds: 60 },
      }),
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    // 실패 이유가 「녹화 없음」이라 화면이 그대로 알린다(POK-389)
    expect(result.current.playback.error).toBe('no-recording');
  });

  it('구간은 틈 안인데 여유만 다음 녹화에 닿아도 받지 않는다 — 고른 장면이 아닌 영상이다(PR #205 codex)', () => {
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 15, endSeconds: 16 },
      }),
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    // 실패 이유가 「녹화 없음」이라 화면이 그대로 알린다(POK-389)
    expect(result.current.playback.error).toBe('no-recording');
  });

  it('구간 목록이 없으면 전처럼 앞뒤 10초 여유다', () => {
    renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 100,
        initialRange: { startSeconds: 30, endSeconds: 40 },
      }),
    );
    expect(requestedWindow(fetchSpy)).toEqual({ fromSeconds: 20, toSeconds: 50 });
  });
});

describe('useEditorVideoPlayback: 받은 창 알리기 (POK-389)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('창은 영상을 다 받은 뒤에 알린다: 받는 동안 알리면 옛 영상을 새 창 시각으로 읽어 미리 듣기·필름띠가 어긋난다(PR #341 codex)', async () => {
    let finish: (res: Response) => void = () => undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => (finish = resolve))),
    );
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }),
    );
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 5, endSeconds: 10 },
      }),
    );
    expect(result.current.window).toBeNull();
    // 받으려는 창은 처음부터 있다: 미리 듣기가 영상과 함께 조각을 받기 시작한다(PR #341 2차)
    expect(result.current.targetWindow).toEqual({ startSeconds: 0, endSeconds: 14 });

    finish(new Response('mp4', { status: 200 }));
    await waitFor(() => expect(result.current.src).toBe('blob:x'));
    expect(result.current.window).toEqual({ startSeconds: 0, endSeconds: 14 });
  });
});

describe('useEditorVideoPlayback: 창 다시 받기 예약 (POK-389)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('창 끝까지 끌었다가 곧 안쪽으로 돌리면 같은 창을 다시 받지 않는다(PR #341 codex)', () => {
    vi.useFakeTimers();
    const fetchSpy = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetchSpy);
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 100,
        initialRange: { startSeconds: 20, endSeconds: 30 },
      }),
    );
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    act(() => {
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 39.8, loop: true });
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 30, loop: true });
      vi.advanceTimersByTime(1_000);
    });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});

describe('useEditorVideoPlayback: 옛 영상 주소 지우기 (POK-389)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('새 창을 받는 동안에는 옛 영상 주소를 지우지 않고, 새 영상이 들어온 뒤에 지운다(PR #341 claude)', async () => {
    vi.useFakeTimers();
    const finishers: ((res: Response) => void)[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => finishers.push(resolve))),
    );
    let made = 0;
    const revoke = vi.fn();
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: () => `blob:${++made}`, revokeObjectURL: revoke }),
    );
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 100,
        initialRange: { startSeconds: 20, endSeconds: 30 },
      }),
    );
    await act(async () => {
      finishers[0]!(new Response('mp4', { status: 200 }));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.src).toBe('blob:1');

    // 구간을 창 끝 너머로 늘리면 새 창을 받는다. 받는 동안 <video>는 옛 주소를 그대로 쓴다
    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 45, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(finishers).toHaveLength(2);
    expect(result.current.src).toBe('blob:1');
    expect(revoke).not.toHaveBeenCalledWith('blob:1');

    await act(async () => {
      finishers[1]!(new Response('mp4', { status: 200 }));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.src).toBe('blob:2');
    expect(revoke).toHaveBeenCalledWith('blob:1');
    expect(revoke).not.toHaveBeenCalledWith('blob:2');
  });
});

describe('useEditorVideoPlayback: 새 창을 받는 동안의 시각 (POK-389)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('새 창을 받는 동안은 아직 도는 옛 영상의 창으로 시각을 옮기고, 새 영상이 들어오면 새 창으로 옮긴다(PR #341 claude)', async () => {
    vi.useFakeTimers();
    const finishers: ((res: Response) => void)[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => finishers.push(resolve))),
    );
    let made = 0;
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: () => `blob:${++made}`, revokeObjectURL: () => {} }),
    );
    const node = Object.assign(new EventTarget(), {
      currentTime: 0,
      paused: true,
      readyState: 0,
      playbackRate: 1,
    }) as unknown as HTMLVideoElement;
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 100,
        initialRange: { startSeconds: 20, endSeconds: 30 },
      }),
    );
    act(() => result.current.videoRef(node));
    // 첫 창: 구간 앞뒤 10초(10~40초)
    await act(async () => {
      finishers[0]!(new Response('mp4', { status: 200 }));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.window).toEqual({ startSeconds: 10, endSeconds: 40 });
    act(() => {
      node.currentTime = 15;
      node.dispatchEvent(new Event('timeupdate'));
    });
    expect(result.current.playback.currentSeconds).toBe(25);

    // 시작을 창 앞쪽 끝까지 당기면 새 창(0.2~40초)을 받는다. 받는 동안 <video>는 옛 창(10~40초) 영상 그대로다
    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 10.2, endSeconds: 30, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(finishers).toHaveLength(2);
    act(() => node.dispatchEvent(new Event('timeupdate')));
    expect(result.current.playback.currentSeconds).toBe(25);
    act(() => result.current.playback.seekTo(28));
    expect(node.currentTime).toBe(18);

    // 새 영상이 들어오면 새 창으로 옮기고, 구간 시작으로 간다
    await act(async () => {
      finishers[1]!(new Response('mp4', { status: 200 }));
      await vi.advanceTimersByTimeAsync(0);
    });
    act(() => node.dispatchEvent(new Event('loadedmetadata')));
    expect(node.currentTime).toBeCloseTo(10);
    expect(result.current.playback.currentSeconds).toBeCloseTo(10.2);
  });
});

describe('useEditorVideoPlayback: 구간 반복 (POK-490)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  /** 구간 20~30초, 창 10~40초를 받아 둔 영상 노드. duration은 받은 영상의 실제 길이(초) */
  async function loaded(duration: number) {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('mp4', { status: 200 }))),
    );
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: () => 'blob:1', revokeObjectURL: () => {} }),
    );
    const play = vi.fn(() => Promise.resolve());
    const node = Object.assign(new EventTarget(), {
      currentTime: 0,
      duration,
      paused: true,
      readyState: 0,
      playbackRate: 1,
      play,
      pause: vi.fn(),
    }) as unknown as HTMLVideoElement;
    const hook = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 100,
        initialRange: { startSeconds: 20, endSeconds: 30 },
      }),
    );
    act(() => hook.result.current.videoRef(node));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(hook.result.current.window).toEqual({ startSeconds: 10, endSeconds: 40 });
    return { ...hook, node, play };
  }

  const tick = (node: HTMLVideoElement, videoSeconds: number) =>
    act(() => {
      node.currentTime = videoSeconds;
      node.dispatchEvent(new Event('timeupdate'));
    });

  it('반복이 켜져 있으면 구간 끝에 닿을 때 구간 시작으로 되감는다', async () => {
    const { node } = await loaded(30);
    tick(node, 20.1); // 방송 30.1초 — 구간 끝을 지났다
    expect(node.currentTime).toBe(10); // 방송 20초 = 창 시작 10초 + 10
  });

  it('받은 영상이 구간 끝보다 짧으면 영상 끝에서 되감는다: 전에는 영상 끝에서 멈춰 반복이 안 걸렸다', async () => {
    // 영상은 15초(방송 10~25초)뿐이다 — 구간 끝 30초에 영영 닿지 않는다(녹화 끝·조각 끝에서 잘린 창)
    const { node } = await loaded(15);
    tick(node, 14.97); // 방송 24.97초 — 영상 끝 바로 앞
    expect(node.currentTime).toBe(10);
  });

  it('영상 끝(ended)이 먼저 와도 반복이면 구간 시작으로 되감아 이어 튼다', async () => {
    const { node, play, result } = await loaded(15);
    act(() => {
      node.currentTime = 15;
      node.dispatchEvent(new Event('ended'));
    });
    expect(node.currentTime).toBe(10);
    expect(play).toHaveBeenCalledTimes(1);
    expect(result.current.playback.error).toBeNull();
  });

  it('받은 영상이 구간 시작에도 못 닿으면 영상 끝에서 반복하지 않고 멈춘다: 구간 밖을 끝없이 돌지 않는다(PR #454 1차 리뷰)', async () => {
    // 영상은 8초(방송 10~18초)뿐 — 구간 20~30초가 통째로 영상 밖이다
    const { node, play, result } = await loaded(8);
    act(() => {
      node.dispatchEvent(new Event('play'));
      node.currentTime = 8;
      node.dispatchEvent(new Event('ended'));
    });
    expect(play).not.toHaveBeenCalled();
    expect(result.current.playback.playing).toBe(false);
  });

  it('받은 영상이 구간 시작 바로 뒤(경계 여유 안)에서 끝나도 멈춘다: 그 몇십 ms를 끝없이 되감지 않는다(PR #454 2차 리뷰)', async () => {
    // 영상은 10.03초(방송 10~20.03초) — 구간 시작 20초를 30ms 넘는다. 경계 틱은 이 구간을 그대로 둬 끝을 못 잡는다
    const { node, play, result } = await loaded(10.03);
    act(() => {
      node.dispatchEvent(new Event('play'));
      node.currentTime = 10.03;
      node.dispatchEvent(new Event('ended'));
    });
    expect(play).not.toHaveBeenCalled();
    expect(result.current.playback.playing).toBe(false);
  });

  it('반복이 꺼져 있으면 영상 끝에서 멈춘다', async () => {
    const { node, play, result } = await loaded(15);
    act(() => result.current.playback.setBounds({ startSeconds: 20, endSeconds: 30, loop: false }));
    act(() => {
      node.dispatchEvent(new Event('play'));
      node.currentTime = 15;
      node.dispatchEvent(new Event('ended'));
    });
    expect(play).not.toHaveBeenCalled();
    expect(result.current.playback.playing).toBe(false);
    expect(node.currentTime).toBe(15);
  });
});

describe('useEditorVideoPlayback: 끊겨 기다릴 때 (POK-439)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('재생 중 waiting 뒤 400ms가 지나면 알리고 playing에 내린다', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)),
    );
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 5, endSeconds: 10 },
      }),
    );
    const node = document.createElement('video');
    Object.defineProperty(node, 'paused', { value: false, configurable: true });
    act(() => result.current.videoRef(node));

    act(() => {
      node.dispatchEvent(new Event('waiting'));
      vi.advanceTimersByTime(399);
    });
    expect(result.current.buffering).toBe(false);

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current.buffering).toBe(true);

    act(() => {
      node.dispatchEvent(new Event('playing'));
    });
    expect(result.current.buffering).toBe(false);
  });
});

describe('useEditorVideoPlayback: 재생 거절 (POK-389)', () => {
  afterEach(() => vi.unstubAllGlobals());

  function videoNode(play: () => Promise<void>) {
    return Object.assign(document.createElement('video'), { play }) as HTMLVideoElement;
  }
  const props = {
    streamId: 'key-1',
    recordingStartMs: BASE,
    recordingSeconds: 38,
    pieces: PIECES,
    initialRange: { startSeconds: 5, endSeconds: 10 },
  };

  it('재생을 누르자마자 멈춰 play()가 AbortError로 거절돼도 오류로 알리지 않는다: 보통 조작이다(로컬 리뷰 4라운드)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)),
    );
    const { result } = renderHook(() => useEditorVideoPlayback(props));
    act(() =>
      result.current.videoRef(
        videoNode(() => Promise.reject(new DOMException('interrupted', 'AbortError'))),
      ),
    );
    await act(async () => {
      result.current.playback.togglePlay();
      await Promise.resolve();
    });
    expect(result.current.playback.error).toBeNull();
  });

  it('그 밖의 이유로 재생을 못 하면 「불러오지 못함」으로 알린다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)),
    );
    const { result } = renderHook(() => useEditorVideoPlayback(props));
    act(() =>
      result.current.videoRef(
        videoNode(() => Promise.reject(new DOMException('bad', 'NotSupportedError'))),
      ),
    );
    await act(async () => {
      result.current.playback.togglePlay();
      await Promise.resolve();
    });
    expect(result.current.playback.error).toBe('fatal');
  });
});

describe('useEditorVideoPlayback: 출입증 쿠키 (POK-317)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('출입증을 받기 전에는 쿠키를 안 싣고, 받은 뒤에는 싣는다: CDN 뒤 미리보기는 쿠키가 없으면 403이다', async () => {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'https://media.test');
    const fetchSpy = vi.fn((input: RequestInfo | URL, _init?: RequestInit) =>
      String(input).endsWith('/playback-access')
        ? Promise.resolve(
            new Response(JSON.stringify({ expiresAt: '2999-01-01T00:00:00Z' }), { status: 200 }),
          )
        : new Promise<Response>(() => undefined),
    );
    vi.stubGlobal('fetch', fetchSpy);
    const { ensurePlaybackAccess } = await import('@/api/playbackAccess');
    const { useEditorVideoPlayback: hook } = await import('./useEditorVideoPlayback');
    const props = {
      streamId: 'key-1',
      recordingStartMs: BASE,
      recordingSeconds: 38,
      pieces: PIECES,
      initialRange: { startSeconds: 5, endSeconds: 10 },
    };
    const credentialsOfLastClip = () =>
      fetchSpy.mock.calls.filter(([url]) => !String(url).endsWith('/playback-access')).at(-1)?.[1]
        ?.credentials;

    renderHook(() => hook(props)).unmount();
    expect(credentialsOfLastClip()).toBe('same-origin');

    await ensurePlaybackAccess('S-1', 'key-1');
    renderHook(() => hook(props));
    // 받아 둔 출입증이 쓸 만하면 기다리지 않고 바로 부른다
    expect(credentialsOfLastClip()).toBe('include');
    expect(
      fetchSpy.mock.calls.filter(([url]) => String(url).endsWith('/playback-access')),
    ).toHaveLength(1);
  });

  it('영상 조각이 403이면 쿠키가 지워진 것이라 출입증을 버리고 다시 받아 한 번 더 부른다(PR #332 codex)', async () => {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'https://media.test');
    let gets = 0;
    const fetchSpy = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      if (String(input).endsWith('/playback-access'))
        return Promise.resolve(
          new Response(JSON.stringify({ expiresAt: '2999-01-01T00:00:00Z' }), { status: 200 }),
        );
      gets += 1;
      return Promise.resolve(
        new Response(gets === 1 ? null : 'mp4', { status: gets === 1 ? 403 : 200 }),
      );
    });
    vi.stubGlobal('fetch', fetchSpy);
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }),
    );
    const { ensurePlaybackAccess } = await import('@/api/playbackAccess');
    const { useEditorVideoPlayback: hook } = await import('./useEditorVideoPlayback');
    await ensurePlaybackAccess('S-1', 'key-1');

    const { result } = renderHook(() =>
      hook({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 5, endSeconds: 10 },
      }),
    );
    await waitFor(() => expect(gets).toBe(2));
    expect(
      fetchSpy.mock.calls.filter(([url]) => String(url).endsWith('/playback-access')),
    ).toHaveLength(2);
    await waitFor(() => expect(result.current.playback.error).toBeNull());
  });

  it('출입증을 다시 받아도 403이면 「출입증 거절」로 알린다(POK-389)', async () => {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'https://media.test');
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input).endsWith('/playback-access')
            ? new Response(JSON.stringify({ expiresAt: '2999-01-01T00:00:00Z' }), { status: 200 })
            : new Response(null, { status: 403 }),
        ),
      ),
    );
    const { ensurePlaybackAccess } = await import('@/api/playbackAccess');
    const { useEditorVideoPlayback: hook } = await import('./useEditorVideoPlayback');
    await ensurePlaybackAccess('S-1', 'key-1');

    const { result } = renderHook(() =>
      hook({
        streamId: 'key-1',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 5, endSeconds: 10 },
      }),
    );
    await waitFor(() => expect(result.current.playback.error).toBe('access'));
  });
});
