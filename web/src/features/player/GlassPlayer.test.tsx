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

  it('시크바 범위는 되감기 창을 따른다 — 방송이 짧으면 그만큼만', () => {
    // 창을 1시간으로 고정하면 짧은 방송에서 시크바 왼쪽이 눌러도 안 가는 영역이 된다 (POK-32)
    renderPlayer({ initialUptimeSeconds: 600 });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuemin',
      '-600',
    );
  });

  it('방송이 창보다 길면 상한은 계약값 1시간이다', () => {
    renderPlayer({ initialUptimeSeconds: 5043 });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuemin',
      '-3600',
    );
  });

  it('플레이어 어디에 포커스가 있어도 화살표로 되감을 수 있다', () => {
    // 시크바에 Tab 포커스를 넣지 않아도 먹어야 한다 (POK-32)
    const { container } = renderPlayer();
    const player = container.querySelector('[data-controls]')!;

    fireEvent.keyDown(player, { key: 'ArrowLeft' });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-10',
    );
  });

  it('볼륨 슬라이더 위에서는 화살표가 시킹하지 않는다', () => {
    // 네이티브 input[type=range]는 preventDefault를 안 해서 타겟 검사로 걸러야 한다
    renderPlayer();
    fireEvent.keyDown(screen.getByRole('slider', { name: '볼륨' }), { key: 'ArrowLeft' });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '0',
    );
  });

  it('재생 버튼을 누른 뒤에도 화살표로 되감을 수 있다', () => {
    // 버튼엔 화살표 기본 동작이 없다 — 클릭 직후 단축키가 죽으면 "전역"이 아니다
    renderPlayer();
    fireEvent.keyDown(screen.getByRole('button', { name: '일시정지' }), { key: 'ArrowLeft' });
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-10',
    );
  });

  it('수정자 키 조합은 가로채지 않는다 — 브라우저 뒤로 가기가 살아 있어야 한다', () => {
    // 회귀: 전역 단축키는 영상을 한 번 클릭하면 활성화된다. Cmd+←(macOS)·Alt+←(Win)로
    // 이전 페이지에 가려던 사용자가 영상만 되감기고, preventDefault 탓에 뒤로 가기도 죽었다.
    const { container } = renderPlayer();
    const player = container.querySelector('[data-controls]')!;
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    for (const modifier of ['metaKey', 'ctrlKey', 'altKey']) {
      const notPrevented = fireEvent.keyDown(player, { key: 'ArrowLeft', [modifier]: true });
      expect(slider).toHaveAttribute('aria-valuenow', '0');
      // 시킹하지 않았으면 기본 동작도 막지 않아야 브라우저가 뒤로 갈 수 있다
      expect(notPrevented).toBe(true);
    }
  });

  it('키 자동반복은 무시한다 — 누르고 있어도 시크가 폭주하지 않는다', () => {
    // 회귀: OS 키 반복(초당 ~30회)이 그대로 시킹돼 hls.js가 매번 버퍼를 비웠다.
    // 드래그를 "놓을 때 한 번만" 커밋한 것과 같은 이유의 방어다.
    const { container } = renderPlayer();
    const player = container.querySelector('[data-controls]')!;

    fireEvent.keyDown(player, { key: 'ArrowLeft' });
    for (let i = 0; i < 5; i += 1) {
      fireEvent.keyDown(player, { key: 'ArrowLeft', repeat: true });
    }
    // 첫 타건 1회만 반영된다 — 가드가 없으면 -60이다
    expect(screen.getByRole('slider', { name: '라이브 탐색' })).toHaveAttribute(
      'aria-valuenow',
      '-10',
    );
  });

  it('시크바에서 누른 키는 전역 단축키가 다시 처리하지 않는다', () => {
    renderPlayer();
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    fireEvent.keyDown(slider, { key: 'ArrowLeft' });
    // 이중 처리되면 -20이 된다
    expect(slider).toHaveAttribute('aria-valuenow', '-10');
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

  it('설정 팝오버 안에서 누른 화살표는 배경 영상을 시킹하지 않는다', () => {
    // 팝오버는 Portal로 body에 붙지만 React는 React 트리를 따라 버블링시킨다 —
    // 컨테이너의 전역 단축키가 그 키를 삼키면 포커스 트랩 뒤에서 영상이 움직인다
    renderPlayer();
    const slider = screen.getByRole('slider', { name: '라이브 탐색' });

    fireEvent.click(screen.getByRole('button', { name: '설정' }));
    fireEvent.keyDown(screen.getByRole('button', { name: '자동 (1080p)' }), { key: 'ArrowLeft' });

    expect(slider).toHaveAttribute('aria-valuenow', '0');
  });

  it('시크바에서 키보드로 시킹하는 동안 컨트롤이 사라지지 않는다', () => {
    // 회귀: 전역 핸들러가 슬라이더 타겟에서 wake보다 먼저 반환해 숨김 타이머가 갱신되지
    // 않았다. 클릭으로 포커스가 들어간 시크바는 :focus-visible이 아니라 CSS 유보도 안 걸려,
    // 마우스를 안 움직이고 화살표만 누르면 되감기는 계속되는데 시크바·시차 표기가 사라졌다.
    vi.useFakeTimers();
    try {
      const { container } = renderPlayer();
      const player = container.querySelector('[data-controls]');
      const slider = screen.getByRole('slider', { name: '라이브 탐색' });

      act(() => {
        vi.advanceTimersByTime(2000); // 숨김 타이머(2.8s) 직전
      });
      fireEvent.keyDown(slider, { key: 'ArrowLeft' });
      act(() => {
        vi.advanceTimersByTime(2000); // 타이머가 갱신되지 않았다면 이 지점에서 숨는다
      });

      expect(player).toHaveAttribute('data-controls', 'visible');
      expect(slider).toHaveAttribute('aria-valuenow', '-10');
    } finally {
      vi.useRealTimers();
    }
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
