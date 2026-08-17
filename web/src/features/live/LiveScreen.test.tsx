import { act } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { ToastProvider } from '@/ui';
import { LiveScreen } from '@/features/live/LiveScreen';

// GlassPlayer가 useToast를 쓰므로 ToastProvider로 감싼다 (앱에선 providers.tsx가 담당).
function renderLive() {
  return render(
    <ToastProvider>
      <LiveScreen />
    </ToastProvider>,
  );
}

describe('LiveScreen', () => {
  it('헤더에 방송 제목·LIVE 배지·시청자 수를 렌더한다', () => {
    renderLive();

    expect(
      screen.getByRole('heading', { name: '새벽 랭크 올리기 — 다이아 승급전 가보자' }),
    ).toBeInTheDocument();
    // 헤더 필 + 플레이어 오버레이 — LIVE 표기는 두 곳이다
    expect(screen.getAllByText('LIVE').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('시청자 1,842')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/home');
    // 자체 헤더를 갖는 화면도 본문 랜드마크는 있어야 한다
    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('하이라이트 6행을 상태 배지와 함께 렌더한다', () => {
    renderLive();

    expect(screen.getByText('97점')).toBeInTheDocument();
    // '수동'은 필터 칩과 상태 배지 양쪽에 있다
    expect(screen.getAllByText('수동').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('편집 중 · 박편집')).toBeInTheDocument();
    expect(screen.getByText('클립 완료')).toBeInTheDocument();
    expect(screen.getByText('미처리')).toBeInTheDocument();
    expect(screen.getByText('만료')).toBeInTheDocument();
  });

  it('"자동" 필터를 누르면 수동 마킹 카드가 사라진다', async () => {
    const user = userEvent.setup();
    renderLive();

    expect(screen.getByText('시청자 참여 미션 성공')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '자동' }));
    expect(screen.queryByText('시청자 참여 미션 성공')).not.toBeInTheDocument();
    expect(screen.getByText('승급전 마지막 한타 역전')).toBeInTheDocument();
  });

  it('채팅 수집 경고 배너를 보여준다', () => {
    renderLive();
    expect(screen.getByText(/채팅 수집이 잠시 끊겼어요/)).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = renderLive();
    // usePlayerSimulation의 1초 tick이 axe 실행(1초 이상) 중에 발화한다 — act로 감싸 흡수
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
