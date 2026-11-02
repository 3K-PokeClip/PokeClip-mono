import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ThumbnailTile } from './ThumbnailTile';

describe('ThumbnailTile', () => {
  it('넘겨받은 고른 장면 그림을 그리고, 누르면 그 장면으로 간다', async () => {
    const onSeek = vi.fn();
    const { container } = render(
      <ThumbnailTile seconds={105.5} label="0:05" frameUrl="data:frame-105500" onSeek={onSeek} />,
    );
    const tile = screen.getByRole('button', { name: '썸네일 장면 0:05' });
    expect(container.querySelector('img')).toHaveAttribute('src', 'data:frame-105500');
    await userEvent.setup().click(tile);
    expect(onSeek).toHaveBeenCalledWith(105.5);
  });

  it('그림이 아직이면 칸만 보인다', () => {
    const { container } = render(
      <ThumbnailTile seconds={105.5} label="0:05" frameUrl={null} onSeek={vi.fn()} />,
    );
    expect(screen.getByRole('button', { name: '썸네일 장면 0:05' })).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
  });

  it('그림을 뽑는 중이면 칸을 스켈레톤으로 채우고, 못 뽑았으면(재료 없음) 빈 칸 그대로다(POK-444)', () => {
    const { container, rerender } = render(
      <ThumbnailTile seconds={105.5} label="0:05" frameUrl={null} frameLoading onSeek={vi.fn()} />,
    );
    // 스켈레톤은 그림일 뿐이라 낭독에서 빠지고, 칸 이름은 그대로다
    expect(container.querySelector('[data-frame-loading]')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByRole('button', { name: '썸네일 장면 0:05' })).toBeInTheDocument();

    rerender(
      <ThumbnailTile
        seconds={105.5}
        label="0:05"
        frameUrl={null}
        frameLoading={false}
        onSeek={vi.fn()}
      />,
    );
    expect(container.querySelector('[data-frame-loading]')).toBeNull();
  });

  it('고른 장면이 없으면 칸을 그리지 않는다', () => {
    const { container } = render(
      <ThumbnailTile seconds={null} label="0:00" frameUrl={null} onSeek={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
