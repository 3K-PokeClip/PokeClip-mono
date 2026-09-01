import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '@/api/auth';
import { AccountSettingsScreen } from '@/features/settings/account/AccountSettingsScreen';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch, type FetchHandler } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const nav = vi.hoisted(() => ({ search: '', replace: vi.fn(), push: vi.fn() }));

// jsdom은 라우팅을 못 흉내 낸다 — 이동 여부만 확인한다.
// 차단 상태 목업은 쿼리로 켜지므로 useSearchParams도 여기서 갈아 끼운다.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: nav.replace, push: nav.push }),
  useSearchParams: () => new URLSearchParams(nav.search),
  usePathname: () => '/settings/account',
}));

const ME: Me = {
  id: 1,
  email: 'raccoon.games@gmail.com',
  name: '게임하는너구리',
  profileImageUrl: 'https://example.test/a.png',
};
const ME_KEY = ['auth', 'me'] as const;

/**
 * me 조회는 기본으로 답하고, 수정 요청은 케이스가 정한다. 모든 URL에 ME를 돌려주면 PATCH도
 * 옛 이름을 줘 「저장이 다시 잠긴다」가 우연히 통과하면서 입력이 조용히 되감긴다 — 갈라 둔다.
 * 케이스가 정하지 않은 수정 요청은 404로 떨어져 단언에 걸린다.
 */
function stubAccount(me: Me = ME, handler?: FetchHandler) {
  return stubFetch((url, init) => {
    const method = init?.method ?? 'GET';
    if (url === '/api/auth/me' && method === 'GET') return jsonResponse(200, me);
    return handler ? handler(url, init) : jsonResponse(404, { reason: 'UNEXPECTED_CALL' });
  });
}

/** 수정 요청을 붙들어 둔다 — 「왕복 중」을 만든다. */
function heldResponse() {
  let release: ((res: Response) => void) | null = null;
  const promise = new Promise<Response>((resolve) => {
    release = resolve;
  });
  return { promise, release: (res: Response) => release?.(res) };
}

function bodyOf(init?: RequestInit): unknown {
  return JSON.parse(String(init?.body));
}

/** jsdom에는 캔버스가 없다 — 기본 아바타를 만들고 자르는 자리만 통과시킨다. */
function stubCanvas() {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    fillRect: vi.fn(),
    fillText: vi.fn(),
    translate: vi.fn(),
    rotate: vi.fn(),
    scale: vi.fn(),
    drawImage: vi.fn(),
  } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(
    'data:image/png;base64,PRESET',
  );
}

beforeEach(() => {
  window.localStorage.clear();
  nav.search = '';
  nav.replace.mockReset();
  nav.push.mockReset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
  stubAccount();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs(); // NODE_ENV 스텁이 다음 테스트로 새면 ?mock=blocked 분기가 죽는다
  vi.restoreAllMocks();
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

  it('사진 주소가 null이면 이니셜을 그린다 — 구글이 사진을 안 줬거나 창고가 꺼진 계정', async () => {
    stubAccount({ ...ME, profileImageUrl: null });
    await renderScreen();

    const profile = within(screen.getByRole('region', { name: '프로필' }));
    expect(profile.queryByRole('img')).toBeNull();
    expect(profile.getByText('게임')).toBeInTheDocument();
  });

  it('바뀐 것이 없으면 저장이 잠겨 있고, 고치면 풀린다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    const save = screen.getByRole('button', { name: '저장' });
    expect(save).toBeDisabled();

    await user.type(screen.getByLabelText('표시 이름'), '2');
    expect(save).toBeEnabled();
  });

  it('끝에 공백만 붙여서는 저장이 풀리지 않는다 — 저장은 양끝을 자른 값을 쓴다', async () => {
    const user = userEvent.setup();
    await renderScreen();

    await user.type(screen.getByLabelText('표시 이름'), '   ');

    // 풀리면 눌러도 값은 그대로인데 「변경했습니다」가 뜬다
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('me가 오기 전에는 저장이 잠겨 있다 — 갈아 끼울 대상이 없다', async () => {
    const user = userEvent.setup();
    stubFetch(() => new Promise<Response>(() => {})); // 영원히 미해결 — me가 오지 않는 상태
    renderWithProviders(<AccountSettingsScreen />);

    await user.type(await screen.findByLabelText('표시 이름'), '너구리씨');

    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.queryByText('표시 이름을 변경했습니다')).toBeNull();
  });

  it('me가 오기 전에는 사진 수정도 잠근다 — 올릴 주인이 없는데 모달만 열리지 않게', async () => {
    stubFetch(() => new Promise<Response>(() => {})); // 영원히 미해결
    renderWithProviders(<AccountSettingsScreen />);

    expect(await screen.findByRole('button', { name: '사진 수정' })).toBeDisabled();
  });

  it('me가 오기 전에는 탈퇴 진입점도 잠근다 — 이름 없는 인사말이 뜨지 않게', async () => {
    stubFetch(() => new Promise<Response>(() => {})); // 영원히 미해결
    renderWithProviders(<AccountSettingsScreen />);

    expect(await screen.findByRole('button', { name: '탈퇴하기' })).toBeDisabled();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = await renderScreen();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('AccountSettingsScreen — 표시 이름 저장', () => {
  it('저장은 PATCH /api/auth/me로 가고, 응답이 입력·캐시를 함께 바꾼다 — 헤더·사이드바가 읽는 캐시다', async () => {
    const user = userEvent.setup();
    const spy = stubAccount(ME, (url, init) =>
      url === '/api/auth/me' && init?.method === 'PATCH'
        ? jsonResponse(200, { ...ME, name: '너구리씨' })
        : jsonResponse(404),
    );
    const { queryClient } = await renderScreen();

    await user.clear(screen.getByLabelText('표시 이름'));
    await user.type(screen.getByLabelText('표시 이름'), '  너구리씨 ');
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText('표시 이름을 변경했습니다')).toBeInTheDocument();
    const patches = spy.mock.calls.filter(([, init]) => init?.method === 'PATCH');
    expect(patches).toHaveLength(1);
    // 양끝 공백은 서버도 자르지만 보내기 전에 잘라 dirty 판정과 저장값을 일치시킨다
    expect(bodyOf(patches[0]?.[1])).toEqual({ name: '너구리씨' });
    expect(queryClient.getQueryData<Me>(ME_KEY)?.name).toBe('너구리씨');
    // 저장한 값이 곧 현재 값이 되므로 입력은 응답을 따라가고 저장은 다시 잠긴다
    expect(screen.getByLabelText('표시 이름')).toHaveValue('너구리씨');
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('응답을 기다리는 동안 저장 버튼이 잠기고 바쁨을 알린다', async () => {
    const user = userEvent.setup();
    const held = heldResponse();
    stubAccount(ME, () => held.promise);
    await renderScreen();

    await user.type(screen.getByLabelText('표시 이름'), '2');
    await user.click(screen.getByRole('button', { name: '저장' }));

    const save = screen.getByRole('button', { name: /저장/ });
    expect(save).toHaveAttribute('aria-busy', 'true');
    expect(save).toBeDisabled();

    held.release(jsonResponse(200, { ...ME, name: '게임하는너구리2' }));
    expect(await screen.findByText('표시 이름을 변경했습니다')).toBeInTheDocument();
  });

  it('서버가 이름을 거절하면 입력 아래에 사유를 그린다 — 토스트가 아니고, 고치기 시작하면 걷힌다', async () => {
    const user = userEvent.setup();
    stubAccount(ME, () => jsonResponse(400, { reason: 'NAME_INVALID_CHARACTER' }));
    await renderScreen();

    await user.type(screen.getByLabelText('표시 이름'), '2');
    await user.click(screen.getByRole('button', { name: '저장' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('이름에 쓸 수 없는 문자가 있어요');
    expect(screen.getByLabelText('표시 이름')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByText('표시 이름을 저장하지 못했어요')).toBeNull();
    // 거절된 입력은 그대로 남는다 — 사용자가 고칠 자리다
    expect(screen.getByLabelText('표시 이름')).toHaveValue('게임하는너구리2');

    await user.type(screen.getByLabelText('표시 이름'), '3');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('30자를 넘기면 요청 없이 입력 아래에서 막는다 — 코드 포인트로 세서 이모지 30개는 통과한다', async () => {
    const spy = stubAccount(ME, () => jsonResponse(200, ME));
    await renderScreen();
    const input = screen.getByLabelText('표시 이름');

    fireEvent.change(input, { target: { value: '😀'.repeat(31) } });
    expect(screen.getByRole('alert')).toHaveTextContent('30자 이내로 입력해 주세요');
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();

    // String.length로 세면 60이라 막혔을 값 — 서버와 같은 단위로 세야 화면과 서버가 안 갈린다
    fireEvent.change(input, { target: { value: '😀'.repeat(30) } });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    expect(spy.mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(false);
  });

  it('왕복 중 더 친 글자는 성공 뒤에도 살아남는다', async () => {
    const user = userEvent.setup();
    const held = heldResponse();
    stubAccount(ME, () => held.promise);
    await renderScreen();

    await user.clear(screen.getByLabelText('표시 이름'));
    await user.type(screen.getByLabelText('표시 이름'), '너구리씨');
    await user.click(screen.getByRole('button', { name: '저장' }));
    await user.type(screen.getByLabelText('표시 이름'), '2'); // 응답이 오기 전에 더 친다

    held.release(jsonResponse(200, { ...ME, name: '너구리씨' }));
    await screen.findByText('표시 이름을 변경했습니다');

    // 성공 콜백이 서버 값으로 덮으면 「2」가 사라진다 — 제출한 값 그대로일 때만 되돌린다
    expect(screen.getByLabelText('표시 이름')).toHaveValue('너구리씨2');
    expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
  });

  it('입력 탓이 아닌 실패는 토스트로 알리고 입력은 그대로 둔다', async () => {
    const user = userEvent.setup();
    stubAccount(ME, () => jsonResponse(503, { message: '점검 중' }));
    const { queryClient } = await renderScreen();

    await user.type(screen.getByLabelText('표시 이름'), '2');
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText('표시 이름을 저장하지 못했어요')).toBeInTheDocument();
    expect(screen.getByLabelText('표시 이름')).toHaveValue('게임하는너구리2');
    expect(screen.getByLabelText('표시 이름')).not.toHaveAttribute('aria-invalid');
    expect(queryClient.getQueryData<Me>(ME_KEY)?.name).toBe('게임하는너구리');
  });
});

describe('AccountSettingsScreen — 프로필 사진', () => {
  const NEW_URL = 'http://localhost:8082/api/profile-photos/1?token=fresh';

  /** 사진 수정 → 기본 아바타 → 크롭 디코드 완료까지. */
  async function openPhotoCrop(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: '사진 수정' }));
    const dialog = within(screen.getByRole('dialog'));
    await user.click(dialog.getByRole('button', { name: '기본 아바타 1' }));
    fireEvent.load(document.querySelector('[role="dialog"] img') as HTMLImageElement);
    return dialog;
  }

  it('적용하면 PUT /api/auth/me/photo에 multipart로 올리고, 응답 주소로 캐시가 바뀐다', async () => {
    stubCanvas();
    const user = userEvent.setup();
    const spy = stubAccount(ME, (url, init) =>
      url === '/api/auth/me/photo' && init?.method === 'PUT'
        ? jsonResponse(200, { ...ME, profileImageUrl: NEW_URL })
        : jsonResponse(404),
    );
    const { queryClient } = await renderScreen();
    const dialog = await openPhotoCrop(user);

    await user.click(dialog.getByRole('button', { name: '적용' }));

    expect(await screen.findByText('프로필 사진을 변경했습니다')).toBeInTheDocument();
    const puts = spy.mock.calls.filter(([, init]) => init?.method === 'PUT');
    expect(puts).toHaveLength(1);
    const body = puts[0]?.[1]?.body;
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBeInstanceOf(Blob);
    // 헤더·사이드바가 읽는 캐시가 서버 응답의 새 주소를 갖는다 — 재조회 없이
    expect(queryClient.getQueryData<Me>(ME_KEY)?.profileImageUrl).toBe(NEW_URL);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('사진 창고가 꺼져 있으면(503) 모달을 닫지 않고 사유를 알리며 캐시는 그대로다', async () => {
    stubCanvas();
    const user = userEvent.setup();
    stubAccount(ME, () => jsonResponse(503, { reason: 'PHOTO_STORAGE_DISABLED' }));
    const { queryClient } = await renderScreen();
    const dialog = await openPhotoCrop(user);

    await user.click(dialog.getByRole('button', { name: '적용' }));

    expect(
      await dialog.findByText('지금은 사진을 올릴 수 없어요 · 잠시 후 다시 시도해 주세요'),
    ).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(queryClient.getQueryData<Me>(ME_KEY)?.profileImageUrl).toBe(ME.profileImageUrl);
    expect(screen.queryByText('프로필 사진을 변경했습니다')).toBeNull();
  });

  it('업로드 중 취소하면 me를 다시 읽는다 — 서버는 창고에 먼저 쓰므로 이미 올라갔을 수 있다', async () => {
    stubCanvas();
    const user = userEvent.setup();
    const held = heldResponse();
    const spy = stubAccount(ME, () => held.promise);
    await renderScreen();
    const dialog = await openPhotoCrop(user);
    const meCallsBefore = spy.mock.calls.filter(
      ([url, init]) => url === '/api/auth/me' && (init?.method ?? 'GET') === 'GET',
    ).length;

    await user.click(dialog.getByRole('button', { name: '적용' }));
    await user.click(dialog.getByRole('button', { name: '취소' }));

    await waitFor(() => {
      const meCalls = spy.mock.calls.filter(
        ([url, init]) => url === '/api/auth/me' && (init?.method ?? 'GET') === 'GET',
      ).length;
      expect(meCalls).toBe(meCallsBefore + 1);
    });
    expect(dialog.getByText('2 / 3 · 크롭')).toBeInTheDocument();
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
