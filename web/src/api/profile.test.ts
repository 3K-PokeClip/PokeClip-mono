import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import {
  displayNameFailureMessage,
  displayNameTooLong,
  normalizeDisplayName,
  photoFailureMessage,
  profileFailureOf,
  updateDisplayName,
  uploadProfilePhoto,
} from '@/api/profile';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

const ME = {
  id: 1,
  email: 'raccoon.games@gmail.com',
  name: '너구리씨',
  profileImageUrl: 'http://localhost:8082/api/profile-photos/1?token=abc',
};

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

describe('updateDisplayName', () => {
  it('PATCH /api/auth/me에 이름만 싣고, 응답을 Me 그대로 돌려준다', async () => {
    const spy = stubFetch(() => jsonResponse(200, ME));

    const me = await updateDisplayName('너구리씨');

    expect(me).toEqual(ME);
    const [url, init] = spy.mock.calls[0] ?? [];
    expect(url).toBe('/api/auth/me');
    expect(init?.method).toBe('PATCH');
    expect(JSON.parse(String(init?.body))).toEqual({ name: '너구리씨' });
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json');
  });
});

describe('uploadProfilePhoto', () => {
  it('PUT /api/auth/me/photo에 multipart 파트 file 하나로 보낸다 — Content-Type은 브라우저 몫', async () => {
    const spy = stubFetch(() => jsonResponse(200, ME));
    const blob = new Blob(['png-bytes'], { type: 'image/png' });
    const signal = new AbortController().signal;

    const me = await uploadProfilePhoto(blob, 'avatar.png', signal);

    expect(me).toEqual(ME);
    const [url, init] = spy.mock.calls[0] ?? [];
    expect(url).toBe('/api/auth/me/photo');
    expect(init?.method).toBe('PUT');
    expect(init?.signal).toBe(signal);
    expect(init?.body).toBeInstanceOf(FormData);
    const part = (init?.body as FormData).get('file');
    expect(part).toBeInstanceOf(File);
    expect((part as File).name).toBe('avatar.png');
    expect((part as File).type).toBe('image/png');
    // 파트는 하나뿐이다 — 서버가 @RequestParam("file") 하나만 읽는다
    expect([...(init?.body as FormData).keys()]).toEqual(['file']);
    // 여기서 Content-Type을 정하면 multipart 경계가 사라진다
    expect(new Headers(init?.headers).get('Content-Type')).toBeNull();
  });
});

describe('profileFailureOf', () => {
  it.each([
    ['NAME_BLANK', 400],
    ['NAME_TOO_LONG', 400],
    ['NAME_INVALID_CHARACTER', 400],
    ['PHOTO_TOO_LARGE', 413],
    ['PHOTO_NOT_AN_IMAGE', 415],
    ['PHOTO_STORAGE_DISABLED', 503],
  ] as const)('%s는 %i와 짝일 때만 사유로 인정한다', (reason, status) => {
    expect(profileFailureOf(new ApiError(status, reason))).toBe(reason);
    expect(profileFailureOf(new ApiError(status === 400 ? 409 : 400, reason))).toBeNull();
  });

  it('ApiError가 아니거나 모르는 사유면 null이다', () => {
    expect(profileFailureOf(new Error('PHOTO_TOO_LARGE'))).toBeNull();
    expect(profileFailureOf(new ApiError(400, 'SOMETHING_ELSE'))).toBeNull();
  });

  it('apiFetch가 인프라 장애로 만든 503은 「창고 꺼짐」이 아니다', () => {
    expect(profileFailureOf(new ApiError(503, '서버와 연결이 원활하지 않아요'))).toBeNull();
  });
});

describe('displayNameFailureMessage', () => {
  it('입력을 고쳐 풀 수 있는 400 세 사유만 문구로 돌려준다', () => {
    expect(displayNameFailureMessage(new ApiError(400, 'NAME_BLANK'))).toBe('이름을 입력해 주세요');
    expect(displayNameFailureMessage(new ApiError(400, 'NAME_TOO_LONG'))).toBe(
      '30자 이내로 입력해 주세요',
    );
    expect(displayNameFailureMessage(new ApiError(400, 'NAME_INVALID_CHARACTER'))).toBe(
      '이름에 쓸 수 없는 문자가 있어요',
    );
  });

  it('입력 탓이 아닌 실패는 null — 호출부가 토스트로 알린다', () => {
    expect(displayNameFailureMessage(new ApiError(503, '서버와 연결이 원활하지 않아요'))).toBeNull();
    expect(displayNameFailureMessage(new ApiError(401, '세션이 만료됐어요'))).toBeNull();
    // 사진 사유가 이름 슬롯에 새지 않는다
    expect(displayNameFailureMessage(new ApiError(413, 'PHOTO_TOO_LARGE'))).toBeNull();
  });
});

describe('photoFailureMessage', () => {
  it('「줄여서 다시」와 「잠시 뒤에 다시」를 가른다', () => {
    expect(photoFailureMessage(new ApiError(413, 'PHOTO_TOO_LARGE'))).toContain('더 작은 사진');
    expect(photoFailureMessage(new ApiError(415, 'PHOTO_NOT_AN_IMAGE'))).toContain(
      '그림 파일이 아니에요',
    );
    expect(photoFailureMessage(new ApiError(503, 'PHOTO_STORAGE_DISABLED'))).toBe(
      '지금은 사진을 올릴 수 없어요 · 잠시 후 다시 시도해 주세요',
    );
  });

  it('인프라 503·모르는 실패는 원인을 단정하지 않는 폴백이다', () => {
    const fallback = '사진을 올리지 못했어요 · 잠시 후 다시 시도해 주세요';
    expect(photoFailureMessage(new ApiError(503, '서버와 연결이 원활하지 않아요'))).toBe(fallback);
    expect(photoFailureMessage(new ApiError(500, '요청이 실패했다 (500)'))).toBe(fallback);
    expect(photoFailureMessage(new TypeError('Failed to fetch'))).toBe(fallback);
  });
});

describe('normalizeDisplayName', () => {
  it('양끝의 공백·전각 공백·NBSP·ZWSP를 자른다 — trim()이 못 자르는 ZWSP까지', () => {
    expect(normalizeDisplayName('　  너구리​ \t')).toBe('너구리');
  });

  it('가운데는 건드리지 않는다 — 띄어쓴 이름과 ZWJ 이모지가 살아남는다', () => {
    expect(normalizeDisplayName(' 김 태현 ')).toBe('김 태현');
    expect(normalizeDisplayName('👨‍👩‍👧')).toBe('👨‍👩‍👧');
  });

  it('보이지 않는 문자뿐이면 빈 문자열이다 — 서버는 이것을 NAME_BLANK로 거절한다', () => {
    expect(normalizeDisplayName('​​')).toBe('');
    expect(normalizeDisplayName(' 　 ')).toBe('');
  });

  it('연달아 불러도 같은 결과다 — g 플래그의 lastIndex가 새지 않는다', () => {
    expect(normalizeDisplayName(' a ')).toBe('a');
    expect(normalizeDisplayName(' a ')).toBe('a');
  });
});

describe('displayNameTooLong', () => {
  it('코드 포인트로 센다 — 이모지 30개는 통과하고 31개는 넘친다', () => {
    expect(displayNameTooLong('😀'.repeat(30))).toBe(false);
    expect(displayNameTooLong('😀'.repeat(31))).toBe(true);
  });

  it('String.length로 세면 갈리는 예 — 이모지 16개는 length 32지만 16자다', () => {
    const name = '😀'.repeat(16);
    expect(name.length).toBe(32);
    expect(displayNameTooLong(name)).toBe(false);
  });

  it('한글·영문도 30자 경계가 같다', () => {
    expect(displayNameTooLong('가'.repeat(30))).toBe(false);
    expect(displayNameTooLong('가'.repeat(31))).toBe(true);
  });
});
