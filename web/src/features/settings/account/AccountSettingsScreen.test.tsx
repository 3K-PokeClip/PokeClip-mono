import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountSettingsScreen } from '@/features/settings/account/AccountSettingsScreen';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const nav = vi.hoisted(() => ({ search: '', replace: vi.fn(), push: vi.fn() }));

// jsdom은 라우팅을 못 흉내 낸다 — 이동 여부만 확인한다.
// 차단 상태 목업은 쿼리로 켜지므로 useSearchParams도 여기서 갈아 끼운다.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: nav.replace, push: nav.push }),
  useSearchParams: () => new URLSearchParams(nav.search),
  usePathname: () => '/settings/account',
}));

const ME = {
  id: 1,
  email: 'raccoon.games@gmail.com',
  name: '게임하는너구리',
  profileImageUrl: 'https://example.test/a.png',
};

beforeEach(() => {
  window.localStorage.clear();
  nav.search = '';
  nav.replace.mockReset();
  nav.push.mockReset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  stubFetch(() => jsonResponse(200, ME));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function renderScreen() {
  const view = renderWithProviders(<AccountSettingsScreen />);
  await screen.findByDisplayValue('게임하는너구리');
  return view;
}

describe('AccountSettingsScreen — 표시', () => {
  it('프로필·이메일·로그인 수단이 시안대로 뜬다', async () => {
    await renderScreen();

    expect(screen.getByRole('heading', { name: '계정' })).toBeInTheDocument();
    expect(screen.getByLabelText('표시 이름')).toHaveValue('게임하는너구리');

    // 이메일은 구글에서 오는 값이라 잠겨 있다
    expect(screen.getByLabelText('이메일')).toBeDisabled();
    expect(screen.getByLabelText('이메일')).toHaveValue('raccoon.games@gmail.com');

    const login = within(screen.getByRole('region', { name: '로그인' }));
    expect(login.getByText('Google로 로그인')).toBeInTheDocument();
    expect(login.getByText('연결됨')).toBeInTheDocument();
  });

  it('바뀐 것이 없으면 저장이 잠겨 있고, 고치면 풀린다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    const save = screen.getByRole('button', { name: '저장' });
    expect(save).toBeDisabled();

    await user.type(screen.getByLabelText('표시 이름'), '2');
    expect(save).toBeEnabled();
  });

  it('끝에 공백만 붙여서는 저장이 풀리지 않는다 — 저장은 트림 후 값을 쓴다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    await user.type(screen.getByLabelText('표시 이름'), '   ');

    // 풀리면 눌러도 값은 그대로인데 「변경했습니다」가 뜬다
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('me가 오기 전에는 저장이 잠겨 있다 — 갈아 끼울 대상이 없다', async () => {
    const user = userEvent.setup();
    // me를 영원히 붙들어 둔다
    stubFetch(() => new Promise<Response>(() => {})); // 영원히 미해결 — me가 오지 않는 상태
    renderWithProviders(<AccountSettingsScreen />);

    await user.type(await screen.findByLabelText('표시 이름'), '너구리씨');

    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.queryByText('표시 이름을 변경했습니다')).toBeNull();
  });

  it('이름을 저장하면 토스트가 결과를 알린다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    await user.clear(screen.getByLabelText('표시 이름'));
    await user.type(screen.getByLabelText('표시 이름'), '너구리씨');
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText('표시 이름을 변경했습니다')).toBeInTheDocument();
    // 저장한 값이 곧 현재 값이 되므로 저장은 다시 잠긴다
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = await renderScreen();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('AccountSettingsScreen — 탈퇴', () => {
  it('탈퇴 진입점은 구분선 아래 저강도 링크다 — DS 버튼이 아니다', async () => {
    await renderScreen();

    const withdraw = screen.getByRole('button', { name: '탈퇴하기' });
    // DS Button은 언제나 data-variant를 단다. 그것이 없다는 것이 「버튼이 아니다」의 증거다
    expect(withdraw).not.toHaveAttribute('data-variant');
  });

  it('모달이 지워지는 것과 안 지워지는 것을 갈라 말하고 실제 수치를 보여 준다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    await user.click(screen.getByRole('button', { name: '탈퇴하기' }));
    const dialog = within(screen.getByRole('dialog'));

    expect(dialog.getByText('게임하는너구리님, 정말 탈퇴하시나요?')).toBeInTheDocument();

    // 지워지는 것 — 복구할 수 없음이 문장에 있다
    expect(
      dialog.getByText(
        /보관함의 클립·하이라이트 카드·자동 처리 설정이 모두 삭제되며 복구할 수 없습니다/,
      ),
    ).toBeInTheDocument();
    // 안 지워지는 것
    expect(
      dialog.getByText(/이미 게시된 영상은 삭제되지 않으며, 해당 채널에서 직접 관리해야 합니다/),
    ).toBeInTheDocument();

    expect(dialog.getByText('42개')).toBeInTheDocument();
    expect(dialog.getByText('128개')).toBeInTheDocument();
    expect(dialog.getByText('23일')).toBeInTheDocument();
  });

  it('취소는 아무 일 없이 이탈한다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    await user.click(screen.getByRole('button', { name: '탈퇴하기' }));
    await user.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(nav.replace).not.toHaveBeenCalled();
    expect(useAuthStore.getState().refreshToken).toBe('refresh-1');
  });

  it('배경을 눌러도 아무 일 없이 이탈한다 — 실수로 진행되지 않는다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    await user.click(screen.getByRole('button', { name: '탈퇴하기' }));
    fireEvent.pointerDown(document.body);

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(nav.replace).not.toHaveBeenCalled();
    expect(useAuthStore.getState().refreshToken).toBe('refresh-1');
  });

  it('확정하면 세션은 그대로 둔 채 탈퇴 완료 화면으로 보낸다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    await user.click(screen.getByRole('button', { name: '탈퇴하기' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '탈퇴하기' }));

    expect(nav.replace).toHaveBeenCalledWith('/goodbye');
    // 여기서 토큰을 지우면 그 리렌더로 깨어난 AuthGuard가 /login으로 보내 이동을 채간다.
    // 세션 정리는 가드 밖인 /goodbye가 맡는다 — 표식만 남기고 나간다.
    expect(useAuthStore.getState().refreshToken).toBe('refresh-1');
    expect(window.sessionStorage.getItem('pc-withdrawn')).toBe('1');
  });

  it('미결제 잔액이 있으면 재확인 대신 차단 상태가 뜬다', async () => {
    const user = userEvent.setup();
    nav.search = 'mock=blocked';
    await renderScreen();

    await user.click(screen.getByRole('button', { name: '탈퇴하기' }));
    const dialog = within(screen.getByRole('dialog'));

    expect(dialog.getByText('지금은 탈퇴할 수 없어요')).toBeInTheDocument();
    expect(dialog.getByText('₩12,900')).toBeInTheDocument();
    // 막힌 상태에서는 확정 버튼 자체가 없다
    expect(dialog.queryByRole('button', { name: '탈퇴하기' })).not.toBeInTheDocument();

    await user.click(dialog.getByRole('button', { name: '결제 내역으로 이동' }));
    expect(nav.push).toHaveBeenCalledWith('/settings/billing');
  });

  it('프로덕션에서는 목업 토글이 죽어 없는 청구 금액이 뜨지 않는다', async () => {
    const user = userEvent.setup();
    nav.search = 'mock=blocked';
    vi.stubEnv('NODE_ENV', 'production');
    await renderScreen();

    await user.click(screen.getByRole('button', { name: '탈퇴하기' }));

    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.queryByText('₩12,900')).toBeNull();
    expect(dialog.getByRole('button', { name: '탈퇴하기' })).toBeInTheDocument();
  });
});
