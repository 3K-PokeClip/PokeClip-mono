import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { applySound, playFirst, readSound } from './playbackSound';

// 다시보기로 넘길 때 옛 영상 노드의 소리 상태를 새 노드에 옮기고, 첫 재생이 소리 때문에 막히면 소리만 끄고 한 번 더
// 튼다(POK-547). 영상 노드의 소리 값과 play() 만 쓰는 순수 DOM 코드라 영상 노드 하나로 잰다.

/** play() 가 차례로 낼 결과 — 'ok' 면 재생, 그 밖은 그 이름의 DOMException 으로 거절 */
type PlayResult = 'ok' | 'NotAllowedError' | 'AbortError' | 'NotSupportedError';

/** play() 를 갈아 끼운 영상 노드. 부를 때마다 그 순간의 소리 값을 적어 둔다 */
function videoWithPlay(...results: PlayResult[]) {
  const video = document.createElement('video');
  const seen: Array<{ muted: boolean; volume: number }> = [];
  const play = vi.fn(() => {
    seen.push({ muted: video.muted, volume: video.volume });
    const result = results[seen.length - 1] ?? 'ok';
    return result === 'ok'
      ? Promise.resolve()
      : Promise.reject(new DOMException('play() 거절', result));
  });
  video.play = play;
  return { video, play, seen };
}

/** 약속 사슬(첫 거절 → 다시 시도 → 그 결과)이 모두 끝날 때까지 기다린다 */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('readSound', () => {
  it.each([
    ['소리 켬 · 음량 30', false, 0.3, { muted: false, volume: 30 }],
    ['무음 · 음량 30', true, 0.3, { muted: true, volume: 30 }],
    ['소리 켬 · 음량 0', false, 0, { muted: false, volume: 0 }],
    ['음량은 UI 단위 정수로 반올림', false, 0.333, { muted: false, volume: 33 }],
  ])('%s', (_name, muted, volume, expected) => {
    const video = document.createElement('video');
    video.muted = muted;
    video.volume = volume;

    expect(readSound(video)).toEqual(expected);
  });

  it('영상 노드가 없으면 null 이다', () => {
    expect(readSound(null)).toBeNull();
  });
});

describe('applySound', () => {
  it.each([
    ['소리 켬 · 음량 30', { muted: false, volume: 30 }, false, 0.3],
    ['무음 · 음량 30', { muted: true, volume: 30 }, true, 0.3],
    ['소리 켬 · 음량 0', { muted: false, volume: 0 }, false, 0],
  ])('%s 을 영상 노드에 그대로 쓴다', (_name, sound, muted, volume) => {
    const video = document.createElement('video');
    video.muted = !muted;
    video.volume = 0.7;

    applySound(video, sound);

    expect(video.muted).toBe(muted);
    expect(video.volume).toBeCloseTo(volume);
    expect(readSound(video)).toEqual(sound);
  });

  it('적용한 뒤 부른 첫 play() 는 넘긴 소리 값을 본다', async () => {
    const { video, seen } = videoWithPlay('ok');
    video.muted = true;
    video.volume = 0.7;

    applySound(video, { muted: false, volume: 30 });
    playFirst(video, true);
    await settle();

    expect(seen).toEqual([{ muted: false, volume: 0.3 }]);
  });
});

describe('playFirst', () => {
  beforeEach(() => {
    vi.spyOn(console, 'info').mockImplementation(() => {});
  });
  afterEach(() => vi.restoreAllMocks());

  it('처음 재생이 되면 한 번만 부르고 소리를 건드리지 않는다', async () => {
    const { video, play } = videoWithPlay('ok');
    applySound(video, { muted: false, volume: 30 });

    playFirst(video, true);
    await settle();

    expect(play).toHaveBeenCalledTimes(1);
    expect(video.muted).toBe(false);
  });

  it('소리 켠 재생이 NotAllowedError 로 막히면 음량은 두고 소리만 끈 채 한 번 더 튼다', async () => {
    const { video, seen } = videoWithPlay('NotAllowedError', 'ok');
    applySound(video, { muted: false, volume: 30 });

    playFirst(video, true);
    await settle();

    expect(seen).toEqual([
      { muted: false, volume: 0.3 },
      { muted: true, volume: 0.3 },
    ]);
    expect(console.info).toHaveBeenCalledWith(
      '[player] 소리 켠 재생이 막혀 소리를 끄고 다시 틀어요',
    );
  });

  it('다시 거절되면 거기서 멈춘다 — 세 번째 시도 없이 소리 끈 채로 남고 거절은 밖으로 새지 않는다', async () => {
    const { video, play } = videoWithPlay('NotAllowedError', 'NotAllowedError');
    applySound(video, { muted: false, volume: 30 });

    playFirst(video, true);
    await settle();

    expect(play).toHaveBeenCalledTimes(2);
    expect(video.muted).toBe(true);
    expect(video.volume).toBeCloseTo(0.3);
  });

  it.each(['AbortError', 'NotSupportedError'] as const)(
    '%s 면 다시 시도하지 않는다',
    async (name) => {
      const { video, play } = videoWithPlay(name);
      applySound(video, { muted: false, volume: 30 });

      playFirst(video, true);
      await settle();

      expect(play).toHaveBeenCalledTimes(1);
      expect(video.muted).toBe(false);
    },
  );

  it('소리 대체를 켜지 않았으면 NotAllowedError 에도 다시 시도하지 않는다', async () => {
    const { video, play } = videoWithPlay('NotAllowedError');

    playFirst(video, false);
    await settle();

    expect(play).toHaveBeenCalledTimes(1);
    expect(console.info).not.toHaveBeenCalled();
  });
});
