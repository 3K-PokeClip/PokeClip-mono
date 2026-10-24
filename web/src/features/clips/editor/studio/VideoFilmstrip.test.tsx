import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useSceneFrames } from '../useSceneFrames';
import {
  FILMSTRIP_TILES,
  VideoFilmstrip,
  filmstripTimesMs,
  useFilmstripFrames,
  type FilmstripSource,
} from './VideoFilmstrip';

// 영상 노드·캔버스가 없는 jsdom에서는 그림을 못 뽑는다: 그림 훅은 「시각마다 주소」를 돌려주는 가짜로 둔다
vi.mock('../useSceneFrames', () => ({
  useSceneFrames: vi.fn(
    (_source: unknown, wanted: readonly number[], _urgent: unknown, keep: number | null) => ({
      frameOf: (ms: number) => (wanted.includes(ms) || ms === keep ? `data:frame-${ms}` : null),
      failed: () => false,
    }),
  ),
}));

const SOURCE = {
  src: 'blob:x',
  videoSecondsAt: (ms: number) => ms / 1000 - 90,
  output: { outputId: 'strip', aspect: 'VERT_9_16' as const, layers: [] },
  fromSeconds: 90,
  toSeconds: 150,
};

function Strip({
  source,
  keepMs = null,
}: {
  source: FilmstripSource | null;
  keepMs?: number | null;
}) {
  const { times, frames } = useFilmstripFrames(
    source,
    { startSeconds: 90, endSeconds: 150 },
    keepMs,
  );
  return (
    <>
      <VideoFilmstrip times={times} frames={frames} />
      {keepMs !== null ? <output data-kept>{frames.frameOf(keepMs)}</output> : null}
    </>
  );
}

describe('filmstripTimesMs: 보이는 창을 칸으로 나눠 칸 가운데 시각(0.25초 눈금)', () => {
  it('창을 고르게 나누고, 받아 둔 영상 밖의 칸은 뺀다(null)', () => {
    const times = filmstripTimesMs({ startSeconds: 80, endSeconds: 160 }, SOURCE, 4);
    // 칸 가운데 90 · 110 · 130 · 150초 → 마지막은 영상 끝(150초)이라 뺀다
    expect(times).toEqual([90_000, 110_000, 130_000, null]);
  });
});

describe('VideoFilmstrip', () => {
  it('칸마다 그 시각 장면 그림을 이어 붙인다', () => {
    const { container } = render(<Strip source={SOURCE} />);
    const tiles = container.querySelectorAll('[data-filmstrip-tile]');
    expect(tiles).toHaveLength(FILMSTRIP_TILES);
    expect(container.querySelectorAll('img').length).toBe(FILMSTRIP_TILES);
    expect(vi.mocked(useSceneFrames).mock.calls.at(-1)![0]).toMatchObject({
      size: { width: 96, height: 54 },
    });
  });

  it('고른 썸네일 장면도 같은 훅에서 뽑는다: 숨은 영상 노드가 하나다(PR #341 claude)', () => {
    vi.mocked(useSceneFrames).mockClear();
    const { container } = render(<Strip source={SOURCE} keepMs={105_500} />);
    // 한 번 그릴 때 훅을 한 번만 부르고, 고른 장면은 keep으로 넘긴다
    expect(vi.mocked(useSceneFrames)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(useSceneFrames).mock.calls[0]![3]).toBe(105_500);
    expect(container.querySelector('[data-kept]')).toHaveTextContent('data:frame-105500');
  });

  it('녹화를 아직 못 받았으면 칸이 없고 재료 없이 부른다', () => {
    const { container } = render(<Strip source={null} />);
    expect(container.querySelectorAll('[data-filmstrip-tile]')).toHaveLength(0);
    expect(vi.mocked(useSceneFrames).mock.calls.at(-1)![0]).toBeNull();
  });
});
