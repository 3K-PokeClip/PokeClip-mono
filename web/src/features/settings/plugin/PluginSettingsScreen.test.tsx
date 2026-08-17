import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
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
    render(<PluginSettingsScreen />);

    expect(screen.getByText(/발행일 2026\. 8\. 2\./)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '재발급' }));

    const today = new Date().toLocaleDateString('ko-KR');
    expect(
      screen.getByText(new RegExp(`발행일 ${today.replace(/\./g, '\\.')}`)),
    ).toBeInTheDocument();
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
