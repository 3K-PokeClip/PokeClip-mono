import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionClose } from '@/features/player/rewindSwitch';
import type {
  PlaybackSession,
  SessionClosed,
  SessionOptions,
} from '@/features/player/usePlaybackSession';
import { useLiveSession, type LiveSessionInput } from './useLiveSession';

// useLiveSession 의 배선(cc c10 L2) — 세션에 넘기는 지금 방송 번호 묻기가 최신 방송|영상 키를 싣고, 그 기억이 렌더를 넘어 산다.
// 세션 훅 · 라우터 · 방송 조회는 흉내 낸다: 시험은 세션 훅이 받은 resolveX 를 직접 부른다.

const hoisted = vi.hoisted(() => ({
  search: '',
  options: null as SessionOptions | null,
  session: null as PlaybackSession | null,
  query: vi.fn(async () => 'r-A' as string | null),
  toast: vi.fn((options: { title: string }) => `toast:${options.title}`),
  dismiss: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/broadcast/livenow',
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));
vi.mock('@/features/channel/ChannelScope', () => ({ useChannelStreamerId: () => 'streamer-1' }));
vi.mock('@/features/broadcast/streamSelection', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/broadcast/streamSelection')>()),
  resolveLiveStreamId: hoisted.query,
}));
vi.mock('@/features/player/usePlaybackSession', () => ({
  usePlaybackSession: (options: SessionOptions) => {
    hoisted.options = options;
    return hoisted.session;
  },
}));
vi.mock('@/ui', () => ({ useToast: () => ({ toast: hoisted.toast, dismiss: hoisted.dismiss }) }));

function input(patch: Partial<LiveSessionInput> = {}): LiveSessionInput {
  return {
    liveStreamId: 'r-A',
    mediaKey: 'key-1',
    accessKey: 'r-A|key-1',
    accessReady: true,
    broadcastStatus: 'live',
    liveUrl: 'https://m/live/key-1/index.m3u8',
    reselect: { deferred: null, latest: 'r-A' },
    ...patch,
  };
}

/** 세션 훅이 받은 지금 방송 번호 묻기(가장 최근 렌더의 것) */
function resolveX(fresh: boolean) {
  const options = hoisted.options;
  if (options === null) throw new Error('세션 훅이 불리지 않았다');
  return options.resolveX(fresh);
}

beforeEach(() => {
  hoisted.search = '';
  hoisted.options = null;
  hoisted.session = null;
  hoisted.query.mockClear();
  hoisted.toast.mockClear();
  hoisted.dismiss.mockClear();
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('useLiveSession: 고정 화면의 지금 방송 번호 묻기 배선(cc c10 L2)', () => {
  it('기억은 렌더를 넘어 살고(같은 키면 다시 묻지 않음), 방송|영상 키가 바뀌면 그 키로 새로 묻는다', async () => {
    hoisted.search = '?stream=r-A';
    const { rerender } = renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input(),
    });
    await expect(resolveX(false)).resolves.toEqual({ kind: 'known', id: 'r-A' });
    expect(hoisted.query).toHaveBeenCalledTimes(1);

    // 같은 채널 · 같은 키의 다른 렌더 — 기억(10초)을 다시 쓴다
    rerender(input({ accessReady: false }));
    await resolveX(false);
    expect(hoisted.query).toHaveBeenCalledTimes(1);

    // 다른 방송|영상 키 — 앞 키의 기억을 쓰지 않는다
    rerender(input({ liveStreamId: 'r-B', accessKey: 'r-B|key-1' }));
    await resolveX(false);
    expect(hoisted.query).toHaveBeenCalledTimes(2);
  });
});

describe('useLiveSession: 안내 거두기(넘김 되돌림 — 실측 M4)', () => {
  it('거둘 안내가 떠 있으면 그 토스트를 닫고, 띄운 적 없거나 이미 거둔 안내는 닫지 않는다', () => {
    renderHook(() => useLiveSession(input()));
    const options = hoisted.options!;
    options.onWithdrawNotice?.('라이브로 이어서 볼게요');
    expect(hoisted.dismiss).not.toHaveBeenCalled();

    options.onNotice?.('라이브로 이어서 볼게요');
    options.onNotice?.('다른 안내');
    options.onWithdrawNotice?.('라이브로 이어서 볼게요');
    expect(hoisted.dismiss).toHaveBeenCalledTimes(1);
    expect(hoisted.dismiss).toHaveBeenCalledWith('toast:라이브로 이어서 볼게요');

    options.onWithdrawNotice?.('라이브로 이어서 볼게요');
    expect(hoisted.dismiss).toHaveBeenCalledTimes(1);
  });
});

describe('useLiveSession: 띠는 하나만(cc L)', () => {
  function sessionWithBanner(banner: { goTo: string } | null): PlaybackSession {
    return {
      view: { banner },
      dispatch: vi.fn(),
    } as unknown as PlaybackSession;
  }

  it('세션 덮개가 다른 방송 띠를 세웠으면 화면의 「새 방송」 띠는 서지 않는다 — 없으면 선다', () => {
    const reselect = { deferred: 'r-B', latest: 'r-B' };
    hoisted.session = sessionWithBanner(null);
    const plain = renderHook(() => useLiveSession(input({ reselect })));
    expect(plain.result.current.band).toBe('r-B');
    plain.unmount();

    hoisted.session = sessionWithBanner({ goTo: 'r-B' });
    const covered = renderHook(() => useLiveSession(input({ reselect })));
    expect(covered.result.current.band).toBeNull();
  });
});

// 화면 갈래(POK-546 설계 3절 「라이브 · B 가 모두 notPast 인 근거」 표 다섯 줄 + A · POK-547 T3). 무변경 대조와 새 칸을 서로
// 다른 it 로 둔다 — 무변경 it 는 develop 에도 있는 관측(session · canOpen)만 보고, 지난 방송 판정 칸(showsPast · closedToPast ·
// pastSinceMs)은 새 동작 it 가 본다.
describe('useLiveSession: 지난 방송 판정으로 그리는 화면(POK-546 T3-6 · POK-547 T3)', () => {
  /** 끝남 대기를 연 시각(세션 endingSince) */
  const ENDING_SINCE = Date.parse('2026-11-04T01:59:30.000Z');
  /** 단추 없이 끝에 닿아 닫힌 past 의 넘김 — 넘길 자리 · 다시보기 없음 */
  const NO_HANDOFF: SessionClosed['handoff'] = {
    resumeAtMs: null,
    seenVod: null,
    chosen: false,
    sound: null,
  };

  function openedSession(): PlaybackSession {
    return { view: { banner: null }, dispatch: vi.fn() } as unknown as PlaybackSession;
  }

  /** 명부 live 로 세션을 연 화면을 세운다 */
  function mountOpened(patch: Partial<LiveSessionInput> = {}) {
    hoisted.session = openedSession();
    return renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input(patch),
    });
  }

  /** 세션을 연 화면이 그 사유로 닫힌다(세션 훅이 닫고 null 을 돌려줌) */
  function closeAs(
    view: ReturnType<typeof mountOpened>,
    reason: SessionClose,
    roster: LiveSessionInput['broadcastStatus'],
    patch: Partial<LiveSessionInput> = {},
    handoff: SessionClosed['handoff'] = reason === 'past' ? NO_HANDOFF : null,
  ) {
    act(() => {
      hoisted.options!.onClose({
        reason,
        accessKey: 'r-A|key-1',
        endingSinceMs: ENDING_SINCE,
        handoff,
      });
    });
    hoisted.session = null;
    view.rerender(input({ broadcastStatus: roster, ...patch }));
    return view;
  }

  describe('무변경 대조 — 라이브 · B · 명부 상태 모름의 지금 관측', () => {
    it('라이브(명부 live · 세션 열림): 세션을 그대로 낸다', () => {
      const view = mountOpened();
      expect(view.result.current.session).toBe(hoisted.session);
    });

    it.each(['unknown', 'offline'] as const)(
      '명부 상태 모름 · 꺼짐(%s): 세션을 열지 않는다',
      (roster) => {
        const view = renderHook(() => useLiveSession(input({ broadcastStatus: roster })));
        expect(hoisted.options!.canOpen).toBe(false);
        expect(view.result.current.session).toBeNull();
      },
    );

    it('B — 세션이 열린 채 명부 ended: 세션을 그대로 둔다', () => {
      const view = mountOpened();
      view.rerender(input({ broadcastStatus: 'ended' }));
      expect(view.result.current.session).toBe(hoisted.session);
    });
  });

  describe('지난 방송 판정 칸 showsPast · closedToPast · pastSinceMs', () => {
    it('라이브(명부 live · 세션 열림)는 거짓이다', () => {
      const { current } = mountOpened().result;
      expect(current.showsPast).toBe(false);
      expect(current.closedToPast).toBe(false);
      expect(current.pastSinceMs).toBeNull();
    });

    it.each(['unknown', 'offline'] as const)('명부 상태 모름 · 꺼짐(%s)은 거짓이다', (roster) => {
      const view = renderHook(() => useLiveSession(input({ broadcastStatus: roster })));
      expect(view.result.current.showsPast).toBe(false);
    });

    it('B — 세션이 열린 채 명부 ended 는 거짓이다(명부 ended 는 세션을 닫지 않는다)', () => {
      const view = mountOpened();
      view.rerender(input({ broadcastStatus: 'ended' }));
      expect(view.result.current.showsPast).toBe(false);
      expect(view.result.current.pastSinceMs).toBeNull();
    });

    it.each(['live', 'ended'] as const)(
      'T3-1 past 로 닫힌 화면(명부 %s)은 참이다 · 원점 폴백 = 끝남 대기를 연 시각 · 같은 키로 다시 열지 않는다',
      (roster) => {
        const view = closeAs(mountOpened(), 'past', roster);
        expect(view.result.current.session).toBeNull();
        expect(view.result.current.showsPast).toBe(true);
        expect(view.result.current.closedToPast).toBe(true);
        expect(view.result.current.pastSinceMs).toBe(ENDING_SINCE);
        expect(hoisted.options!.canOpen).toBe(false);
      },
    );

    it('T3-2 past 로 닫힌 화면도 재생을 보유한다 — 같은 채널 새 방송은 「새 방송」 띠다', () => {
      const reselect = { deferred: 'r-B', latest: 'r-B' };
      const view = closeAs(mountOpened({ reselect }), 'past', 'live', { reselect });
      expect(view.result.current.band).toBe('r-B');
    });

    it.each(['go-live', 'left'] as const)(
      'T3-3 %s 로 닫힌 화면은 명부 ended 뒤에도 거짓이다(세션을 연 키다)',
      (reason) => {
        const view = closeAs(mountOpened(), reason, 'live');
        expect(view.result.current.showsPast).toBe(false);
        expect(view.result.current.closedToPast).toBe(false);
        view.rerender(input({ broadcastStatus: 'ended' }));
        expect(view.result.current.showsPast).toBe(false);
        expect(view.result.current.pastSinceMs).toBeNull();
      },
    );

    it('A — 세션을 연 적 없이 명부 ended 면 참이다 · 원점 폴백은 없다', () => {
      const view = renderHook(() => useLiveSession(input({ broadcastStatus: 'ended' })));
      expect(view.result.current.showsPast).toBe(true);
      expect(view.result.current.closedToPast).toBe(false);
      expect(view.result.current.pastSinceMs).toBeNull();
    });

    it('A — 세션을 연 적 있는 키와 다른 방송|영상 키로 명부 ended 를 받으면 참이다', () => {
      const view = mountOpened();
      hoisted.session = null;
      view.rerender(
        input({
          liveStreamId: 'r-B',
          accessKey: 'r-B|key-1',
          broadcastStatus: 'ended',
        }),
      );
      expect(view.result.current.showsPast).toBe(true);
    });

    it('past 로 닫힌 키와 다른 방송|영상 키는 past 닫힘을 이어받지 않는다', () => {
      const view = closeAs(mountOpened(), 'past', 'live');
      view.rerender(input({ liveStreamId: 'r-B', accessKey: 'r-B|key-1' }));
      expect(view.result.current.closedToPast).toBe(false);
      expect(view.result.current.pastSinceMs).toBeNull();
      expect(hoisted.options!.canOpen).toBe(true);
    });
  });
});

describe('useLiveSession: past 닫힘이 넘긴 자리 · 다시보기 · 받아 둔 카드(POK-547)', () => {
  const VOD = { url: 'https://m/vod/key-1/r-A/index.m3u8', firstPdtMs: 1_000, headPdtMs: 9_000 };
  const SPOT_MS = 5_000;
  /** 세션 재생기에서 소리를 켜고 음량 30 으로 보다 닫혔다 */
  const SOUND = { muted: false, volume: 30 };

  function mountWith(onChosenPastClose = vi.fn()) {
    hoisted.session = { view: { banner: null }, dispatch: vi.fn() } as unknown as PlaybackSession;
    const view = renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input({ onChosenPastClose }),
    });
    return { view, onChosenPastClose };
  }

  function close(
    view: ReturnType<typeof mountWith>['view'],
    handoff: SessionClosed['handoff'],
    reason: SessionClose = 'past',
  ) {
    act(() => {
      hoisted.options!.onClose({ reason, accessKey: 'r-A|key-1', endingSinceMs: null, handoff });
    });
    hoisted.session = null;
    view.rerender(input());
  }

  it('단추로 닫으면 넘긴 자리 · 다시보기 · 소리를 내고 같은 닫힘 호출 안에서 받아 둔 카드를 비우게 한다', () => {
    const { view, onChosenPastClose } = mountWith();
    act(() => {
      hoisted.options!.onClose({
        reason: 'past',
        accessKey: 'r-A|key-1',
        endingSinceMs: null,
        handoff: { resumeAtMs: SPOT_MS, seenVod: VOD, chosen: true, sound: SOUND },
      });
      expect(onChosenPastClose).toHaveBeenCalledTimes(1);
    });
    hoisted.session = null;
    view.rerender(input({ onChosenPastClose }));
    expect(view.result.current.closedToPast).toBe(true);
    expect(view.result.current.pastResumeAtMs).toBe(SPOT_MS);
    expect(view.result.current.pastSeenVod).toEqual(VOD);
    expect(view.result.current.vodSound).toEqual(SOUND);
    expect(onChosenPastClose).toHaveBeenCalledTimes(1);
  });

  it('단추 없는 past 닫힘은 받아 둔 카드를 두고 넘긴 자리만 낸다', () => {
    const { view, onChosenPastClose } = mountWith();
    close(view, { resumeAtMs: SPOT_MS, seenVod: null, chosen: false, sound: null });
    expect(onChosenPastClose).not.toHaveBeenCalled();
    expect(view.result.current.pastResumeAtMs).toBe(SPOT_MS);
    expect(view.result.current.pastSeenVod).toBeNull();
  });

  it('넘김 자리를 소모하면 자리만 비우고 닫힘 기록 · 다시보기 · 소리는 둔다', () => {
    const { view } = mountWith();
    close(view, { resumeAtMs: SPOT_MS, seenVod: VOD, chosen: true, sound: SOUND });
    act(() => view.result.current.consumePastResume());
    expect(view.result.current.pastResumeAtMs).toBeNull();
    expect(view.result.current.pastSeenVod).toEqual(VOD);
    expect(view.result.current.vodSound).toEqual(SOUND);
    expect(view.result.current.closedToPast).toBe(true);
    expect(hoisted.options!.canOpen).toBe(false);
  });

  it('다른 방송|영상 키는 넘긴 자리 · 다시보기 · 소리를 이어받지 않는다', () => {
    const { view } = mountWith();
    close(view, { resumeAtMs: SPOT_MS, seenVod: VOD, chosen: true, sound: SOUND });
    view.rerender(input({ liveStreamId: 'r-B', accessKey: 'r-B|key-1' }));
    expect(view.result.current.pastResumeAtMs).toBeNull();
    expect(view.result.current.pastSeenVod).toBeNull();
    expect(view.result.current.vodSound).toBeNull();
  });

  it.each(['go-live', 'left'] as const)('%s 닫힘은 카드를 비우지 않고 넘길 것도 없다', (reason) => {
    const { view, onChosenPastClose } = mountWith();
    close(view, null, reason);
    expect(onChosenPastClose).not.toHaveBeenCalled();
    expect(view.result.current.pastResumeAtMs).toBeNull();
    expect(view.result.current.pastSeenVod).toBeNull();
  });

  it('세션이 열린 동안 소모는 아무것도 바꾸지 않는다', () => {
    const { view } = mountWith();
    const before = view.result.current;
    act(() => before.consumePastResume());
    expect(view.result.current.pastResumeAtMs).toBeNull();
    expect(view.result.current.closedToPast).toBe(false);
  });
});

describe('useLiveSession: 다시보기 재생기에서 바꾼 소리(POK-547)', () => {
  /** 세션 재생기에서 소리를 켜고 음량 30 으로 보다 닫혔다 */
  const HANDED = { muted: false, volume: 30 };
  /** 다시보기 재생기에서 음소거했다(음량은 그대로) */
  const MUTED = { muted: true, volume: 30 };

  function closedWith(sound: typeof HANDED) {
    hoisted.session = { view: { banner: null }, dispatch: vi.fn() } as unknown as PlaybackSession;
    const view = renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input(),
    });
    act(() => {
      hoisted.options!.onClose({
        reason: 'past',
        accessKey: 'r-A|key-1',
        endingSinceMs: null,
        handoff: { resumeAtMs: null, seenVod: null, chosen: true, sound },
      });
    });
    hoisted.session = null;
    view.rerender(input());
    return view;
  }

  it('past 로 닫힌 뒤 다시보기 재생기에서 바꾼 소리가 넘긴 소리를 갈음한다', () => {
    const view = closedWith(HANDED);
    act(() => view.result.current.rememberVodSound(MUTED));
    expect(view.result.current.vodSound).toEqual(MUTED);
  });

  it('끝난 뒤 들어온 화면은 처음에 소리가 없고(기본값), 다시보기 재생기에서 바꾸면 그 값이다', () => {
    const view = renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input({ broadcastStatus: 'ended' }),
    });
    expect(view.result.current.vodSound).toBeNull();
    act(() => view.result.current.rememberVodSound(HANDED));
    expect(view.result.current.vodSound).toEqual(HANDED);
  });

  it('바꾼 소리는 그 방송|영상 키에서만 낸다 — 다른 키의 첫 다시보기는 기본값이다', () => {
    const view = renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input({ broadcastStatus: 'ended' }),
    });
    act(() => view.result.current.rememberVodSound(HANDED));
    view.rerender(input({ liveStreamId: 'r-B', accessKey: 'r-B|key-1', broadcastStatus: 'ended' }));
    expect(view.result.current.vodSound).toBeNull();
  });

  it('넘김 자리를 소모해도 바꾼 소리는 남는다', () => {
    const view = closedWith(HANDED);
    act(() => view.result.current.rememberVodSound(MUTED));
    act(() => view.result.current.consumePastResume());
    expect(view.result.current.vodSound).toEqual(MUTED);
  });
});
