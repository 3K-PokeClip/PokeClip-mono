import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ThumbnailTile } from './ThumbnailTile';

// jsdom에는 영상 노드·캔버스가 없다: 그림 훅은 「시각마다 주소」를 돌려주는 가짜로 둔다
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

describe('ThumbnailTile', () => {
  it('고른 시각의 장면을 그린다: 지금 영상 화면을 찍지 않아 다시 그려져도 고른 장면 그대로다(PR #341 codex)', () => {
    const { container } = render(
      <ThumbnailTile seconds={105.5} label="0:05" source={SOURCE} onSeek={vi.fn()} />,
    );
    expect(screen.getByRole('button', { name: '썸네일 장면 0:05' })).toBeInTheDocument();
    expect(container.querySelector('img')).toHaveAttribute('src', 'data:frame-105500');
  });

  it('고른 장면이 없으면 칸을 그리지 않는다', () => {
    const { container } = render(
      <ThumbnailTile seconds={null} label="0:00" source={SOURCE} onSeek={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
