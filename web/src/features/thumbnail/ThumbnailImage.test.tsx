import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ThumbnailImage } from './ThumbnailImage';

const SRC = 'http://localhost:4566/clips-local/thumbnails/card/7.jpg?X-Amz-Date=20261004T101500Z';
const OTHER = 'http://localhost:4566/clips-local/thumbnails/card/8.jpg?X-Amz-Date=20261004T101500Z';

describe('ThumbnailImage', () => {
  it('사진이 있으면 사진을, 없으면 자리표시를 보인다', () => {
    const { container, rerender } = render(
      <ThumbnailImage src={SRC} fallback={<span>자리표시</span>} />,
    );
    expect(container.querySelector('img')).toHaveAttribute('src', SRC);
    expect(screen.queryByText('자리표시')).not.toBeInTheDocument();

    rerender(<ThumbnailImage src={null} fallback={<span>자리표시</span>} />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('자리표시')).toBeInTheDocument();
  });

  it('사진을 못 받으면(만료·삭제) 자리표시로 돌아가고, 다른 사진이 오면 다시 시도한다', () => {
    const { container, rerender } = render(
      <ThumbnailImage src={SRC} fallback={<span>자리표시</span>} />,
    );
    fireEvent.error(container.querySelector('img')!);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('자리표시')).toBeInTheDocument();

    rerender(<ThumbnailImage src={OTHER} fallback={<span>자리표시</span>} />);
    expect(container.querySelector('img')).toHaveAttribute('src', OTHER);
  });

  it('못 받은 뒤 같은 사진의 새 서명 주소가 오면 바로 다시 시도한다(갱신 시간을 기다리지 않는다)', () => {
    // 서명 3초 뒤로 시계를 고정한다. 안 그러면 「50분 지났다」로 새 주소를 쓰게 돼 이 갈래를 재지 못한다
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-04T10:15:06Z'));
    const later = SRC.replace('20261004T101500Z', '20261004T101503Z');
    const { container, rerender } = render(
      <ThumbnailImage src={SRC} fallback={<span>자리표시</span>} />,
    );
    fireEvent.error(container.querySelector('img')!);
    expect(container.querySelector('img')).toBeNull();

    rerender(<ThumbnailImage src={later} fallback={<span>자리표시</span>} />);
    expect(container.querySelector('img')).toHaveAttribute('src', later);
    vi.useRealTimers();
  });
});
