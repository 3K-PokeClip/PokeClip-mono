import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import FullscreenLayout from './layout';

// AuthGuard는 세션 스토어·useMe를 타므로 여기선 통과만 시킨다 —
// 이 테스트가 지키려는 것은 "이 그룹에는 독이 없다" 하나다.
vi.mock('@/components/app-shell/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

describe('(fullscreen) 레이아웃', () => {
  it('하단 독 없이 화면만 그린다 — 편집기를 (dock)으로 되돌리면 여기서 깨진다', () => {
    render(<FullscreenLayout>{<div>편집기</div>}</FullscreenLayout>);

    expect(screen.getByText('편집기')).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /클립/ })).not.toBeInTheDocument();
  });
});
