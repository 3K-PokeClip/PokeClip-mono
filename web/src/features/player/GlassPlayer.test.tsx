import { act } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
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

  it('시크바 PageDown은 60초 뒤로 시킹한다', () => {
    renderPlayer();
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    fireEvent.keyDown(slider, { key: 'PageDown' });
    expect(slider).toHaveAttribute('aria-valuenow', '-60');
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

  it('설정 팝오버가 열려 있는 동안엔 자동 숨김이 유보된다', () => {
    // 팝오버는 Portal로 플레이어 밖에 떠서 포커스 보호가 닿지 않는다 — 열림 상태로 유보한다.
    // userEvent는 fake timer와 대기가 엉켜 멈추므로 동기 fireEvent를 쓴다.
    vi.useFakeTimers();
    try {
      const { container } = renderPlayer();
      const player = container.querySelector('[data-controls]');

      fireEvent.click(screen.getByRole('button', { name: '설정' }));
      act(() => {
        vi.advanceTimersByTime(4000); // 숨김 타이머(2.8s)를 지나친다
      });

      expect(player).toHaveAttribute('data-controls', 'visible');
    } finally {
      vi.useRealTimers();
    }
  });
});
