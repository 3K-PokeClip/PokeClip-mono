import { StrictMode, useEffect, useRef } from 'react';
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { rebaseTimeline, recordingTimeline } from './recordingTimeline';

// 다시보기 오류가 쿠키 지워짐(403)이면 출입증을 다시 받아 같은 줄기를 다시 붙인다(PR #332 codex). 확인 자체는 playbackAccess 시험이 잰다
const access = vi.hoisted(() => ({
  recover: vi.fn((_url: string, _key: string) => Promise.resolve(false)),
}));
vi.mock('@/api/playbackAccess', () => ({ recoverForbiddenRecording: access.recover }));
const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/analytics')>()),
  track,
}));
import { useRecordedPlayback, type RecordedSource } from './useRecordedPlayback';
import { resetPlaybackStartsForTest } from './playbackStarts';

// 끝난 방송 다시보기(POK-253). 재생 서버는 틈을 건너 주지 않으므로(실측) 한 구간씩 달라 하고 이어 튼다.

const T0 = Date.parse('2026-09-30T01:50:00Z');

function source(spans: [offsetSeconds: number, durationSeconds: number][]): RecordedSource {
  const timeline = recordingTimeline(
    spans.map(([offset, duration]) => ({ startMs: T0 + offset * 1000, durationSeconds: duration })),
    null,
    null,
  )!;
  return { streamId: 'gaptest', ...timeline };
}

function fakeVideo() {
  const video = document.createElement('video');
  const play = vi.fn(() => Promise.resolve());
  Object.defineProperty(video, 'play', { value: play });
  return { video, play };
}

/** 요청한 줄기의 시작(첫 구간 기준 초)·길이 */
function requested(video: HTMLVideoElement) {
  const url = new URL(video.src, 'http://localhost');
  return {
    fromSeconds: (Date.parse(url.searchParams.get('start')!) - T0) / 1000,
    duration: Number(url.searchParams.get('duration')),
  };
}

describe('useRecordedPlayback — 구간 여럿', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('그 방송을 처음 틀 때 한 번만 센다 — 멈췄다 다시 틀기·다음 구간 이어 틀기는 세지 않는다 (POK-357)', () => {
    track.mockClear();
    resetPlaybackStartsForTest();
    const { video } = fakeVideo();
    renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );

    act(() => {
      video.dispatchEvent(new Event('play'));
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('play'));
    });
    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });
    act(() => {
      video.dispatchEvent(new Event('play'));
    });

    expect(track.mock.calls).toEqual([['broadcast_playback_started', { mode: 'vod' }]]);
  });

  it('플레이어가 다시 마운트돼도(GlassPlayer key 변경) 같은 방송은 다시 세지 않는다 — 다른 방송은 센다 (PR #382 리뷰)', () => {
    track.mockClear();
    resetPlaybackStartsForTest();
    const first = fakeVideo();
    const view = renderHook(() => useRecordedPlayback({ current: first.video }, source([[0, 14]])));
    act(() => {
      first.video.dispatchEvent(new Event('play'));
    });
    view.unmount();

    const again = fakeVideo();
    renderHook(() => useRecordedPlayback({ current: again.video }, source([[0, 14]])));
    act(() => {
      again.video.dispatchEvent(new Event('play'));
    });
    const other = fakeVideo();
    renderHook(() =>
      useRecordedPlayback({ current: other.video }, { ...source([[0, 14]]), streamId: 'other' }),
    );
    act(() => {
      other.video.dispatchEvent(new Event('play'));
    });

    expect(track.mock.calls).toEqual([
      ['broadcast_playback_started', { mode: 'vod' }],
      ['broadcast_playback_started', { mode: 'vod' }],
    ]);
  });

  it('틈을 건너 달라 하지 않는다 — 첫 줄기는 첫 구간 끝까지다', () => {
    const { video } = fakeVideo();
    renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    expect(requested(video)).toEqual({ fromSeconds: 0, duration: 14 });
  });

  it('한 구간이 끝나면 다음 구간을 이어 튼다 — 전에는 첫 구간 뒤를 못 봤다', () => {
    const { video, play } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    play.mockClear();

    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });

    expect(requested(video)).toEqual({ fromSeconds: 24, duration: 14 });
    expect(play).toHaveBeenCalled();
    expect(result.current.behindSeconds).toBe(14);
  });

  it('마지막 구간이 끝나면 더 받지 않는다', () => {
    const { video, play } = fakeVideo();
    renderHook(() => useRecordedPlayback({ current: video }, source([[0, 14]])));
    const before = video.src;
    play.mockClear();

    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });

    expect(video.src).toBe(before);
    expect(play).not.toHaveBeenCalled();
  });

  it('틈 안을 누르면 다음 구간 시작으로 간다 — 틈에서 시작하면 재생 서버가 404다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );

    // 전체 38초 중 18초 자리 = 끝에서 20초 앞
    act(() => result.current.seekToFraction(18 / 38));
    act(() => vi.advanceTimersByTime(300));

    expect(requested(video)).toEqual({ fromSeconds: 24, duration: 14 });
  });

  it('멈춰 둔 채 끝까지 본 게 아니면 이어 틀 때 사용자가 멈춘 상태를 지킨다', () => {
    const { video, play } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    act(() => video.dispatchEvent(new Event('pause')));
    play.mockClear();

    act(() => result.current.seekToFraction(30 / 38));
    act(() => vi.advanceTimersByTime(300));

    expect(requested(video).fromSeconds).toBe(30);
    expect(play).not.toHaveBeenCalled();
  });
});

describe('useRecordedPlayback — 틈 앞뒤로 오가기(PR #205 codex)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function endPiece(video: HTMLVideoElement) {
    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });
  }

  it('다음 구간 시작에서 뒤로 가면 앞 구간으로 간다 — 전에는 제자리였다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    endPiece(video);
    expect(requested(video).fromSeconds).toBe(24);
    Object.defineProperty(video, 'ended', { value: false, configurable: true });

    act(() => result.current.seekBy(5));
    act(() => vi.advanceTimersByTime(300));

    expect(requested(video)).toEqual({ fromSeconds: 13, duration: 1 });
  });

  it('다 본 뒤 재생을 누르면 처음부터 다시 튼다 — 전에는 마지막 구간만 다시 나왔다', () => {
    const { video, play } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    endPiece(video);
    endPiece(video);
    play.mockClear();

    act(() => result.current.togglePlay());

    expect(requested(video)).toEqual({ fromSeconds: 0, duration: 14 });
    expect(play).toHaveBeenCalled();
  });
});

describe('useRecordedPlayback — 남은 재생기 결함(POK-253)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('「실시간으로」는 녹화 끝으로 간다 — 전에는 처음(0초)으로 갔다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 14],
          [24, 14],
        ]),
      ),
    );
    act(() => result.current.returnToLive());
    act(() => vi.advanceTimersByTime(300));
    expect(requested(video)).toEqual({ fromSeconds: 37, duration: 1 });
    expect(result.current.atEdge).toBe(true);
  });

  it('보이는 볼륨(70%)을 영상에 싣는다 — 전에는 노드가 100%였다', () => {
    const { video } = fakeVideo();
    // 화면처럼 같은 ref를 넘긴다: 다시 그릴 때마다 새 ref면 줄기를 다시 붙여(효과 의존성) 한 번만 다시 붙이는지를 못 잰다
    const ref = { current: video };
    const recorded = source([[0, 14]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded));
    expect(result.current.volume).toBe(70);
    expect(video.volume).toBeCloseTo(0.7);
  });

  it('화질·저지연 설정 메뉴를 안 보인다 — 렌디션이 하나라 거짓 선택지다', () => {
    const { video } = fakeVideo();
    // 화면처럼 같은 ref를 넘긴다: 다시 그릴 때마다 새 ref면 줄기를 다시 붙여(효과 의존성) 한 번만 다시 붙이는지를 못 잰다
    const ref = { current: video };
    const recorded = source([[0, 14]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded));
    expect(result.current.settingsMenu).toBe(false);
  });

  it('서버 기준점 축이면 첫 구간이 3초에 선다 — 받는 줄기도 녹화 시작부터, 다 본 뒤 재생은 브라우저가 그 줄기를 다시 튼다', () => {
    const { video, play } = fakeVideo();
    const rebased = { streamId: 'gaptest', ...rebaseTimeline(source([[0, 14]]), T0 - 3_000) };
    const { result } = renderHook(() => useRecordedPlayback({ current: video }, rebased));
    const url = new URL(video.src, 'http://localhost');
    expect(Date.parse(url.searchParams.get('start')!)).toBe(T0);
    expect(Number(url.searchParams.get('duration'))).toBe(14);

    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });
    const before = video.src;
    play.mockClear();

    // 첫 구간이 3초에 서므로 「0초로 돌리기」는 같은 줄기다 — 갈아 끼우지 않고 브라우저에 다시 틀게 한다
    act(() => result.current.togglePlay());
    expect(video.src).toBe(before);
    expect(play).toHaveBeenCalled();
  });
});

describe('useRecordedPlayback — 1시간 넘는 녹화', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('시크바 왼쪽 끝이 녹화 처음이다 — 전에는 마지막 1시간만 이동했다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback({ current: video }, source([[0, 9000]])),
    );
    expect(result.current.windowSeconds).toBe(9000);

    act(() => result.current.seekToFraction(0.5));
    act(() => vi.advanceTimersByTime(300));
    expect(requested(video).fromSeconds).toBe(4500);

    act(() => result.current.seekToFraction(0));
    act(() => vi.advanceTimersByTime(300));
    expect(requested(video).fromSeconds).toBe(0);
  });
});

describe('useRecordedPlayback: 시작 위치', () => {
  // 녹화 플레이어가 선 다음에 옮기면, 개발 서버(StrictMode)가 새로 선 부품을 껐다 켤 때 기다리던 이동이 지워져
  // 처음부터 튼다(QA 2026-10-11 실기동). 그래서 고른 시점을 시작 위치로 받아 처음 달라는 줄기부터 그 자리다
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('시작 위치를 주면 처음부터가 아니라 그 자리의 줄기를 달라고 한다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback({ current: video }, source([[0, 100]]), 42),
    );
    expect(requested(video).fromSeconds).toBe(42);
    expect(result.current.behindSeconds).toBe(58);
  });

  it('시작 위치가 틈 안이면 다음 구간 시작이다: 재생 서버는 틈을 건너 주지 않는다', () => {
    const { video } = fakeVideo();
    renderHook(() =>
      useRecordedPlayback(
        { current: video },
        source([
          [0, 10],
          [20, 10],
        ]),
        15,
      ),
    );
    expect(requested(video).fromSeconds).toBe(20);
  });
});

describe('useRecordedPlayback: 이동이 사라지지 않는다', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('지금 줄기가 시작한 자리로 다시 옮기면 줄기 처음으로 되감는다: 같은 카드를 다시 눌러도 그 자리부터다', () => {
    const { video } = fakeVideo();
    const ref = { current: video };
    const recorded = source([[0, 100]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded, 42));
    // 42초부터 30초를 봤다
    video.currentTime = 30;

    act(() => {
      result.current.seekToFraction(0.42);
      vi.advanceTimersByTime(300);
    });

    expect(requested(video).fromSeconds).toBe(42);
    expect(video.currentTime).toBe(0);
  });

  it('같은 자리인지는 주소가 쓰는 ms로 가른다: 1ms 안쪽 차이로 새 주소도 되감기도 없이 멈추지 않는다', () => {
    // 녹화 길이에 소수가 있으면 카드 경로(비율 반올림)의 도착점이 시작 위치와 1ms 안쪽으로 다르다(리뷰 2라운드)
    const { video } = fakeVideo();
    const ref = { current: video };
    const recorded = source([[0, 100.0004]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded, 42));
    video.currentTime = 30;

    act(() => {
      result.current.seekToFraction(0.42);
      vi.advanceTimersByTime(300);
    });

    expect(requested(video).fromSeconds).toBe(42);
    expect(video.currentTime).toBe(0);
  });

  it('선 직후에 건 이동도 StrictMode가 껐다 켜는 사이에 잃지 않는다', () => {
    const { video } = fakeVideo();
    const ref = { current: video };
    const recorded = source([[0, 100]]);
    // 부모 효과처럼 한 번만 건다: StrictMode가 효과를 다시 돌려도 이동은 다시 걸리지 않는다
    function useSeekOnMount() {
      const sim = useRecordedPlayback(ref, recorded);
      const done = useRef(false);
      useEffect(() => {
        if (done.current) return;
        done.current = true;
        sim.seekToFraction(0.5);
      });
      return sim;
    }
    renderHook(() => useSeekOnMount(), { wrapper: StrictMode });

    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(requested(video).fromSeconds).toBe(50);
  });
});

describe('useRecordedPlayback: 재생 위치(POK-324)', () => {
  // 지난 방송 채팅은 이 재생 위치까지의 줄만 보인다. 위치가 옛 자리로 튀면 채팅도 옛 시각으로 돌아간다
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function tick(video: HTMLVideoElement, seconds: number) {
    act(() => {
      video.currentTime = seconds;
      video.dispatchEvent(new Event('timeupdate'));
    });
  }

  it('재생 위치는 지금 줄기의 시작 + 영상 노드 시각이다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback({ current: video }, source([[0, 100]]), 42),
    );
    expect(result.current.positionSeconds).toBe(42);

    tick(video, 5);
    expect(result.current.positionSeconds).toBe(47);
  });

  it('옮기는 동안 옛 줄기의 시각으로 되돌아가지 않는다: 줄기를 갈아 끼우기 전에 시크바가 옛 자리로 튀었다', () => {
    const { video } = fakeVideo();
    // 화면의 영상 노드 ref는 그대로다(useRef). 그릴 때마다 새 ref를 주면 줄기를 다시 붙여 옮기기가 끝난 것으로 본다
    const ref = { current: video };
    const recorded = source([[0, 100]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded));
    tick(video, 30);

    act(() => {
      result.current.seekToFraction(0.8);
    });
    expect(result.current.positionSeconds).toBe(80);
    // 줄기를 갈아 끼우기 전(250ms)에도 옛 줄기는 흐른다
    tick(video, 30.25);
    expect(result.current.positionSeconds).toBe(80);
    expect(result.current.behindSeconds).toBe(20);

    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(requested(video).fromSeconds).toBe(80);
    // 새 줄기가 붙은 뒤에는 그 줄기의 시각으로 흐른다
    tick(video, 1);
    expect(result.current.positionSeconds).toBe(81);
  });

  it('250ms 안에 두 번 옮겨도 둘 다 간다: 옮기는 중에는 도착점에서 센다', () => {
    // 화살표가 옛 줄기 시각에서 세면 두 번째 누름이 첫 번째를 덮어 10초만 갔다(리뷰 1라운드)
    const { video } = fakeVideo();
    const ref = { current: video };
    const recorded = source([[0, 100]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded, 50));

    act(() => {
      result.current.seekBy(10);
      result.current.seekBy(10);
    });
    expect(result.current.positionSeconds).toBe(30);
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(requested(video).fromSeconds).toBe(30);
  });

  it('사람이 옮길 때만 알린다: 다음 구간으로 이어 틀 때는 알리지 않는다', () => {
    // 지난 방송 채팅은 이 알림으로만 맨 아래를 따라간다. 녹화 틈을 넘을 때도 알리면 위로 올려 읽던 자리를 잃었다(리뷰 1라운드)
    const { video } = fakeVideo();
    const ref = { current: video };
    const onSeek = vi.fn();
    const recorded = source([
      [0, 14],
      [24, 14],
    ]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded, 0, onSeek));

    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });
    expect(requested(video).fromSeconds).toBe(24);
    expect(onSeek).not.toHaveBeenCalled();

    act(() => {
      result.current.seekToFraction(0.5);
    });
    expect(onSeek).toHaveBeenCalledTimes(1);
  });

  it('지금 줄기의 시작으로 다시 옮겨 되감아도 그 뒤 재생 위치가 다시 흐른다', () => {
    const { video } = fakeVideo();
    const { result } = renderHook(() =>
      useRecordedPlayback({ current: video }, source([[0, 100]]), 42),
    );
    tick(video, 30);

    act(() => {
      result.current.seekToFraction(0.42);
      vi.advanceTimersByTime(300);
    });
    expect(video.currentTime).toBe(0);
    expect(result.current.positionSeconds).toBe(42);

    tick(video, 2);
    expect(result.current.positionSeconds).toBe(44);
  });

  it('옮기는 동안 옛 구간이 끝나도 다음 구간을 붙이지 않는다: 자리가 옮긴 곳 → 다음 구간 → 옮긴 곳으로 오가지 않는다', () => {
    // 옛 줄기가 끝나는 알림만 「옮기는 중」을 안 봐 채팅 커서와 시크바가 5 → 24 → 5초로 오갔다(리뷰 2라운드)
    const { video } = fakeVideo();
    const ref = { current: video };
    const recorded = source([
      [0, 14],
      [24, 14],
    ]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded));
    tick(video, 13.9);

    act(() => {
      result.current.seekToPosition?.(5);
    });
    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    act(() => {
      video.dispatchEvent(new Event('pause'));
      video.dispatchEvent(new Event('ended'));
    });
    expect(result.current.positionSeconds).toBe(5);

    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(requested(video).fromSeconds).toBe(5);
    expect(result.current.positionSeconds).toBe(5);
  });

  it('다 본 뒤 처음부터 다시 틀면 사람이 옮긴 것으로 알린다: 구간이 하나여도 지난 방송 채팅이 처음을 따라간다', () => {
    // 구간이 하나면 브라우저가 같은 줄기를 다시 틀어 알리지 않았다(리뷰 2라운드)
    const { video, play } = fakeVideo();
    const onSeek = vi.fn();
    const ref = { current: video };
    const recorded = source([[0, 100]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded, 0, onSeek));
    Object.defineProperty(video, 'ended', { value: true, configurable: true });
    Object.defineProperty(video, 'paused', { value: true, configurable: true });

    act(() => result.current.togglePlay());

    expect(onSeek).toHaveBeenCalledTimes(1);
    expect(play).toHaveBeenCalled();
  });

  it('다 본 뒤 처음부터 다시 틀면 그 자리는 0초가 아니라 첫 구간 시작이다: 서버 기준점 축이면 녹화가 몇 초 뒤에 선다', () => {
    const { video } = fakeVideo();
    const onSeek = vi.fn();
    const ref = { current: video };
    // 녹화가 기준점보다 12초 뒤에 서고 구간이 둘이다
    const rebased = {
      streamId: 'gaptest',
      ...rebaseTimeline(
        source([
          [0, 50],
          [60, 40],
        ]),
        T0 - 12_000,
      ),
    };
    const { result } = renderHook(() => useRecordedPlayback(ref, rebased, 0, onSeek));
    act(() => {
      result.current.seekToPosition?.(80);
      vi.advanceTimersByTime(300);
    });
    onSeek.mockClear();
    Object.defineProperty(video, 'ended', { value: true, configurable: true });

    act(() => result.current.togglePlay());

    expect(onSeek).toHaveBeenCalledTimes(1);
    expect(result.current.positionSeconds).toBe(12);
  });

  it('다 본 뒤 옮기는 중에 재생을 누르면 옮긴 자리부터 튼다: 처음으로 덮었다가 되돌아오지 않는다', () => {
    // 끝난 옛 줄기의 ended를 보고 처음부터로 덮으면 걸린 이동이 옮긴 자리로 다시 돌려 자리가 80 → 0 → 80으로 오갔고 지난 방송
    // 채팅도 두 번 처음부터 다시 읽었다(리뷰 3라운드)
    const { video, play } = fakeVideo();
    const onSeek = vi.fn();
    const ref = { current: video };
    const recorded = source([
      [0, 50],
      [60, 40],
    ]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded, 0, onSeek));
    const end = () => {
      Object.defineProperty(video, 'ended', { value: true, configurable: true });
      Object.defineProperty(video, 'paused', { value: true, configurable: true });
      act(() => {
        video.dispatchEvent(new Event('pause'));
        video.dispatchEvent(new Event('ended'));
      });
    };
    // 첫 구간이 끝나 둘째 구간을 이어 틀고, 그것도 끝까지 봤다
    end();
    end();
    act(() => result.current.seekToPosition?.(80));
    onSeek.mockClear();
    play.mockClear();

    act(() => result.current.togglePlay());
    expect(result.current.positionSeconds).toBe(80);
    expect(onSeek).not.toHaveBeenCalled();

    Object.defineProperty(video, 'ended', { value: false, configurable: true });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(requested(video).fromSeconds).toBe(80);
    expect(play).toHaveBeenCalled();
  });

  it('처음 자리는 0초가 아니라 첫 구간 시작이다: 서버 기준점 축이면 녹화가 몇 초 뒤에 선다', () => {
    // 0초로 알리면 지난 방송 채팅이 영상보다 앞 시각에 섰다(자동재생이 막혀 멈춘 채 서면 그대로 남았다, 리뷰 3라운드)
    const { video } = fakeVideo();
    const rebased = { streamId: 'gaptest', ...rebaseTimeline(source([[0, 50]]), T0 - 12_000) };
    const { result } = renderHook(() => useRecordedPlayback({ current: video }, rebased));
    expect(result.current.positionSeconds).toBe(12);
    // 받는 줄기는 녹화 시작부터다
    expect(requested(video).fromSeconds).toBe(0);
  });
});

describe('useRecordedPlayback: 쿠키가 지워진 줄기 (PR #332 codex)', () => {
  afterEach(() => access.recover.mockReset());

  function setup() {
    const { video } = fakeVideo();
    const load = vi.fn();
    Object.defineProperty(video, 'load', { value: load });
    // 화면처럼 같은 ref를 넘긴다: 다시 그릴 때마다 새 ref면 줄기를 다시 붙여(효과 의존성) 한 번만 다시 붙이는지를 못 잰다
    const ref = { current: video };
    const recorded = source([[0, 14]]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded));
    return { video, load, src: video.src, result };
  }

  it('재생 오류가 403이면 출입증을 다시 받은 뒤 같은 줄기를 다시 붙인다', async () => {
    access.recover.mockResolvedValue(true);
    const { video, load, src } = setup();
    await act(async () => {
      video.dispatchEvent(new Event('error'));
    });
    // 훅은 상대 주소를 넘기고 video.src는 절대 주소로 돌려준다: 같은 줄기인지 절대 주소로 비교한다
    const [url, key] = access.recover.mock.calls[0] ?? [];
    expect(new URL(String(url), document.baseURI).href).toBe(src);
    expect(key).toBe('gaptest');
    expect(load).toHaveBeenCalledTimes(1);
    expect(video.src).toBe(src);
  });

  it('403이 아니면(틈 · 서버 오류) 다시 붙이지 않고 영상 위에 알린다', async () => {
    access.recover.mockResolvedValue(false);
    const { video, load, result } = setup();
    expect(result.current.playbackError).toBeNull();
    await act(async () => {
      video.dispatchEvent(new Event('error'));
    });
    expect(load).not.toHaveBeenCalled();
    expect(result.current.playbackError).toBe('녹화 영상을 불러오지 못했어요');
  });

  it('403을 다시 받아 붙이면 알리지 않는다', async () => {
    access.recover.mockResolvedValue(true);
    const { video, result } = setup();
    await act(async () => {
      video.dispatchEvent(new Event('error'));
    });
    expect(result.current.playbackError).toBeNull();
  });

  it('다시 붙인 줄기가 또 오류면 알린다: 전에는 조용히 멎었다', async () => {
    access.recover.mockResolvedValue(true);
    const { video, result } = setup();
    await act(async () => {
      video.dispatchEvent(new Event('error'));
    });
    await act(async () => {
      video.dispatchEvent(new Event('error'));
    });
    expect(result.current.playbackError).toBe('녹화 영상을 불러오지 못했어요');
  });

  it('다른 구간으로 옮기면 알림을 지운다: 새 줄기는 아직 실패한 적이 없다', async () => {
    access.recover.mockResolvedValue(false);
    const { video } = fakeVideo();
    const ref = { current: video };
    const recorded = source([
      [0, 14],
      [24, 14],
    ]);
    const { result } = renderHook(() => useRecordedPlayback(ref, recorded));
    await act(async () => {
      video.dispatchEvent(new Event('error'));
    });
    expect(result.current.playbackError).not.toBeNull();
    vi.useFakeTimers();
    try {
      act(() => result.current.seekToFraction(30 / 38));
      act(() => vi.advanceTimersByTime(300));
    } finally {
      vi.useRealTimers();
    }
    expect(requested(video).fromSeconds).toBeGreaterThanOrEqual(24);
    expect(result.current.playbackError).toBeNull();
  });

  it('같은 줄기는 한 번만 다시 붙인다: 다시 받아도 403이면 끝없이 돌지 않는다', async () => {
    access.recover.mockResolvedValue(true);
    const { video, load } = setup();
    for (let i = 0; i < 3; i++)
      await act(async () => {
        video.dispatchEvent(new Event('error'));
      });
    expect(access.recover).toHaveBeenCalledTimes(1);
    expect(load).toHaveBeenCalledTimes(1);
  });
});
