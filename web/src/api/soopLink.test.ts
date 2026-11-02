import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { ApiError } from './client';
import {
  completeSoopLink,
  fetchSoopLink,
  soopLinkFailureMessage,
  soopLinkFailureOf,
  startSoopLink,
  unlinkSoop,
} from './soopLink';

const SOOP_USER_ID = 'soop-user-a1';

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('soopLink 호출', () => {
  it('상태 조회는 GET /api/soop-link이고 서버가 아이디를 실어도 반환값에 없다', async () => {
    const spy = stubFetch(() =>
      jsonResponse(200, {
        linked: true,
        soopUserId: SOOP_USER_ID,
        nickname: '숲너구리',
        status: 'ACTIVE',
        linkedAt: '2026-10-23T03:00:00Z',
        lastRefreshedAt: '2026-10-23T04:00:00Z',
        accessExpiresAt: '2026-10-23T11:00:00Z',
      }),
    );

    const state = await fetchSoopLink();

    expect(spy.mock.calls[0]?.[0]).toBe('/api/soop-link');
    expect(spy.mock.calls[0]?.[1]?.method ?? 'GET').toBe('GET');
    expect(state).toEqual({
      linked: true,
      nickname: '숲너구리',
      status: 'ACTIVE',
      linkedAt: '2026-10-23T03:00:00Z',
      lastRefreshedAt: '2026-10-23T04:00:00Z',
      accessExpiresAt: '2026-10-23T11:00:00Z',
    });
    expect(JSON.stringify(state)).not.toContain(SOOP_USER_ID);
  });

  it('연동 시작은 POST /api/soop-link/start이고 동의 주소를 돌려준다', async () => {
    const spy = stubFetch(() =>
      jsonResponse(200, { authorizeUrl: 'https://openapi.sooplive.com/auth/code?state=s' }),
    );

    expect(await startSoopLink()).toBe('https://openapi.sooplive.com/auth/code?state=s');
    expect(spy.mock.calls[0]?.[0]).toBe('/api/soop-link/start');
    expect(spy.mock.calls[0]?.[1]?.method).toBe('POST');
  });

  it('완료는 POST /api/soop-link에 code·state를 싣고, 아이디는 반환값에 없다', async () => {
    const spy = stubFetch(() =>
      jsonResponse(201, {
        soopUserId: SOOP_USER_ID,
        nickname: '숲너구리',
        linkedAt: '2026-10-23T03:00:00Z',
      }),
    );

    const linked = await completeSoopLink({ code: 'code-1', state: 'state-1' });

    expect(spy.mock.calls[0]?.[0]).toBe('/api/soop-link');
    expect(spy.mock.calls[0]?.[1]?.method).toBe('POST');
    expect(JSON.parse(String(spy.mock.calls[0]?.[1]?.body))).toEqual({
      code: 'code-1',
      state: 'state-1',
    });
    expect(linked).toEqual({ nickname: '숲너구리', linkedAt: '2026-10-23T03:00:00Z' });
    expect(JSON.stringify(linked)).not.toContain(SOOP_USER_ID);
  });

  it('해제는 DELETE /api/soop-link이고 204 본문을 읽지 않는다', async () => {
    const spy = stubFetch(() => jsonResponse(204));

    await unlinkSoop();

    expect(spy.mock.calls[0]?.[0]).toBe('/api/soop-link');
    expect(spy.mock.calls[0]?.[1]?.method).toBe('DELETE');
  });
});

describe('soopLinkFailureOf', () => {
  it('reason과 status가 짝일 때만 실패 코드로 읽는다', () => {
    expect(soopLinkFailureOf(new ApiError(400, 'INVALID_STATE'))).toBe('INVALID_STATE');
    expect(soopLinkFailureOf(new ApiError(400, 'INVALID_CODE'))).toBe('INVALID_CODE');
    expect(soopLinkFailureOf(new ApiError(409, 'CHANNEL_ALREADY_LINKED'))).toBe(
      'CHANNEL_ALREADY_LINKED',
    );
    expect(soopLinkFailureOf(new ApiError(502, 'SOOP_UNAVAILABLE'))).toBe('SOOP_UNAVAILABLE');
    expect(soopLinkFailureOf(new ApiError(422, 'IDENTITY_UNAVAILABLE'))).toBe(
      'IDENTITY_UNAVAILABLE',
    );
  });

  it('IDENTITY_UNAVAILABLE은 422하고만 짝이다: 422라도 reason이 다르면 null', () => {
    expect(soopLinkFailureOf(new ApiError(400, 'IDENTITY_UNAVAILABLE'))).toBeNull();
    expect(soopLinkFailureOf(new ApiError(502, 'IDENTITY_UNAVAILABLE'))).toBeNull();
    expect(soopLinkFailureOf(new ApiError(422, 'SOOP_UNAVAILABLE'))).toBeNull();
  });

  it('status가 어긋나거나 모르는 값이면 null이다', () => {
    expect(soopLinkFailureOf(new ApiError(409, 'INVALID_STATE'))).toBeNull();
    expect(soopLinkFailureOf(new ApiError(400, 'SOOP_UNAVAILABLE'))).toBeNull();
    // 치지직 코드가 SOOP 판정으로 새면 안 된다: 같은 화면에 두 배선이 산다
    expect(soopLinkFailureOf(new ApiError(502, 'CHZZK_UNAVAILABLE'))).toBeNull();
    expect(soopLinkFailureOf(new ApiError(400, 'toString'))).toBeNull();
    expect(soopLinkFailureOf(new TypeError('network'))).toBeNull();
  });
});

describe('soopLinkFailureMessage', () => {
  it('400 둘·409·502를 각자 문구로 가른다', () => {
    expect(soopLinkFailureMessage(new ApiError(400, 'INVALID_STATE')).title).toBe(
      '연동 요청을 확인할 수 없어요',
    );
    expect(soopLinkFailureMessage(new ApiError(400, 'INVALID_CODE')).description).toContain(
      'SOOP 동의 정보',
    );
    const conflict = soopLinkFailureMessage(new ApiError(409, 'CHANNEL_ALREADY_LINKED'));
    expect(conflict.title).toBe('이미 다른 계정에 연동된 방송국이에요');
    expect(conflict.description).toContain('다른 PokeClip 계정');
    expect(soopLinkFailureMessage(new ApiError(502, 'SOOP_UNAVAILABLE')).title).toBe(
      'SOOP과 연결하지 못했어요',
    );
  });

  it('아이디를 못 읽으면 방송을 켠 상태에서 다시 연동하라고 안내한다', () => {
    expect(soopLinkFailureMessage(new ApiError(422, 'IDENTITY_UNAVAILABLE'))).toEqual({
      title: 'SOOP 방송국을 확인하지 못했어요',
      description: '방송을 켠 상태에서 다시 연동해 주세요',
    });
  });

  it('422인데 reason이 다르면 폴백이다', () => {
    expect(soopLinkFailureMessage(new ApiError(422, 'SOMETHING')).title).toBe('연동에 실패했어요');
  });

  it('모르는 실패는 폴백 문구로: reason 원문을 노출하지 않는다', () => {
    const message = soopLinkFailureMessage(new ApiError(400, 'SOME_INTERNAL_CODE'));
    expect(message).toEqual({
      title: '연동에 실패했어요',
      description: '잠시 후 다시 시도해 주세요.',
    });
  });

  it('reason 없는 5xx와 네트워크 단절은 도달 실패 문구다', () => {
    expect(soopLinkFailureMessage(new ApiError(503, '요청이 실패했다 (503)')).title).toBe(
      '서버와 연결이 원활하지 않아요',
    );
    expect(soopLinkFailureMessage(new TypeError('Failed to fetch')).title).toBe(
      '서버와 연결이 원활하지 않아요',
    );
  });
});
