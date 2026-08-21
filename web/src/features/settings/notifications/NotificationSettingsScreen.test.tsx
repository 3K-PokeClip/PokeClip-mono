import { fireEvent, render, screen, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { beforeEach, describe, expect, it } from 'vitest';
import { NotificationSettingsScreen } from '@/features/settings/notifications/NotificationSettingsScreen';

const row = (name: string) => within(screen.getByRole('group', { name }));

describe('NotificationSettingsScreen', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('중요·일반 2그룹과 인앱·이메일 2열이 시안대로 뜬다', () => {
    render(<NotificationSettingsScreen />);

    expect(screen.getByRole('heading', { name: '알림 설정' })).toBeInTheDocument();

    // 카드 둘이 각자 그룹 이름을 접근 이름으로 든다
    expect(screen.getAllByRole('region')).toHaveLength(2);
    expect(screen.getByRole('region', { name: '중요 알림' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '일반 알림' })).toBeInTheDocument();

    // 열 머리글이 카드마다 하나씩
    expect(screen.getAllByText('인앱')).toHaveLength(2);
    expect(screen.getAllByText('이메일')).toHaveLength(2);

    // 시안 1n 8행 + IA 전용 3행(방송 시작·승인 결과·결제 실패) = 11행
    expect(screen.getAllByRole('group')).toHaveLength(11);
    for (const title of ['결제 실패', '방송 시작', '승인 결과']) {
      expect(screen.getByRole('group', { name: title })).toBeInTheDocument();
    }
  });

  it('인앱 중요 알림 스위치는 비활성이고 켜진 채 고정된다 — 끌 수 없다', () => {
    render(<NotificationSettingsScreen />);

    const locked = screen.getAllByRole('switch', { name: '인앱 — 항상 켜져 있어요' });
    expect(locked).toHaveLength(5);
    for (const sw of locked) {
      expect(sw).toBeChecked();
      expect(sw).toBeDisabled();
    }

    fireEvent.click(locked[0]!);
    expect(locked[0]!).toBeChecked();
  });

  it('끌 수 없다는 사실이 문구로도 보인다', () => {
    render(<NotificationSettingsScreen />);

    expect(screen.getByText('인앱 중요 알림은 항상 켜져 있어요')).toBeInTheDocument();
  });

  it('나머지 스위치가 토글된다', () => {
    render(<NotificationSettingsScreen />);

    // 일반 알림 — 인앱·이메일 둘 다 열려 있다
    const digestInapp = row('주간 성과 요약').getByRole('switch', { name: '인앱' });
    const digestEmail = row('주간 성과 요약').getByRole('switch', { name: '이메일' });
    expect(digestInapp).not.toBeChecked();
    expect(digestEmail).toBeChecked();

    fireEvent.click(digestInapp);
    fireEvent.click(digestEmail);
    expect(digestInapp).toBeChecked();
    expect(digestEmail).not.toBeChecked();

    // 중요 알림 — 이메일만 열려 있다
    const billingEmail = row('결제 실패').getByRole('switch', { name: '이메일' });
    expect(billingEmail).toBeChecked();
    fireEvent.click(billingEmail);
    expect(billingEmail).not.toBeChecked();

    // 한 행을 눌러도 옆 행은 그대로다
    expect(row('방송 시작').getByRole('switch', { name: '인앱' })).toBeChecked();
  });

  it('방송 중 방해 금지가 있고 토글된다', () => {
    render(<NotificationSettingsScreen />);

    expect(screen.getByText('라이브 중에는 중요 알림만 받아요')).toBeInTheDocument();
    const dnd = screen.getByRole('switch', { name: '방송 중 방해 금지' });
    expect(dnd).toBeChecked();

    fireEvent.click(dnd);
    expect(dnd).not.toBeChecked();
  });

  it('어디에도 저장하지 않는다 — 언마운트 후 다시 마운트하면 기본값이다', () => {
    // 의도를 잠그는 테스트. 저장 계층(localStorage·전역 스토어·모듈 캐시)이 무심코 들어오면
    // 여기서 깨진다. 백엔드를 붙이는 티켓이 이 테스트를 뒤집는 것이 정상 신호다.
    const first = render(<NotificationSettingsScreen />);

    fireEvent.click(screen.getByRole('switch', { name: '방송 중 방해 금지' }));
    fireEvent.click(row('주간 성과 요약').getByRole('switch', { name: '인앱' }));
    expect(window.localStorage.length).toBe(0);

    first.unmount();
    render(<NotificationSettingsScreen />);

    expect(screen.getByRole('switch', { name: '방송 중 방해 금지' })).toBeChecked();
    expect(row('주간 성과 요약').getByRole('switch', { name: '인앱' })).not.toBeChecked();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<NotificationSettingsScreen />);

    expect(await axe(container)).toHaveNoViolations();
  });
});
