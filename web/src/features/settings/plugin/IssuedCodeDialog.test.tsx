import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { IssuedCodeDialog } from '@/features/settings/plugin/IssuedCodeDialog';

// 화면 결합 흐름(발급→모달 표시→닫기)은 PluginSettingsScreen.test.tsx가 맡고,
// 여기는 모달 자체의 두 상태(코드 표시 / 만료)를 직접 검증한다.

// 마감은 클라 시계 앵커(epoch ms) — 훅이 응답 수신 순간 + TTL로 만들어 넘기는 값 (리뷰 #74)
function deadlineIn(minutes: number) {
  return Date.now() + minutes * 60 * 1000;
}

describe('IssuedCodeDialog', () => {
  it('코드 원문·카운트다운·1회 표시 경고를 함께 보여준다', () => {
    render(
      <IssuedCodeDialog
        issued={{ code: 'KQ4M-7X2P', deadline: deadlineIn(10) }}
        onClose={() => {}}
        onIssueNew={() => {}}
      />,
    );

    expect(screen.getByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent(/\d{2}:\d{2} 후 만료돼요/);
    expect(screen.getByText(/10분 동안만/)).toBeInTheDocument();
  });

  it('복사 아이콘을 누르면 복사됨 피드백이 뜨고 1.5초 뒤 원래대로 돌아온다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    vi.useFakeTimers();
    try {
      render(
        <IssuedCodeDialog
          issued={{ code: 'KQ4M-7X2P', deadline: deadlineIn(10) }}
          onClose={() => {}}
          onIssueNew={() => {}}
        />,
      );

      fireEvent.click(screen.getByRole('button', { name: '코드 복사' }));
      await act(async () => {}); // writeText 프라미스 반영

      expect(writeText).toHaveBeenCalledWith('KQ4M-7X2P');
      // 시각 툴팁과 스크린리더 안내가 같이 뜬다 (디자인 ③: 체크 아이콘 + "복사됨")
      expect(screen.getByRole('status')).toHaveTextContent('복사됨');

      // 피드백은 1.5초만 (디자인 정본값) — 지나면 다시 복사 대기 상태
      act(() => vi.advanceTimersByTime(1500));
      expect(screen.getByRole('status')).toHaveTextContent('');
      expect(screen.getByRole('button', { name: '코드 복사' })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('확인했어요가 onClose를 부른다 — 그 뒤 원문 재표시 불가는 호출부 책임', () => {
    const onClose = vi.fn();
    render(
      <IssuedCodeDialog
        issued={{ code: 'KQ4M-7X2P', deadline: deadlineIn(10) }}
        onClose={onClose}
        onIssueNew={() => {}}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '확인했어요' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('만료된 코드는 숨기고 상태 안내와 새 코드 발급 버튼으로 바꾼다', () => {
    const onIssueNew = vi.fn();
    render(
      <IssuedCodeDialog
        issued={{ code: 'KQ4M-7X2P', deadline: Date.now() - 1_000 }}
        onClose={() => {}}
        onIssueNew={onIssueNew}
      />,
    );

    // 디자인 ④: 제목이 만료로 바뀌고, 안내는 낭독돼야 한다 (POK-103 만료 상태 표시)
    expect(screen.getByRole('heading', { name: '코드가 만료됐어요' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('발급 후 10분이 지나 코드가 만료되었어요');
    expect(screen.queryByText('KQ4M-7X2P')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '새 코드 발급' }));
    expect(onIssueNew).toHaveBeenCalledTimes(1);
  });
});
