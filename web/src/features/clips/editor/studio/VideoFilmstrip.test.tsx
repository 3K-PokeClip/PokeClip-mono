import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useSceneFrames } from '../useSceneFrames';
import { FILMSTRIP_TILES, VideoFilmstrip, filmstripTimesMs } from './VideoFilmstrip';

// 영상 노드·캔버스가 없는 jsdom에서는 그림을 못 뽑는다: 그림 훅은 「시각마다 주소」를 돌려주는 가짜로 둔다
vi.mock('../useSceneFrames', () => ({
  useSceneFrames: vi.fn((_source: unknown, wanted: readonly number[]) => ({
    frameOf: (ms: number) => (wanted.includes(ms) ? `data:frame-${ms}` : null),
    failed: () => false,
  })),
}));

const SOURCE = {
  src: 'blob:x',
  videoSecondsAt: (ms: number) => ms / 1000 - 90,
  output: { outputId: 'strip', aspect: 'VERT_9_16' as const, layers: [] },
  fromSeconds: 90,
  toSeconds: 150,
};

describe('filmstripTimesMs: 보이는 창을 칸으로 나눠 칸 가운데 시각(0.25초 눈금)', () => {
  it('창을 고르게 나누고, 받아 둔 영상 밖의 칸은 뺀다(null)', () => {
    const times = filmstripTimesMs({ startSeconds: 80, endSeconds: 160 }, SOURCE, 4);
    // 칸 가운데 90 · 110 · 130 · 150초 → 마지막은 영상 끝(150초)이라 뺀다
    expect(times).toEqual([90_000, 110_000, 130_000, null]);
  });
});

describe('VideoFilmstrip', () => {
  it('칸마다 그 시각 장면 그림을 이어 붙인다', () => {
    const { container } = render(
      <VideoFilmstrip source={SOURCE} view={{ startSeconds: 90, endSeconds: 150 }} />,
    );
    const tiles = container.querySelectorAll('[data-filmstrip-tile]');
    expect(tiles).toHaveLength(FILMSTRIP_TILES);
    expect(container.querySelectorAll('img').length).toBe(FILMSTRIP_TILES);
    expect(vi.mocked(useSceneFrames).mock.calls.at(-1)![0]).toMatchObject({
      size: { width: 96, height: 54 },
    });
  });
});
