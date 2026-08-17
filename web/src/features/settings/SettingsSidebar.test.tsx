import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { SettingsSidebar } from '@/features/settings/SettingsSidebar';

vi.mock('next/navigation', () => ({
  usePathname: () => '/settings/plugin',
}));

describe('SettingsSidebar', () => {
  it('설정 메뉴 7개 중 플러그인만 링크이고 현재 경로를 활성으로 표시한다', () => {
    render(<SettingsSidebar />);

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/settings/plugin');
    expect(links[0]).toHaveAttribute('aria-current', 'page');

    // 나머지 6개는 비활성 (하위 티켓에서 라우트가 생기면 링크로 전환)
    for (const label of [
      '채널 연동',
      '편집자 관리',
      '알림 설정',
      '구독 · 결제',
      '계정',
      '도움말 · 문의',
    ]) {
      expect(screen.getByText(label).closest('[aria-disabled="true"]')).not.toBeNull();
    }
  });

  it('토글 버튼이 접힘 상태를 전환한다', async () => {
    const user = userEvent.setup();
    render(<SettingsSidebar />);

    const toggle = screen.getByRole('button', { name: '사이드바 접기' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    await user.click(toggle);
    expect(screen.getByRole('button', { name: '사이드바 펼치기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });
});
