'use client';

import { ApiError, apiFetch } from './client';
import type { Me } from './auth';

// 회원정보 수정 호출 (POK-208 ← 서버 POK-207) — auth 서버의 PATCH /api/auth/me(표시 이름)·
// PUT /api/auth/me/photo(프로필 사진). 계약 정본: services/README.md 「회원정보 수정 — 표시
// 이름·프로필 사진」과 https://kth4778.github.io/pokeclip-api-docs/ auth 명세. 두 창구 모두
// GET /api/auth/me와 같은 모양(Me)으로 답하므로 호출부는 재조회 없이 캐시를 통째로 덮는다.
//
// 이메일은 여기서 바꾸지 않는다 — 편집자 초대의 열쇠라 서버가 창구를 열지 않았다(ADR-039).

/** 표시 이름 상한 — 서버(UserService)와 같이 **코드 포인트**로 센다. `String.length`가 아니다. */
export const NAME_MAX_CODE_POINTS = 30;

// 서버(UserService.stripEdgeBlanks)의 미러 — 양끝의 공백류·Zs·Cf·Cc를 자른다. trim()으로는
// 부족하다: ZWSP(U+200B)·ZWJ는 공백이 아니라 형식 문자(Cf)라 trim이 못 자른다. 가운데는 절대
// 건드리지 않는다 — 접으면 "김 태현"이 "김태현"이 되고 이모지를 잇는 ZWJ가 사라진다.
// g 플래그라 .replace 전용이다 — .test()에 재사용하면 lastIndex가 남는다.
const EDGE_BLANK = /^[\s\p{Zs}\p{Cf}\p{Cc}]+|[\s\p{Zs}\p{Cf}\p{Cc}]+$/gu;

/** 저장될 모양의 이름 — 서버가 자를 것을 미리 잘라 dirty 판정과 저장값을 일치시킨다. */
export function normalizeDisplayName(raw: string): string {
  return raw.replace(EDGE_BLANK, '');
}

/**
 * 30자(코드 포인트) 초과 — 이모지 하나가 1자다. `<input maxLength>`는 UTF-16 단위로 세서
 * 이모지에서 서버와 갈리므로 쓰지 않는다.
 */
export function displayNameTooLong(name: string): boolean {
  return [...name].length > NAME_MAX_CODE_POINTS;
}

/** 표시 이름만 바꾼다. 누구를 고칠지는 토큰이 정한다 — 회원 번호를 싣지 않는다. */
export async function updateDisplayName(name: string): Promise<Me> {
  const res = await apiFetch('/api/auth/me', {
    method: 'PATCH',
    body: JSON.stringify({ name }),
  });
  return res.json() as Promise<Me>;
}

/**
 * 프로필 사진을 올린다 — multipart 파트 이름은 서버가 `file` 하나로 못박았다. 사람마다 사진이
 * 하나라 PUT이고, 올리면 구글 사진 주소는 지워진다(되돌리는 문이 없다).
 *
 * `signal`은 apiFetch의 401 재시도에도 그대로 실린다. 취소하면 ApiError가 아니라 AbortError가
 * 새어 나오므로 호출부가 가른다. 서버는 창고에 먼저 쓰고 표를 나중에 갱신하므로, 취소는
 * 「기다리지 않겠다」이지 「안 올렸다」가 아니다 — 취소 뒤에는 회원 정보를 다시 읽어야 한다.
 */
export async function uploadProfilePhoto(
  photo: Blob,
  filename: string,
  signal: AbortSignal,
): Promise<Me> {
  const form = new FormData();
  form.append('file', photo, filename);
  const res = await apiFetch('/api/auth/me/photo', { method: 'PUT', body: form, signal });
  return res.json() as Promise<Me>;
}

export type ProfileFailure =
  | 'NAME_BLANK'
  | 'NAME_TOO_LONG'
  | 'NAME_INVALID_CHARACTER'
  | 'PHOTO_TOO_LARGE'
  | 'PHOTO_NOT_AN_IMAGE'
  | 'PHOTO_STORAGE_DISABLED';

// 오류 본문은 {"reason": "<코드>"} 한 필드이고 client.ts의 errorMessage가 그것을 ApiError.message에
// 담아 준다. status도 짝으로 검사한다(editors 선례) — 특히 503은 apiFetch가 refresh 불가(인프라
// 장애)일 때 스스로 만드는 코드와 겹치므로, reason이 PHOTO_STORAGE_DISABLED일 때만 「창고 꺼짐」이다.
// NAME_INVALID_CHARACTER는 services/README 실패 표에 빠져 있지만 서버 enum·핸들러에 있다(400).
const FAILURE_STATUS: Record<ProfileFailure, number> = {
  NAME_BLANK: 400,
  NAME_TOO_LONG: 400,
  NAME_INVALID_CHARACTER: 400,
  PHOTO_TOO_LARGE: 413,
  PHOTO_NOT_AN_IMAGE: 415,
  PHOTO_STORAGE_DISABLED: 503,
};

export function profileFailureOf(e: unknown): ProfileFailure | null {
  if (!(e instanceof ApiError)) return null;
  const reason = e.message;
  if (!Object.hasOwn(FAILURE_STATUS, reason)) return null;
  const failure = reason as ProfileFailure;
  return FAILURE_STATUS[failure] === e.status ? failure : null;
}

// 이름 실패는 사용자가 입력을 고쳐 해결하는 것이라 입력 아래 한 줄로 그린다(폼 오류는 폼이 갖는다,
// ADR-044). NAME_TOO_LONG은 화면이 먼저 거르지만 최종 판정은 서버라 문구를 같이 둔다.
const NAME_MESSAGE: Partial<Record<ProfileFailure, string>> = {
  NAME_BLANK: '이름을 입력해 주세요',
  NAME_TOO_LONG: `${NAME_MAX_CODE_POINTS}자 이내로 입력해 주세요`,
  NAME_INVALID_CHARACTER: '이름에 쓸 수 없는 문자가 있어요',
};

/**
 * 이름 저장 실패 중 **입력을 고쳐 풀 수 있는 것**만 문구로 돌려준다. `null`이면 입력 탓이
 * 아니다(세션·서버·네트워크) — 호출부가 토스트로 결과를 알린다.
 */
export function displayNameFailureMessage(e: unknown): string | null {
  const failure = profileFailureOf(e);
  return failure !== null ? (NAME_MESSAGE[failure] ?? null) : null;
}

// 「줄여서 다시」와 「잠시 뒤에 다시」는 사용자가 할 행동이 다르다 — 서버가 상태 코드를 가른 이유다.
// 폴백 문구는 인프라 장애(apiFetch의 refresh 불가 503)도 받으므로 원인을 단정하지 않는다.
const PHOTO_MESSAGE: Partial<Record<ProfileFailure, string>> = {
  PHOTO_TOO_LARGE: '사진이 너무 커요 · 더 작은 사진으로 다시 시도해 주세요',
  PHOTO_NOT_AN_IMAGE: '그림 파일이 아니에요 · 다른 사진을 골라 주세요',
  PHOTO_STORAGE_DISABLED: '지금은 사진을 올릴 수 없어요 · 잠시 후 다시 시도해 주세요',
};
const PHOTO_FALLBACK = '사진을 올리지 못했어요 · 잠시 후 다시 시도해 주세요';

/** 사진 업로드 실패를 모달 안 한 줄로. 알 수 없는 실패는 폴백 — reason 원문을 노출하지 않는다. */
export function photoFailureMessage(e: unknown): string {
  const failure = profileFailureOf(e);
  return (failure !== null ? PHOTO_MESSAGE[failure] : undefined) ?? PHOTO_FALLBACK;
}
