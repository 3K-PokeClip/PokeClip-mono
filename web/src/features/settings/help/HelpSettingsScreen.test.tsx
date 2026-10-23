import type { AnchorHTMLAttributes, ReactNode } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import { FaqList } from './FaqList';
import { useOnboardingStore } from '@/stores/onboarding';
import { FAQ_ITEMS, FAQ_TOPICS, type FaqKey } from './faqItems';
import { HelpSettingsScreen } from './HelpSettingsScreen';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', () => ({ track }));

// Next Link는 jsdom에서 미리 불러오기 상태를 act 밖에서 고치고(경고), 누르면 jsdom이 못 하는 문서 이동을
// 시도한다. 여기서는 주소·onClick만 보면 되므로 순수 a로 바꾸고 이동은 막는다(ChzzkCallbackScreen 테스트와 같은 방식)
vi.mock('next/link', () => ({
  default: ({
    children,
    onClick,
    ...rest
  }: { children?: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a
      {...rest}
      onClick={(e) => {
        onClick?.(e);
        e.preventDefault();
      }}
    >
      {children}
    </a>
  ),
}));

// 문항은 고를 때마다 바뀐다 — 동작 테스트는 문구에 기대지 않게 지어낸 목록으로 돈다
const ITEMS = [
  {
    key: 'q-steps' as FaqKey,
    topic: 'start' as const,
    q: '순서가 있는 질문',
    a: '순서가 있는 답',
    steps: ['첫째 할 일', '둘째 할 일'],
    link: { label: '채널 연동 열기', href: '/settings/channels' },
  },
  { key: 'q-plain' as FaqKey, topic: 'start' as const, q: '그냥 질문', a: '그냥 답' },
];

const inTopic = (topic: (typeof FAQ_TOPICS)[number]['key']) =>
  FAQ_ITEMS.filter((item) => item.topic === topic);

const question = (name: string) => screen.getByRole('button', { name });

beforeEach(() => {
  track.mockClear();
  // startTour가 온보딩 플래그를 localStorage에 남긴다 — 다음 테스트로 새지 않게 지운다
  window.localStorage.clear();
});

describe('HelpSettingsScreen', () => {
  it('가이드 카드 3개 · 질문 목록 · 문의 카드가 시안 순서대로 뜬다', () => {
    render(<HelpSettingsScreen />);

    expect(screen.getByRole('heading', { level: 1, name: '도움말 · 문의' })).toBeInTheDocument();
    // 가이드 문서가 없어 카드는 그 일을 하는 화면으로 보낸다
    expect(screen.getByRole('link', { name: /시작 가이드/ })).toHaveAttribute('href', '/home');
    expect(screen.getByRole('link', { name: /클립 편집 가이드/ })).toHaveAttribute(
      'href',
      '/broadcast/vod',
    );
    expect(screen.getByRole('link', { name: /편집자 협업 가이드/ })).toHaveAttribute(
      'href',
      '/settings/editors',
    );

    expect(screen.getByRole('heading', { level: 2, name: '자주 묻는 질문' })).toBeInTheDocument();
    // 처음엔 첫 주제(시작하기)의 질문만, 전부 닫힌 채로 보인다 (시안 ①)
    const toggles = screen.getAllByRole('button', { expanded: false });
    expect(toggles).toHaveLength(inTopic('start').length);
    expect(toggles[0]).toHaveAccessibleName(FAQ_ITEMS[0].q);
    expect(screen.queryByRole('region')).toBeNull();
  });

  it('질문은 주제 탭으로 나뉜다 — 탭마다 개수가 보이고, 고른 주제의 질문만 보인다', () => {
    render(<HelpSettingsScreen />);

    const tabs = screen.getAllByRole('tab');
    // 낭독 이름은 「플러그인, 질문 5개」 — 화면의 숫자만 붙으면 「플러그인5」로 읽힌다
    tabs.forEach((tab, i) => {
      const t = FAQ_TOPICS[i]!;
      expect(tab).toHaveAccessibleName(`${t.label}, 질문 ${inTopic(t.key).length}개`);
    });
    expect(tabs).toHaveLength(FAQ_TOPICS.length);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    // 모든 질문이 어느 한 주제에 들어 있다 — 주제 없는 질문은 화면에서 사라진다
    expect(FAQ_TOPICS.reduce((n, t) => n + inTopic(t.key).length, 0)).toBe(FAQ_ITEMS.length);

    fireEvent.click(screen.getByRole('tab', { name: /편집자/ }));
    const panel = within(screen.getByRole('tabpanel'));
    expect(panel.getAllByRole('button', { expanded: false })).toHaveLength(
      inTopic('editor').length,
    );
    expect(screen.queryByRole('button', { name: FAQ_ITEMS[0].q })).toBeNull();
  });

  it('시작 가이드는 처음 사용 안내를 첫 단계부터 다시 켠다 — 홈이 그 상태를 읽어 투어를 띄운다', () => {
    useOnboardingStore.setState({ tourStep: null });
    render(<HelpSettingsScreen />);

    fireEvent.click(screen.getByRole('link', { name: /시작 가이드/ }));
    expect(useOnboardingStore.getState().tourStep).toBe(0);
  });

  it('수정 키를 누른 클릭(새 탭·창)은 투어를 켜지 않는다 — 이 탭은 홈으로 가지 않는다', () => {
    useOnboardingStore.setState({ tourStep: null });
    render(<HelpSettingsScreen />);

    const start = screen.getByRole('link', { name: /시작 가이드/ });
    for (const modifier of ['metaKey', 'ctrlKey', 'shiftKey', 'altKey'] as const) {
      fireEvent.click(start, { [modifier]: true });
      expect(useOnboardingStore.getState().tourStep).toBeNull();
    }
  });

  it('주제 탭을 오가도 평가는 질문마다 한 번이다 — 탭을 바꿔도 평가 기록이 남는다', () => {
    render(<HelpSettingsScreen />);

    fireEvent.click(question(FAQ_ITEMS[0].q));
    fireEvent.click(screen.getByRole('button', { name: '도움이 되었어요' }));
    expect(track).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('tab', { name: /플러그인/ }));
    fireEvent.click(screen.getByRole('tab', { name: /시작하기/ }));

    // 돌아오면 펼침은 닫혀 있다(시안 ① 전부 닫힘) — 평가 기록만 남는다
    const toggle = question(FAQ_ITEMS[0].q);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    // 같은 질문을 다시 열면 버튼 대신 완료 문구다 — 다시 보낼 길이 없다
    fireEvent.click(toggle);
    expect(screen.getByText('피드백을 보냈어요 · 더 나은 답변에 반영할게요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '도움이 되었어요' })).toBeNull();
    expect(track).toHaveBeenCalledTimes(1);
  });

  it('평가 이벤트의 질문 key 목록(events.ts FaqKey)과 화면 질문이 양쪽으로 같다', () => {
    // 화면 쪽 ⊆ FaqKey는 faqItems.ts의 satisfies가, FaqKey ⊆ 화면 쪽은 이 줄이 컴파일 때 잡는다 —
    // 질문을 지우며 events.ts를 그대로 두면 다시 오지 않는 key가 열거에 남는다
    expectTypeOf<Exclude<FaqKey, (typeof FAQ_ITEMS)[number]['key']>>().toEqualTypeOf<never>();
    expect(new Set(FAQ_ITEMS.map((item) => item.key)).size).toBe(FAQ_ITEMS.length);
  });

  it('다른 가이드 카드는 투어를 건드리지 않는다', () => {
    useOnboardingStore.setState({ tourStep: null });
    render(<HelpSettingsScreen />);

    fireEvent.click(screen.getByRole('link', { name: /편집자 협업 가이드/ }));
    expect(useOnboardingStore.getState().tourStep).toBeNull();
  });

  it('문의는 디스코드 1:1(새 탭)과 이메일 두 갈래이고, 메일 주소를 글로도 보인다 — 문의 API가 없다', () => {
    render(<HelpSettingsScreen />);

    const discord = screen.getByRole('link', { name: '디스코드 1:1' });
    expect(discord).toHaveAttribute('href', 'https://discord.gg/rJM9egeuwr');
    expect(discord).toHaveAttribute('target', '_blank');
    expect(discord).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByRole('link', { name: '이메일' })).toHaveAttribute(
      'href',
      'mailto:3k.pokeclip@gmail.com',
    );
    expect(screen.getByText('3k.pokeclip@gmail.com')).toBeInTheDocument();
  });

  it('접근성 위반이 없다 — 질문 하나를 펼친 상태까지', async () => {
    const { container } = render(<HelpSettingsScreen />);
    fireEvent.click(question(FAQ_ITEMS[0].q));

    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('FaqList', () => {
  it('질문을 누르면 답이 펼쳐지고, 다른 질문을 열면 앞의 것이 닫힌다 — 한 번에 하나 (시안 ②)', () => {
    render(<FaqList items={ITEMS} />);

    fireEvent.click(question('순서가 있는 질문'));
    expect(question('순서가 있는 질문')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('region', { name: '순서가 있는 질문' })).toHaveTextContent(
      '순서가 있는 답',
    );

    fireEvent.click(question('그냥 질문'));
    expect(question('순서가 있는 질문')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('순서가 있는 답')).toBeNull();
    expect(screen.getByText('그냥 답')).toBeInTheDocument();

    // 열린 질문을 다시 누르면 닫힌다
    fireEvent.click(question('그냥 질문'));
    expect(screen.queryByRole('region')).toBeNull();
  });

  it('순서가 있는 답은 번호 목록으로, 앱 안 화면 링크는 있는 질문에만 그린다', () => {
    render(<FaqList items={ITEMS} />);

    fireEvent.click(question('순서가 있는 질문'));
    const panel = within(screen.getByRole('region'));
    // list-style: none 목록은 Safari가 목록 의미를 벗긴다 — 역할을 명시해야 VoiceOver가 순서를 읽는다
    expect(panel.getByRole('list')).toHaveAttribute('role', 'list');
    expect(panel.getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      '1첫째 할 일',
      '2둘째 할 일',
    ]);
    expect(panel.getByRole('link', { name: '채널 연동 열기' })).toHaveAttribute(
      'href',
      '/settings/channels',
    );

    fireEvent.click(question('그냥 질문'));
    expect(within(screen.getByRole('region')).queryByRole('list')).toBeNull();
    expect(within(screen.getByRole('region')).queryByRole('link')).toBeNull();
  });

  it('평가를 누르면 질문 key와 답을 보내고 완료 문구로 바뀐다 — 포커스가 문구로 옮겨 간다 (시안 ③)', () => {
    render(<FaqList items={ITEMS} />);

    fireEvent.click(question('순서가 있는 질문'));
    fireEvent.click(screen.getByRole('button', { name: '도움이 되었어요' }));

    expect(track).toHaveBeenCalledTimes(1);
    expect(track).toHaveBeenCalledWith('faq_feedback_sent', { faq: 'q-steps', helpful: true });
    const thanks = screen.getByText('피드백을 보냈어요 · 더 나은 답변에 반영할게요');
    expect(thanks).toHaveFocus();
    expect(screen.queryByRole('button', { name: '도움이 되었어요' })).toBeNull();
  });

  it('도움이 되지 않았어요는 helpful=false로 보낸다', () => {
    render(<FaqList items={ITEMS} />);

    fireEvent.click(question('그냥 질문'));
    fireEvent.click(screen.getByRole('button', { name: '도움이 되지 않았어요' }));

    expect(track).toHaveBeenCalledTimes(1);
    expect(track).toHaveBeenCalledWith('faq_feedback_sent', { faq: 'q-plain', helpful: false });
  });

  it('평가는 질문마다 한 번 — 닫았다 다시 열어도 버튼 대신 완료 문구이고, 포커스는 펼침 버튼에 남는다', () => {
    render(<FaqList items={ITEMS} />);

    fireEvent.click(question('그냥 질문'));
    fireEvent.click(screen.getByRole('button', { name: '도움이 되었어요' }));
    fireEvent.click(question('그냥 질문'));

    const toggle = question('그냥 질문');
    toggle.focus();
    fireEvent.click(toggle);

    expect(screen.getByText('피드백을 보냈어요 · 더 나은 답변에 반영할게요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '도움이 되었어요' })).toBeNull();
    expect(toggle).toHaveFocus();
    expect(track).toHaveBeenCalledTimes(1);

    // 다른 질문은 아직 평가하지 않았다
    fireEvent.click(question('순서가 있는 질문'));
    expect(screen.getByRole('button', { name: '도움이 되었어요' })).toBeInTheDocument();
  });
});
