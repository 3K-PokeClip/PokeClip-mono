import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { HomeScreen } from '@/features/home/HomeScreen';
import { LiveNowBand } from '@/features/home/LiveNowBand';
import type { LiveNow } from '@/features/home/useHomeMockState';

const LIVE: LiveNow = {
  title: '새벽 랭크 올리기 — 다이아 승급전 가보자',
  platform: '치지직',
  startedNote: '오후 7:12 시작 · 편집자 1명 접속 중',
  uptimeLabel: '1:24:03',
  viewers: '1,842',
  detectedCards: 8,
  completedClips: 3,
};

describe('HomeScreen', () => {
  it('인사말·발행 현황·만료 임박 카드를 렌더한다', () => {
    render(<HomeScreen />);

    expect(
      screen.getByRole('heading', { name: /좋은 저녁이에요, 게임하는너구리님/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '발행 현황' })).toBeInTheDocument();
    expect(screen.getByText('업로드 중')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '만료 임박 VOD' })).toBeInTheDocument();
  });

  it('이어서 편집 배너를 닫을 수 있다', async () => {
    const user = userEvent.setup();
    render(<HomeScreen />);

    expect(screen.getByText(/편집하던 클립이 있어요/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '배너 닫기' }));
    expect(screen.queryByText(/편집하던 클립이 있어요/)).not.toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<HomeScreen />);
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('LiveNowBand', () => {
  it('방송 정보와 라이브 대시보드 진입 링크를 렌더한다', () => {
    render(<LiveNowBand live={LIVE} />);

    expect(screen.getByText('LIVE 1:24:03')).toBeInTheDocument();
    expect(screen.getByText(LIVE.title)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '대시보드 열기' })).toHaveAttribute('href', '/live');
    expect(screen.getByRole('link', { name: '카드 검토' })).toHaveAttribute('href', '/live');
  });
});
