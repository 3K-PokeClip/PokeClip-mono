import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EditorSettingsScreen } from '@/features/settings/editors/EditorSettingsScreen';
import { EDITORS_POLL_MS } from '@/features/settings/editors/useEditorSettingsState';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/analytics')>()),
  track,
}));

const DELEGATIONS_URL = '/api/editor-delegations/as-streamer';
const SENT_URL = '/api/editor-invitations/sent';
const INVITE_URL = '/api/editor-invitations';

// 서버 응답 원형(Wire) 그대로 — counterpartId·inviteeId가 화면에 새지 않는 것도 검증 대상이다.
const DELEGATIONS = [
  {
    id: 3,
    counterpartId: 71,
    counterpartName: '박편집',
    counterpartProfileImageUrl: 'https://img.example/park.png',
    grantedAt: '2026-05-12T09:00:00Z',
  },
  {
    id: 4,
    counterpartId: 72,
    counterpartName: '김컷',
    counterpartProfileImageUrl: null,
    grantedAt: '2026-07-03T09:00:00Z',
  },
];

/** 만료 라벨이 실시간 now를 쓰므로 기준 시각에서 상대값으로 만든다. */
const inFuture = (days: number) => new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString();

const SENT = [
  {
    id: 11,
    inviteeId: 81,
    inviteeName: '컷마스터',
    inviteeEmail: 'cut.master@gmail.com',
    status: 'PENDING',
    expiresAt: inFuture(6.9),
    createdAt: '2026-08-25T09:00:00Z',
  },
  // 이력 — 화면에 나오면 안 되는 것들
  {
    id: 10,
    inviteeId: 82,
    inviteeName: '수락한편집자',
    inviteeEmail: 'accepted@example.com',
    status: 'ACCEPTED',
    expiresAt: inFuture(3),
    createdAt: '2026-08-20T09:00:00Z',
  },
  {
    id: 9,
    inviteeId: 83,
    inviteeName: '만료된초대',
    inviteeEmail: 'expired@example.com',
    status: 'EXPIRED',
    expiresAt: '2026-08-01T09:00:00Z',
    createdAt: '2026-07-25T09:00:00Z',
  },
];

/** 케이스별 동작(주로 뮤테이션)이 먼저 가로채고, 두 GET은 기본 응답으로 떨어진다. */
type PartialHandler = (url: string, init?: RequestInit) => Response | undefined;

function stubEditors(override?: PartialHandler) {
  return stubFetch((url, init) => {
    const intercepted = override?.(url, init);
    if (intercepted) return intercepted;
    if (url === DELEGATIONS_URL) return jsonResponse(200, DELEGATIONS);
    if (url === SENT_URL) return jsonResponse(200, SENT);
    throw new Error(`unexpected fetch: ${init?.method ?? 'GET'} ${url}`);
  });
}

/** 편집자도 대기 초대도 없는 응답 — 빈 상태(1l ④) 케이스 공용. */
function stubEmptyEditors() {
  return stubFetch((url) => {
    if (url === DELEGATIONS_URL || url === SENT_URL) return jsonResponse(200, []);
    throw new Error(`unexpected fetch: ${url}`);
  });
}

/** 행 안으로 범위를 좁힌다 — 「회수」·「취소」 버튼이 행마다 있어 이름만으로는 구분되지 않는다. */
const row = (name: string | RegExp) => within(screen.getByRole('group', { name }));

async function openInviteDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '편집자 초대' }));
  return screen.findByRole('dialog', { name: '편집자 초대' });
}

beforeEach(() => {
  window.localStorage.clear();
  track.mockReset();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('EditorSettingsScreen — 목록', () => {
  it('로딩 동안 스켈레톤이 행 자리를 지킨다', () => {
    stubFetch(() => new Promise<Response>(() => {}));
    const { container } = renderWithProviders(<EditorSettingsScreen />);

    expect(screen.getByRole('heading', { name: '편집자 관리' })).toBeInTheDocument();
    expect(container.querySelectorAll('[aria-hidden="true"]').length).toBeGreaterThan(0);
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });

  it('편집자 행은 이름·합류일·내보내기 버튼, 대기 초대 행은 이메일·만료일·취소 버튼이다', async () => {
    stubEditors();
    renderWithProviders(<EditorSettingsScreen />);

    const park = within(await screen.findByRole('group', { name: '박편집' }));
    expect(park.getByText('5월 12일 합류')).toBeInTheDocument();
    expect(park.getByRole('button', { name: '내보내기' })).toBeInTheDocument();
    expect(row('김컷').getByText('7월 3일 합류')).toBeInTheDocument();

    const pending = row(/cut\.master@gmail\.com/);
    expect(pending.getByText('7일 후 만료')).toBeInTheDocument();
    expect(pending.getByRole('button', { name: '취소' })).toBeInTheDocument();
  });

  it('편집자 행의 아바타는 프로필 사진을 그리고, 사진이 없으면 이니셜로 선다', async () => {
    stubEditors();
    renderWithProviders(<EditorSettingsScreen />);

    const park = within(await screen.findByRole('group', { name: '박편집' }));
    expect(park.getByRole('img', { name: '박편집' })).toHaveAttribute(
      'src',
      'https://img.example/park.png',
    );
    expect(row('김컷').queryByRole('img')).not.toBeInTheDocument();
  });

  it('sent 이력 중 PENDING만 그린다 — 수락·만료된 초대는 나오지 않는다', async () => {
    stubEditors();
    renderWithProviders(<EditorSettingsScreen />);

    await screen.findByRole('group', { name: '박편집' });
    expect(screen.getAllByRole('group')).toHaveLength(3); // 편집자 2 + 대기 초대 1
    expect(screen.queryByText(/accepted@example\.com/)).not.toBeInTheDocument();
    expect(screen.queryByText(/expired@example\.com/)).not.toBeInTheDocument();
  });

  it('편집자도 대기 초대도 없으면 빈 상태가 선다 — 초대 진입점은 헤더 버튼 하나다 (1l ④)', async () => {
    stubEmptyEditors();
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);

    expect(await screen.findByText('아직 편집자가 없어요')).toBeInTheDocument();
    expect(screen.getByText(/하이라이트 검토와 클립 편집을 맡길 수 있어요/)).toBeInTheDocument();
    // 업로드 승인 게이트가 없다(POK-254): 편집자도 영상을 만들고 바로 올린다
    expect(
      screen.getByText(/편집자도 영상을 만들고 바로 유튜브에 올릴 수 있어요/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/승인/)).not.toBeInTheDocument();

    // 하단 안내와 「권한 2단계 비교」 트리거는 편집자 0명이어도 항상 있다
    expect(screen.getByText(/편집자를 내보내면 즉시 적용됩니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '권한 2단계 비교' })).toBeInTheDocument();

    // 1l ④에는 카드 안 초대 버튼이 없다 — 같은 이름의 버튼은 헤더 하나뿐이어야 한다
    await user.click(screen.getByRole('button', { name: '편집자 초대' }));
    expect(await screen.findByRole('dialog', { name: '편집자 초대' })).toBeInTheDocument();
  });

  it('최초 조회 실패는 오류 카드로 서고, 다시 시도가 성공하면 목록으로 회복된다', async () => {
    let failing = true;
    stubFetch((url) => {
      if (url === DELEGATIONS_URL)
        return failing ? jsonResponse(500) : jsonResponse(200, DELEGATIONS);
      if (url === SENT_URL) return failing ? jsonResponse(500) : jsonResponse(200, SENT);
      throw new Error(`unexpected fetch: ${url}`);
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);

    expect(await screen.findByText('편집자 목록을 불러오지 못했어요')).toBeInTheDocument();

    failing = false;
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('group', { name: '박편집' })).toBeInTheDocument();
  });
});

describe('EditorSettingsScreen — 초대', () => {
  it('이메일을 보내면 모달이 닫히고 성공 토스트가 뜨며 두 목록을 다시 읽는다', async () => {
    const spy = stubEditors((url, init) => {
      if (url === INVITE_URL && init?.method === 'POST') {
        expect(JSON.parse(init.body as string)).toEqual({ email: 'editor@example.com' });
        return jsonResponse(201, {});
      }
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    const dialog = within(await openInviteDialog(user));
    // 시안의 「초대 링크」·「24시간」을 따르지 않는다 — 만료 안내는 7일이다
    expect(dialog.getByText(/7일 후 만료돼요/)).toBeInTheDocument();
    expect(dialog.queryByText(/초대 링크/)).not.toBeInTheDocument();

    await user.type(dialog.getByLabelText('이메일'), 'editor@example.com');
    const listCallsBefore = spy.mock.calls.filter(
      ([url]) => url === DELEGATIONS_URL || url === SENT_URL,
    ).length;
    await user.click(dialog.getByRole('button', { name: '초대 보내기' }));

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '편집자 초대' })).not.toBeInTheDocument(),
    );
    expect(await screen.findByText('초대를 보냈어요')).toBeInTheDocument();
    await waitFor(() => {
      const listCalls = spy.mock.calls.filter(
        ([url]) => url === DELEGATIONS_URL || url === SENT_URL,
      ).length;
      expect(listCalls).toBe(listCallsBefore + 2);
    });
    // 초대 성공 뒤 한 번 센다 — 이메일은 싣지 않는다 (POK-357)
    expect(track.mock.calls).toEqual([['editor_invited', {}]]);
  });

  it.each([
    [400, 'SELF_INVITE', '자기 자신은 초대할 수 없어요'],
    [404, 'INVITEE_NOT_FOUND', '그 이메일로 가입한 계정이 없어요'],
    [409, 'ALREADY_EDITOR', '이미 편집자예요'],
    [409, 'TOO_MANY_PENDING', '대기 중인 초대가 가득 찼어요'],
  ])('%i %s 거절은 모달을 닫지 않고 사유를 그 자리에 그린다', async (status, reason, title) => {
    stubEditors((url, init) => {
      if (url === INVITE_URL && init?.method === 'POST') return jsonResponse(status, { reason });
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    const dialog = within(await openInviteDialog(user));
    await user.type(dialog.getByLabelText('이메일'), 'someone@example.com');
    await user.click(dialog.getByRole('button', { name: '초대 보내기' }));

    const alert = await dialog.findByRole('alert');
    expect(alert).toHaveTextContent(title);
    expect(screen.getByRole('dialog', { name: '편집자 초대' })).toBeInTheDocument();
    expect(track).not.toHaveBeenCalled(); // 거절된 초대는 세지 않는다
  });

  it('정원이 차면 자리 비우는 방법(취소)이 사유에 담긴다 — 내보내기는 효과 없는 해법이라 없다', async () => {
    stubEditors((url, init) => {
      if (url === INVITE_URL && init?.method === 'POST')
        return jsonResponse(409, { reason: 'TOO_MANY_PENDING' });
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    const dialog = within(await openInviteDialog(user));
    await user.type(dialog.getByLabelText('이메일'), 'one.more@example.com');
    await user.click(dialog.getByRole('button', { name: '초대 보내기' }));

    const alert = await dialog.findByRole('alert');
    expect(alert).toHaveTextContent('취소');
    // 이 409는 PENDING 초대 수만 센다 — 내보내기를 권하면 실행해도 소용없는 안내가 된다
    expect(alert).not.toHaveTextContent('내보내');
  });

  it('실패 문구는 모달을 닫았다 다시 열면 남지 않는다', async () => {
    stubEditors((url, init) => {
      if (url === INVITE_URL && init?.method === 'POST')
        return jsonResponse(404, { reason: 'INVITEE_NOT_FOUND' });
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    let dialog = within(await openInviteDialog(user));
    await user.type(dialog.getByLabelText('이메일'), 'ghost@example.com');
    await user.click(dialog.getByRole('button', { name: '초대 보내기' }));
    await dialog.findByRole('alert');

    await user.click(dialog.getByRole('button', { name: '취소' }));
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '편집자 초대' })).not.toBeInTheDocument(),
    );

    dialog = within(await openInviteDialog(user));
    expect(dialog.queryByRole('alert')).not.toBeInTheDocument();
    expect(dialog.getByLabelText('이메일')).toHaveValue('');
  });
});

describe('EditorSettingsScreen — 내보내기', () => {
  it('내보내기는 확인 모달을 거친다 — 취소하면 DELETE가 나가지 않는다', async () => {
    const spy = stubEditors();
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);

    await user.click(row(await findGroupName('박편집')).getByRole('button', { name: '내보내기' }));
    const dialog = within(
      await screen.findByRole('dialog', {
        name: '박편집 님을 편집자에서 빼고, 내 방송 접근을 막을까요?',
      }),
    );
    // 무엇을 잃는지 알린다. 대상 표시는 제목의 이름뿐이다(1l ⑦). 업로드 승인 게이트가 없어 「승인 요청 무효」는 말하지 않는다(POK-254)
    expect(dialog.getByText(/하이라이트·클립을 볼 수 없게 됩니다/)).toBeInTheDocument();
    expect(dialog.queryByText(/승인/)).not.toBeInTheDocument();
    expect(dialog.queryByText('5월 12일 합류')).not.toBeInTheDocument();

    await user.click(dialog.getByRole('button', { name: '취소' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(
      spy.mock.calls.filter(([, init]) => (init as RequestInit)?.method === 'DELETE'),
    ).toHaveLength(0);
  });

  it('확인하면 DELETE가 나가고 결과 토스트에는 되돌리기가 없다 (ADR-044)', async () => {
    const spy = stubEditors((url, init) => {
      if (url === '/api/editor-delegations/3' && init?.method === 'DELETE')
        return new Response(null, { status: 204 });
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);

    await user.click(row(await findGroupName('박편집')).getByRole('button', { name: '내보내기' }));
    const dialog = within(
      await screen.findByRole('dialog', {
        name: '박편집 님을 편집자에서 빼고, 내 방송 접근을 막을까요?',
      }),
    );
    await user.click(dialog.getByRole('button', { name: '내보내기' }));

    expect(await screen.findByText('박편집 님을 편집자에서 내보냈어요')).toBeInTheDocument();
    expect(screen.queryByText(/승인 요청/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '되돌리기' })).not.toBeInTheDocument();
    expect(
      spy.mock.calls.some(
        ([url, init]) =>
          url === '/api/editor-delegations/3' && (init as RequestInit)?.method === 'DELETE',
      ),
    ).toBe(true);
    // 내보낸 뒤 한 번 센다 — 상대 이름은 싣지 않는다 (POK-357)
    expect(track.mock.calls).toEqual([['editor_delegation_ended', { initiator: 'owner' }]]);
  });

  it('이미 내보낸 위임(404)은 오류 토스트로 알리고 목록을 갱신한다', async () => {
    stubEditors((url, init) => {
      if (url === '/api/editor-delegations/3' && init?.method === 'DELETE')
        return jsonResponse(404, { reason: 'DELEGATION_NOT_FOUND' });
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);

    await user.click(row(await findGroupName('박편집')).getByRole('button', { name: '내보내기' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: '내보내기' }),
    );

    expect(await screen.findByText('이미 내보낸 편집자예요')).toBeInTheDocument();
  });
});

describe('EditorSettingsScreen — 초대 취소', () => {
  it('취소는 확인 없이 즉시 DELETE하고 결과를 토스트로 알린다', async () => {
    const spy = stubEditors((url, init) => {
      if (url === '/api/editor-invitations/11' && init?.method === 'DELETE')
        return new Response(null, { status: 204 });
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    await user.click(row(/cut\.master@gmail\.com/).getByRole('button', { name: '취소' }));

    // 확인 모달 없이 바로 나간다
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByText('초대를 취소했어요')).toBeInTheDocument();
    expect(
      spy.mock.calls.some(
        ([url, init]) =>
          url === '/api/editor-invitations/11' && (init as RequestInit)?.method === 'DELETE',
      ),
    ).toBe(true);
    // 취소 성공 뒤 한 번 센다 — 상대 이메일은 싣지 않는다 (POK-357)
    expect(track.mock.calls).toEqual([['editor_invitation_canceled', {}]]);
  });
});

describe('EditorSettingsScreen — 초대 취소 경합', () => {
  it('상대가 먼저 응답한 409 INVITATION_NOT_PENDING도 「이미 처리된 초대예요」로 알린다', async () => {
    stubEditors((url, init) => {
      if (url === '/api/editor-invitations/11' && init?.method === 'DELETE')
        return jsonResponse(409, { reason: 'INVITATION_NOT_PENDING' });
      return undefined;
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    await user.click(row(/cut\.master@gmail\.com/).getByRole('button', { name: '취소' }));

    expect(await screen.findByText('이미 처리된 초대예요')).toBeInTheDocument();
    expect(screen.queryByText('초대 취소에 실패했어요')).not.toBeInTheDocument();
  });

  it('동시 취소에서 각 행의 잠금이 따로 유지된다 — 마지막 호출만 따라가지 않는다', async () => {
    const first = SENT[0] as (typeof SENT)[number];
    const second = { ...first, id: 12, inviteeEmail: 'second.pending@example.com' };
    stubFetch((url, init) => {
      if (init?.method === 'DELETE') return new Promise<Response>(() => {}); // 응답 보류
      if (url === DELEGATIONS_URL) return jsonResponse(200, DELEGATIONS);
      if (url === SENT_URL) return jsonResponse(200, [first, second]);
      throw new Error(`unexpected fetch: ${url}`);
    });
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    const firstCancel = row(/cut\.master@gmail\.com/).getByRole('button', { name: '취소' });
    await user.click(firstCancel);
    await user.click(row(/second\.pending@example\.com/).getByRole('button', { name: /취소/ }));

    // 두 DELETE가 모두 미결인 동안 두 행의 버튼이 전부 잠겨 있어야 한다
    expect(firstCancel).toHaveAttribute('aria-busy', 'true');
    expect(
      row(/second\.pending@example\.com/).getByRole('button', { name: /취소/ }),
    ).toHaveAttribute('aria-busy', 'true');
  });
});

describe('EditorSettingsScreen — 권한 2단계 비교 팝오버', () => {
  it('안내 문구의 트리거로 열리고, 2단계 열과 4개 행 안내가 선다', async () => {
    stubEditors();
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    await user.click(screen.getByRole('button', { name: '권한 2단계 비교' }));
    const panel = within(await screen.findByRole('dialog', { name: '권한 2단계 비교' }));

    // 업로드 승인 게이트가 없다(POK-254): 기본 열도 승인이 필요하다고 하지 않는다
    expect(panel.getByRole('columnheader', { name: /기본/ })).toHaveTextContent('새 편집자');
    expect(panel.getByRole('columnheader', { name: /신뢰/ })).toHaveTextContent('직접 업로드');
    for (const label of [
      '검토 · 클립 편집',
      '보관함 저장 · 템플릿 사용',
      '유튜브 업로드',
      '템플릿 · 채널 설정 변경',
    ]) {
      expect(panel.getByRole('rowheader', { name: label })).toBeInTheDocument();
    }
    // 유튜브 업로드 행 — 편집자도 바로 올린다(08-31 결정): 두 열 모두 즉시
    expect(panel.queryByText('승인 후')).not.toBeInTheDocument();
    expect(panel.getAllByText('즉시')).toHaveLength(2);
    expect(panel.getByText(/새 편집자는 기본으로 시작하고/)).toBeInTheDocument();
  });

  it('닫기 버튼과 Esc로 닫힌다', async () => {
    stubEditors();
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    await user.click(screen.getByRole('button', { name: '권한 2단계 비교' }));
    await user.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('dialog', { name: '권한 2단계 비교' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '권한 2단계 비교' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: '권한 2단계 비교' })).not.toBeInTheDocument();
  });

  it('팝오버가 열린 상태에서 axe 위반이 없다', async () => {
    stubEditors();
    const user = userEvent.setup();
    renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    await user.click(screen.getByRole('button', { name: '권한 2단계 비교' }));
    // 팝오버는 포털로 뜨므로 다이얼로그 요소를 직접 검사한다 — 본문 랜드마크는 앱 레이아웃 몫
    expect(
      await axe(await screen.findByRole('dialog', { name: '권한 2단계 비교' })),
    ).toHaveNoViolations();
  });
});

describe('EditorSettingsScreen — 접근성', () => {
  it('목록 상태에서 axe 위반이 없다', async () => {
    stubEditors();
    const { container } = renderWithProviders(<EditorSettingsScreen />);
    await screen.findByRole('group', { name: '박편집' });

    expect(await axe(container)).toHaveNoViolations();
  });

  it('빈 상태에서 axe 위반이 없다', async () => {
    stubEmptyEditors();
    const { container } = renderWithProviders(<EditorSettingsScreen />);
    await screen.findByText('아직 편집자가 없어요');

    expect(await axe(container)).toHaveNoViolations();
  });
});

/** findByRole로 행이 설 때까지 기다린 뒤 이름을 돌려준다 — row() 헬퍼와 짝. */
async function findGroupName(name: string): Promise<string> {
  await screen.findByRole('group', { name });
  return name;
}

describe('EditorSettingsScreen — 편집자 시점은 다루지 않는다', () => {
  it('받은 초대·내가 편집자인 채널은 여기 없다 — 초대 답하기와 나가기는 계정 전환 창에서 한다(POK-416)', async () => {
    const spy = stubFetch((url) => {
      if (url === DELEGATIONS_URL) return jsonResponse(200, DELEGATIONS);
      if (url === SENT_URL) return jsonResponse(200, SENT);
      throw new Error(`unexpected fetch: ${url}`);
    });
    renderWithProviders(<EditorSettingsScreen />);

    expect(await screen.findByRole('group', { name: '박편집' })).toBeInTheDocument();
    expect(screen.queryByText(/님의 초대$/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '수락' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '나가기' })).not.toBeInTheDocument();
    // 편집자 시점 목록은 읽지도 않는다
    const urls = spy.mock.calls.map(([url]) => url);
    expect(urls).not.toContain('/api/editor-invitations/received');
    expect(urls).not.toContain('/api/editor-delegations/as-editor');
  });
});

/** 운영(providers.tsx)과 같은 staleTime 60초 — 주기 읽기가 이 기본값과 상관없이 도는지 본다 */
function prodLikeClient() {
  return new QueryClient({
    defaultOptions: { queries: { staleTime: 60_000, retry: false }, mutations: { retry: false } },
  });
}

/** 주기를 한 번 진행하고 그 응답까지 act 안에서 받는다: 응답이 act 밖에서 화면을 바꾸지 않게 */
async function nextPoll(queryClient: QueryClient) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(EDITORS_POLL_MS);
    await vi.waitUntil(() => queryClient.isFetching() === 0);
  });
}

const listCalls = (spy: ReturnType<typeof stubFetch>, url: string) =>
  spy.mock.calls.filter(([u]) => u === url).length;

describe('EditorSettingsScreen — 편집자가 바꾼 목록을 새로고침 없이 반영한다(POK-481)', () => {
  // react-query는 주기를 걸 때의 전역 setInterval을 쓴다: 렌더 전에 가짜로 바꾼다
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  /** 편집자 쪽 동작(수락·거절·나가기)을 흉내 내려고 응답을 케이스 안에서 바꾼다 */
  interface LiveServer {
    delegations: unknown[];
    sent: unknown[];
    failing?: boolean;
  }

  function stubLive(server: LiveServer) {
    return stubFetch((url) => {
      if (server.failing) return jsonResponse(500, { error: 'boom' });
      if (url === DELEGATIONS_URL) return jsonResponse(200, server.delegations);
      if (url === SENT_URL) return jsonResponse(200, server.sent);
      throw new Error(`unexpected fetch: ${url}`);
    });
  }

  it('화면이 열려 있으면 20초마다 두 목록을 다시 읽는다: 편집자가 수락하면 대기 초대가 편집자 행으로 바뀐다', async () => {
    const server: LiveServer = { delegations: [DELEGATIONS[0]], sent: [SENT[0]] };
    const spy = stubLive(server);
    const { queryClient } = renderWithProviders(<EditorSettingsScreen />, prodLikeClient());
    await screen.findByRole('group', { name: /cut\.master@gmail\.com/ });
    expect(screen.queryByRole('group', { name: '컷마스터' })).not.toBeInTheDocument();

    server.sent = [{ ...SENT[0], status: 'ACCEPTED' }];
    server.delegations = [
      DELEGATIONS[0],
      { id: 5, counterpartId: 81, counterpartName: '컷마스터', grantedAt: '2026-08-26T09:00:00Z' },
    ];
    await nextPoll(queryClient);

    expect(screen.getByRole('group', { name: '컷마스터' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: /cut\.master@gmail\.com/ })).not.toBeInTheDocument();
    expect(listCalls(spy, DELEGATIONS_URL)).toBe(2);
    expect(listCalls(spy, SENT_URL)).toBe(2);
  });

  it('편집자가 거절하거나 스스로 나가면 그 행이 사라진다', async () => {
    const server: LiveServer = { delegations: [DELEGATIONS[0]], sent: [SENT[0]] };
    stubLive(server);
    const { queryClient } = renderWithProviders(<EditorSettingsScreen />, prodLikeClient());
    await screen.findByRole('group', { name: '박편집' });

    server.sent = [{ ...SENT[0], status: 'DECLINED' }];
    server.delegations = [];
    await nextPoll(queryClient);

    expect(screen.queryByRole('group', { name: '박편집' })).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: /cut\.master@gmail\.com/ })).not.toBeInTheDocument();
  });

  it('주기 읽기가 실패해도 받아 둔 목록이 남고 오류 카드로 바뀌지 않는다', async () => {
    const server: LiveServer = { delegations: DELEGATIONS, sent: SENT, failing: false };
    stubLive(server);
    const { queryClient } = renderWithProviders(<EditorSettingsScreen />, prodLikeClient());
    await screen.findByRole('group', { name: '박편집' });

    server.failing = true;
    await nextPoll(queryClient);

    expect(queryClient.getQueryState(['editorDelegations'])?.status).toBe('error');
    expect(screen.getByRole('group', { name: '박편집' })).toBeInTheDocument();
    expect(screen.queryByText('편집자 목록을 불러오지 못했어요')).not.toBeInTheDocument();
  });

  it('처음부터 못 읽은 목록은 주기로 다시 읽지 않아 「다시 시도」가 저절로 돌지 않는다', async () => {
    const spy = stubFetch((url) => {
      if (url === DELEGATIONS_URL) return jsonResponse(500, { error: 'boom' });
      if (url === SENT_URL) return jsonResponse(200, SENT);
      throw new Error(`unexpected fetch: ${url}`);
    });
    const { queryClient } = renderWithProviders(<EditorSettingsScreen />, prodLikeClient());
    const retry = await screen.findByRole('button', { name: '다시 시도' });

    await nextPoll(queryClient);
    await nextPoll(queryClient);

    expect(listCalls(spy, DELEGATIONS_URL)).toBe(1);
    expect(listCalls(spy, SENT_URL)).toBe(3);
    expect(retry).not.toHaveAttribute('aria-busy');
  });

  it('한 목록만 처음부터 못 읽었을 때, 읽어 둔 다른 목록의 주기 읽기에는 「다시 시도」가 돌지 않는다', async () => {
    let releaseSent: () => void = () => {};
    let holdSent: Promise<void> | null = null;
    stubFetch((url) => {
      if (url === DELEGATIONS_URL) return jsonResponse(500, { error: 'boom' });
      if (url === SENT_URL)
        return (holdSent ?? Promise.resolve()).then(() => jsonResponse(200, SENT));
      throw new Error(`unexpected fetch: ${url}`);
    });
    const { queryClient } = renderWithProviders(<EditorSettingsScreen />, prodLikeClient());
    const retry = await screen.findByRole('button', { name: '다시 시도' });

    holdSent = new Promise<void>((r) => {
      releaseSent = r;
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(EDITORS_POLL_MS);
      // 실패하는 목록은 바로 끝나고, 읽어 둔 목록만 응답을 기다리는 순간을 만든다
      await vi.waitUntil(
        () =>
          queryClient.getQueryState(['editorDelegations'])?.fetchStatus === 'idle' &&
          queryClient.getQueryState(['editorInvitations', 'sent'])?.fetchStatus === 'fetching',
      );
    });
    expect(retry).not.toHaveAttribute('aria-busy');

    await act(async () => {
      releaseSent();
      await vi.waitUntil(() => queryClient.isFetching() === 0);
    });
  });
});
