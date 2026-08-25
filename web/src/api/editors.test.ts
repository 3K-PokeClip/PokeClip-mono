import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '@/api/client';
import {
  cancelInvitation,
  fetchEditorDelegations,
  fetchSentInvitations,
  inviteEditor,
  inviteFailureMessage,
  inviteFailureOf,
  revokeDelegation,
} from '@/api/editors';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

beforeEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    hydrated: true,
    rotatedFrom: null,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchEditorDelegations', () => {
  it('회원ID를 경계에서 버리고 화이트리스트만 담는다', async () => {
    stubFetch((url) => {
      expect(url).toBe('/api/editor-delegations/as-streamer');
      return jsonResponse(200, [
        { id: 3, counterpartId: 77, counterpartName: '박편집', grantedAt: '2026-05-12T09:00:00Z' },
      ]);
    });

    const delegations = await fetchEditorDelegations();
    expect(delegations).toEqual([{ id: 3, name: '박편집', grantedAt: '2026-05-12T09:00:00Z' }]);
    // counterpartId가 다른 이름으로도 딸려 오지 않는다 — 스프레드 금지 규약의 검증
    expect(JSON.stringify(delegations)).not.toContain('77');
  });
});

describe('fetchSentInvitations', () => {
  it('inviteeId를 버리고 상태·기한을 그대로 옮긴다 — PENDING 필터는 호출부 몫이다', async () => {
    stubFetch((url) => {
      expect(url).toBe('/api/editor-invitations/sent');
      return jsonResponse(200, [
        {
          id: 11,
          inviteeId: 88,
          inviteeName: '컷마스터',
          inviteeEmail: 'cut.master@gmail.com',
          status: 'PENDING',
          expiresAt: '2026-09-01T09:00:00Z',
          createdAt: '2026-08-25T09:00:00Z',
        },
        {
          id: 10,
          inviteeId: 89,
          inviteeName: '김컷',
          inviteeEmail: 'kimcut@naver.com',
          status: 'DECLINED',
          expiresAt: '2026-08-20T09:00:00Z',
          createdAt: '2026-08-13T09:00:00Z',
        },
      ]);
    });

    const invitations = await fetchSentInvitations();
    expect(invitations).toHaveLength(2);
    expect(invitations[0]).toEqual({
      id: 11,
      inviteeName: '컷마스터',
      inviteeEmail: 'cut.master@gmail.com',
      status: 'PENDING',
      expiresAt: '2026-09-01T09:00:00Z',
      createdAt: '2026-08-25T09:00:00Z',
    });
    expect(invitations[1]?.status).toBe('DECLINED');
    expect(JSON.stringify(invitations)).not.toContain('88');
  });
});

describe('뮤테이션 3종', () => {
  it('inviteEditor는 이메일을 JSON 본문으로 POST한다', async () => {
    const spy = stubFetch(() => jsonResponse(201, {}));
    await inviteEditor('editor@example.com');

    const [url, init] = spy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/editor-invitations');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ email: 'editor@example.com' });
  });

  it('revokeDelegation·cancelInvitation은 id 경로로 DELETE한다', async () => {
    const spy = stubFetch(() => new Response(null, { status: 204 }));
    await revokeDelegation(3);
    await cancelInvitation(11);

    expect(spy.mock.calls.map(([url, init]) => [url, (init as RequestInit).method])).toEqual([
      ['/api/editor-delegations/3', 'DELETE'],
      ['/api/editor-invitations/11', 'DELETE'],
    ]);
  });
});

describe('inviteFailureOf', () => {
  it('reason과 status가 짝일 때만 실패 코드로 읽는다', () => {
    expect(inviteFailureOf(new ApiError(400, 'SELF_INVITE'))).toBe('SELF_INVITE');
    expect(inviteFailureOf(new ApiError(404, 'INVITEE_NOT_FOUND'))).toBe('INVITEE_NOT_FOUND');
    expect(inviteFailureOf(new ApiError(409, 'ALREADY_EDITOR'))).toBe('ALREADY_EDITOR');
    expect(inviteFailureOf(new ApiError(409, 'TOO_MANY_PENDING'))).toBe('TOO_MANY_PENDING');
  });

  it('status가 어긋나면 null이다 — 아는 reason이어도 믿지 않는다', () => {
    expect(inviteFailureOf(new ApiError(409, 'SELF_INVITE'))).toBeNull();
    expect(inviteFailureOf(new ApiError(400, 'TOO_MANY_PENDING'))).toBeNull();
  });

  it('모르는 reason·ApiError가 아닌 것·프로토타입 키는 전부 null이다', () => {
    expect(inviteFailureOf(new ApiError(404, 'SOMETHING_NEW'))).toBeNull();
    expect(inviteFailureOf(new ApiError(400, 'toString'))).toBeNull();
    expect(inviteFailureOf(new TypeError('network'))).toBeNull();
    expect(inviteFailureOf(undefined)).toBeNull();
  });
});

describe('inviteFailureMessage', () => {
  it('미가입 주소는 사유와 함께 거절된다 — 티켓 완료 조건의 문구', () => {
    const message = inviteFailureMessage(new ApiError(404, 'INVITEE_NOT_FOUND'));
    expect(message.title).toBe('그 이메일로 가입한 계정이 없어요');
    expect(message.description).toContain('먼저 가입한 사람만');
  });

  it('정원이 차면 자리를 비우는 방법을 알린다 — 취소와 회수 둘 다', () => {
    const message = inviteFailureMessage(new ApiError(409, 'TOO_MANY_PENDING'));
    expect(message.description).toContain('취소');
    expect(message.description).toContain('회수');
  });

  it('409 둘을 서로 다른 문구로 가른다', () => {
    const already = inviteFailureMessage(new ApiError(409, 'ALREADY_EDITOR'));
    const full = inviteFailureMessage(new ApiError(409, 'TOO_MANY_PENDING'));
    expect(already.title).not.toBe(full.title);
  });

  it('모르는 실패는 폴백 문구로 — reason 원문을 사용자에게 노출하지 않는다', () => {
    const message = inviteFailureMessage(new ApiError(500, 'SOME_INTERNAL_CODE'));
    expect(message).toEqual({
      title: '초대를 보내지 못했어요',
      description: '잠시 후 다시 시도해 주세요.',
    });
    expect(JSON.stringify(message)).not.toContain('SOME_INTERNAL_CODE');
  });
});
