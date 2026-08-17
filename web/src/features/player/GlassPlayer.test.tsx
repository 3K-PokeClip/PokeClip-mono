import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ToastProvider } from '@/ui';
import { GlassPlayer } from '@/features/player/GlassPlayer';
import type { PlayerSimulationOptions } from '@/features/player/usePlayerSimulation';

function renderPlayer(simulationOptions?: PlayerSimulationOptions) {
  return render(
    <ToastProvider>
      <GlassPlayer
        channelName="게임하는너구리"
        title="새벽 랭크 올리기"
        viewersNote="시청자 1,842"
        embed
        simulationOptions={simulationOptions}
      />
    </ToastProvider>,
  );
}

describe('GlassPlayer', () => {
  it('엣지에서는 LIVE 복귀 버튼이 "실시간"을 보여준다', () => {
    renderPlayer();
    expect(screen.getByRole('button', { name: '실시간으로 이동' })).toHaveTextContent('실시간');
  });

  it('뒤로 시킹된 상태에선 -MM:SS 시차를 보여주고, 클릭하면 실시간으로 복귀한다', async () => {
    const user = userEvent.setup();
    renderPlayer({ initialBehindSeconds: 83 });

    const livePill = screen.getByRole('button', { name: '실시간으로 이동' });
    expect(livePill).toHaveTextContent('-01:23 · 실시간으로');

    await user.click(livePill);
    expect(livePill).toHaveTextContent('실시간');
    expect(livePill).not.toHaveTextContent('실시간으로');
  });

  it('시크바는 시차를 slider 값으로 노출한다', () => {
    renderPlayer({ initialBehindSeconds: 83 });
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });
    expect(slider).toHaveAttribute('aria-valuenow', '-83');
    expect(slider).toHaveAttribute('aria-valuetext', '실시간에서 -01:23');
  });

  it('재생/일시정지가 토글된다', async () => {
    const user = userEvent.setup();
    renderPlayer();

    await user.click(screen.getByRole('button', { name: '일시정지' }));
    expect(screen.getByRole('button', { name: '재생' })).toBeInTheDocument();
  });

  it('클립 만들기를 누르면 토스트가 뜬다', async () => {
    const user = userEvent.setup();
    renderPlayer();

    await user.click(screen.getByRole('button', { name: '클립 만들기' }));
    expect(screen.getByText('최근 30초 클립이 저장되었습니다')).toBeInTheDocument();
  });

  it('설정 팝오버에서 화질 4개를 고를 수 있다', async () => {
    const user = userEvent.setup();
    renderPlayer();

    await user.click(screen.getByRole('button', { name: '설정' }));
    expect(screen.getByRole('button', { name: '자동 (1080p)' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: '1080p60' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '720p' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '480p' })).toBeInTheDocument();
  });
});
