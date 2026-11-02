import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import type { SessionView } from './sessionView';
import { PlayerSessionOverlay } from './PlayerSessionOverlay';

// 끝남 대기 덮개 · 띠(설계 5.3 · 5.5절 — 버튼 넷의 문구는 2번 통지 범위다)

const QUIET: SessionView = {
  cover: null,
  actions: [],
  banner: null,
  seekHidden: false,
  liveHidden: false,
  liveChecking: false,
  recordEmphasis: false,
  vodReady: false,
};

describe('PlayerSessionOverlay', () => {
  it('아무 일도 없으면 그리지 않는다', () => {
    const { container } = render(<PlayerSessionOverlay view={QUIET} onAction={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('재접속 대기 덮개는 「이어지는지 확인」 문구와 「녹화 보기」를 늘 둔다', async () => {
    const onAction = vi.fn();
    render(
      <PlayerSessionOverlay
        view={{ ...QUIET, cover: 'checking', actions: ['record'] }}
        onAction={onAction}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('방송이 이어지는지 확인하고 있어요');
    await userEvent.click(screen.getByRole('button', { name: '녹화 보기' }));
    expect(onAction).toHaveBeenCalledWith('record');
  });

  it('재접속 대기 뒤에는 「다시보기 준비 중」이다', () => {
    render(
      <PlayerSessionOverlay
        view={{ ...QUIET, cover: 'preparing', actions: ['record'] }}
        onAction={vi.fn()}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('다시보기 준비 중');
  });

  it('버튼 규칙의 「라이브 보기」 · 「새 방송 보기」가 덮개에 선다', async () => {
    const onAction = vi.fn();
    const { rerender } = render(
      <PlayerSessionOverlay
        view={{ ...QUIET, cover: 'checking', actions: ['record', 'go-live'] }}
        onAction={onAction}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: '라이브 보기' }));
    expect(onAction).toHaveBeenLastCalledWith('go-live');
    rerender(
      <PlayerSessionOverlay
        view={{ ...QUIET, cover: 'checking', actions: ['record', 'new-broadcast'] }}
        onAction={onAction}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: '새 방송 보기' }));
    expect(onAction).toHaveBeenLastCalledWith('new-broadcast');
  });

  it('아직 보는 시청자에게는 띠로 — 다시보기 준비됨 · 다시보기 보기', async () => {
    const onAction = vi.fn();
    render(
      <PlayerSessionOverlay
        view={{ ...QUIET, actions: ['record', 'vod'], vodReady: true }}
        onAction={onAction}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('다시보기 준비됨');
    await userEvent.click(screen.getByRole('button', { name: '다시보기 보기' }));
    expect(onAction).toHaveBeenCalledWith('vod');
  });

  it('세션에 묶인 live 가 다른 방송을 보면 「새 방송이 시작됐어요」 띠 · 보러 가기', async () => {
    const onAction = vi.fn();
    render(
      <PlayerSessionOverlay view={{ ...QUIET, banner: { goTo: 'r-B' } }} onAction={onAction} />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('새 방송이 시작됐어요');
    await userEvent.click(screen.getByRole('button', { name: '보러 가기' }));
    expect(onAction).toHaveBeenCalledWith('new-broadcast');
  });

  it('명부 ended 면 「녹화 보기」를 기본 강조로 둔다', () => {
    render(
      <PlayerSessionOverlay
        view={{ ...QUIET, cover: 'preparing', actions: ['record'], recordEmphasis: true }}
        onAction={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: '녹화 보기' })).toHaveAttribute(
      'data-emphasis',
      'true',
    );
  });

  it('LIVE 를 눌러 지금 방송을 확인하는 동안 알린다', () => {
    render(<PlayerSessionOverlay view={{ ...QUIET, liveChecking: true }} onAction={vi.fn()} />);
    expect(screen.getByRole('status')).toHaveTextContent('지금 방송을 확인하고 있어요');
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(
      <PlayerSessionOverlay
        view={{ ...QUIET, cover: 'checking', actions: ['record', 'go-live'] }}
        onAction={vi.fn()}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
