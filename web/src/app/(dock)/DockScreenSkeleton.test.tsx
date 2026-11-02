import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DockScreenSkeleton } from './DockScreenSkeleton';

const nav = vi.hoisted(() => ({ pathname: '/broadcast/vod' }));
vi.mock('next/navigation', () => ({ usePathname: () => nav.pathname }));

// 채널이 서기 전 본문 자리는 그 화면이 첫 로딩에 그릴 뼈대와 같아야 한다 — 뼈대가 두 번 바뀌지 않게(PR #377 2차 리뷰)
describe('DockScreenSkeleton', () => {
  it.each([
    ['/broadcast/vod', '지난 방송을 불러오는 중'],
    ['/broadcast/livenow', '라이브 대시보드를 불러오는 중'],
    ['/broadcast/vod/s-1', '라이브 대시보드를 불러오는 중'],
    ['/clips/library', '편집본을 불러오는 중'],
    ['/clips/templates', '화면을 불러오는 중'],
  ])('%s는 「%s」 뼈대다', (pathname, label) => {
    nav.pathname = pathname;
    render(<DockScreenSkeleton />);

    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent(label);
  });
});
