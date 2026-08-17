import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PluginSettingsScreen } from '@/features/settings/plugin/PluginSettingsScreen';
import { PairingCodeCard } from '@/features/settings/plugin/PairingCodeCard';

describe('PluginSettingsScreen', () => {
  it('디자인 1m의 블록 세 개를 렌더한다', () => {
    render(<PluginSettingsScreen />);

    expect(screen.getByRole('region', { name: '플러그인 연결 상태' })).toHaveTextContent('연결됨');
    expect(screen.getByRole('heading', { name: '연동 코드' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '플러그인 다운로드' })).toBeInTheDocument();
  });

  it('코드 원문은 화면 어디에도 다시 나오지 않는다 (ADR-019)', () => {
    const { container } = render(<PluginSettingsScreen />);

    expect(screen.getByText(/보안을 위해 코드는 다시 표시되지 않아요/)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/srt:\/\//i);
  });

  it('재발급하면 발행일이 오늘로 갱신된다', () => {
    // 실제 시계로 자정을 넘기면 기대값이 어긋난다 — 시계를 고정해 결정적으로 만든다
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-08-17T12:00:00'));
      render(<PluginSettingsScreen />);

      expect(screen.getByText(/발행일 2026\. 8\. 2\./)).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: '재발급' }));

      expect(screen.getByText(/발행일 2026\. 8\. 17\./)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('발급 직후에만 코드 원문을 1회 보여준다 (ADR-019)', () => {
    render(<PluginSettingsScreen />);

    // 이전 세션 발급분 — 원문은 이미 사라졌다
    // 서버의 사람용 표기와 같은 XXXX-XXXX (Crockford Base32, PairingCodeService.format)
    const CODE_PATTERN = /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/;
    expect(screen.queryByText(CODE_PATTERN)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '재발급' }));

    expect(screen.getByText(CODE_PATTERN)).toBeInTheDocument();
    expect(screen.getByText(/이 코드는 지금만 보여요/)).toBeInTheDocument();
  });
});

describe('PairingCodeCard 미발급 상태', () => {
  it('빈 상태 안내와 코드 발급 버튼을 보여준다', () => {
    render(<PairingCodeCard code={{ issued: false }} onIssue={() => {}} />);

    expect(screen.getByText(/아직 발급된 코드가 없어요/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '코드 발급' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '재발급' })).not.toBeInTheDocument();
  });
});
