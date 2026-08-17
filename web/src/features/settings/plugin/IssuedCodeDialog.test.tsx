import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { IssuedCodeDialog } from '@/features/settings/plugin/IssuedCodeDialog';

// 화면 결합 흐름(발급→모달 표시→닫기)은 PluginSettingsScreen.test.tsx가 맡고,
// 여기는 모달 자체의 두 상태(코드 표시 / 만료)를 직접 검증한다.

function futureIso(minutes: number) {
  return new Date(Date.now() + minutes * 60 * 1000).toISOString();
}

describe('IssuedCodeDialog', () => {
  it('코드 원문·카운트다운·1회 표시 경고를 함께 보여준다', () => {
    render(
      <IssuedCodeDialog
        issued={{ code: 'KQ4M-7X2P', expiresAt: futureIso(10) }}
        onClose={() => {}}
        onIssueNew={() => {}}
      />,
    );

    expect(screen.getByText('KQ4M-7X2P')).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent(/\d{2}:\d{2} 후 만료돼요/);
    expect(screen.getByText(/한 번만/)).toBeInTheDocument();
  });

  it('확인했어요가 onClose를 부른다 — 그 뒤 원문 재표시 불가는 호출부 책임', () => {
    const onClose = vi.fn();
    render(
      <IssuedCodeDialog
        issued={{ code: 'KQ4M-7X2P', expiresAt: futureIso(10) }}
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
        issued={{ code: 'KQ4M-7X2P', expiresAt: '2020-01-01T00:00:00Z' }}
        onClose={() => {}}
        onIssueNew={onIssueNew}
      />,
    );

    // 만료 전환은 낭독돼야 한다 (POK-103 만료 상태 표시)
    expect(screen.getByRole('status')).toHaveTextContent('코드가 만료됐어요');
    expect(screen.queryByText('KQ4M-7X2P')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '새 코드 발급' }));
    expect(onIssueNew).toHaveBeenCalledTimes(1);
  });
});
