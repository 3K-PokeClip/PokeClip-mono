import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSession, SessionOptions } from '@/features/player/usePlaybackSession';
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
    startedAt: null,
    endedAt: null,
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
    rerender(input({ startedAt: Date.now() }));
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

// 화면 갈래(POK-546 설계 3절 「라이브 · B 가 모두 notPast 인 근거」 표 다섯 줄 + A). 무변경 대조와 새 칸을 서로 다른 it 로 둔다 —
// 무변경 it 는 develop 에도 있는 관측(recordedRequest · session · canOpen)만 보고, 새 칸 enteredAfterEnd 는 새 동작 it 만 본다.
// A(끝난 뒤 들어온 화면)의 recordedRequest 는 여기서 보지 않는다 — 다음 커밋에서 녹화 조회를 떼어 null 로 바뀌는 임시 값이다.
describe('useLiveSession: 끝난 뒤 들어온 화면 판정(POK-546 T3-6)', () => {
  const STARTED_AT = Date.parse('2026-11-04T01:00:00.000Z');
  const ENDED_AT = Date.parse('2026-11-04T02:00:00.000Z');
  const OBSERVED_END = Date.parse('2026-11-04T01:59:30.000Z');

  function openedSession(): PlaybackSession {
    return { view: { banner: null }, dispatch: vi.fn() } as unknown as PlaybackSession;
  }

  /** 명부 live 로 세션을 연 화면을 세운다 */
  function mountOpened() {
    hoisted.session = openedSession();
    return renderHook((props: LiveSessionInput) => useLiveSession(props), {
      initialProps: input({ startedAt: STARTED_AT }),
    });
  }

  /** 세션을 연 화면이 녹화로 닫힌다(세션 훅이 recorded 로 닫고 null 을 돌려줌) */
  function closeRecorded(
    view: ReturnType<typeof mountOpened>,
    roster: LiveSessionInput['broadcastStatus'],
  ) {
    act(() => {
      hoisted.options!.onClose({
        reason: 'recorded',
        accessKey: 'r-A|key-1',
        observedEndMs: OBSERVED_END,
      });
    });
    hoisted.session = null;
    view.rerender(
      input({
        startedAt: STARTED_AT,
        broadcastStatus: roster,
        endedAt: roster === 'ended' ? ENDED_AT : null,
      }),
    );
    return view;
  }

  const recorded = { fromMs: STARTED_AT, untilMs: OBSERVED_END };

  describe('무변경 대조 — 라이브 · B · 명부 상태 모름의 지금 관측', () => {
    it('라이브(명부 live · 세션 열림): 세션을 그대로 내고 녹화를 묻지 않는다', () => {
      const view = mountOpened();
      expect(view.result.current.session).toBe(hoisted.session);
      expect(view.result.current.recordedRequest).toBeNull();
    });

    it.each(['unknown', 'offline'] as const)(
      '명부 상태 모름 · 꺼짐(%s): 세션을 열지 않는다',
      (roster) => {
        const view = renderHook(() => useLiveSession(input({ broadcastStatus: roster })));
        expect(hoisted.options!.canOpen).toBe(false);
        expect(view.result.current.session).toBeNull();
      },
    );

    it('B — 세션이 열린 채 명부 ended: 세션을 그대로 두고 녹화를 묻지 않는다', () => {
      const view = mountOpened();
      view.rerender(input({ startedAt: STARTED_AT, broadcastStatus: 'ended', endedAt: ENDED_AT }));
      expect(view.result.current.session).toBe(hoisted.session);
      expect(view.result.current.recordedRequest).toBeNull();
    });

    it('B — 세션이 녹화로 닫힌 뒤 명부 ended: 닫힐 때 고정한 녹화 조회를 그대로 낸다', () => {
      const view = closeRecorded(mountOpened(), 'ended');
      expect(view.result.current.session).toBeNull();
      expect(view.result.current.closedRecorded).toBe(true);
      expect(view.result.current.recordedRequest).toEqual(recorded);
    });

    it('녹화 재생 중(세션이 녹화로 닫힘 · 명부는 아직 live): 같은 키로 세션을 다시 열지 않고 녹화 조회를 낸다', () => {
      const view = closeRecorded(mountOpened(), 'live');
      expect(hoisted.options!.canOpen).toBe(false);
      expect(view.result.current.recordedRequest).toEqual(recorded);
    });
  });

  describe('새 칸 enteredAfterEnd', () => {
    it('라이브(명부 live · 세션 열림)는 거짓이다', () => {
      expect(mountOpened().result.current.enteredAfterEnd).toBe(false);
    });

    it.each(['unknown', 'offline'] as const)('명부 상태 모름 · 꺼짐(%s)은 거짓이다', (roster) => {
      const view = renderHook(() => useLiveSession(input({ broadcastStatus: roster })));
      expect(view.result.current.enteredAfterEnd).toBe(false);
    });

    it('B — 세션이 열린 채 명부 ended 는 거짓이다(명부 ended 는 세션을 닫지 않는다)', () => {
      const view = mountOpened();
      view.rerender(input({ startedAt: STARTED_AT, broadcastStatus: 'ended', endedAt: ENDED_AT }));
      expect(view.result.current.enteredAfterEnd).toBe(false);
    });

    it('B — 세션이 녹화로 닫힌 뒤 명부 ended 는 거짓이다(세션을 연 키는 닫혀도 남는다)', () => {
      expect(closeRecorded(mountOpened(), 'ended').result.current.enteredAfterEnd).toBe(false);
    });

    it('녹화 재생 중(명부는 아직 live)은 거짓이다', () => {
      expect(closeRecorded(mountOpened(), 'live').result.current.enteredAfterEnd).toBe(false);
    });

    it('A — 세션을 연 적 없이 명부 ended 면 참이다', () => {
      const view = renderHook(() =>
        useLiveSession(
          input({ startedAt: STARTED_AT, broadcastStatus: 'ended', endedAt: ENDED_AT }),
        ),
      );
      expect(view.result.current.enteredAfterEnd).toBe(true);
    });

    it('A — 세션을 연 적 있는 키와 다른 방송|영상 키로 명부 ended 를 받으면 참이다', () => {
      const view = mountOpened();
      hoisted.session = null;
      view.rerender(
        input({
          liveStreamId: 'r-B',
          accessKey: 'r-B|key-1',
          broadcastStatus: 'ended',
          endedAt: ENDED_AT,
        }),
      );
      expect(view.result.current.enteredAfterEnd).toBe(true);
    });
  });
});
