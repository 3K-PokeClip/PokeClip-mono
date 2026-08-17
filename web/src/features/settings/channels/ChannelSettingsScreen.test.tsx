import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { ChannelSettingsScreen } from '@/features/settings/channels/ChannelSettingsScreen';

describe('ChannelSettingsScreen', () => {
  it('신규 계정 기본값은 미연동 — 치지직 연동 버튼과 SOOP 자리를 보여준다', () => {
    render(<ChannelSettingsScreen />);

    expect(screen.getByRole('heading', { name: '채널 연동' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '방송 채널' })).toBeInTheDocument();
    expect(screen.getByText(/연동하면 치지직 방송에서 하이라이트를 감지해요/)).toBeInTheDocument();
    expect(screen.queryByText('연동됨')).not.toBeInTheDocument();

    // SOOP은 자리만 — 버튼 비활성
    const buttons = screen.getAllByRole('button', { name: '연동' });
    expect(buttons).toHaveLength(2);
    expect(buttons[1]).toBeDisabled();
  });

  it('연동을 누르면 연동됨 배지와 해제 버튼으로 바뀌고, 해제하면 원복된다', () => {
    render(<ChannelSettingsScreen />);

    // 첫 번째 '연동' 버튼이 치지직 행 (SOOP은 비활성)
    fireEvent.click(screen.getAllByRole('button', { name: '연동' })[0]!);

    expect(screen.getByText('연동됨')).toBeInTheDocument();
    expect(screen.getByText(/게임하는너구리/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '연동 해제' }));

    expect(screen.queryByText('연동됨')).not.toBeInTheDocument();
    expect(screen.getByText(/연동하면 치지직 방송에서 하이라이트를 감지해요/)).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<ChannelSettingsScreen />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
