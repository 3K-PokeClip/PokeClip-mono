'use client';

import { ApiError, apiFetch } from './client';

// SOOP 채널 연동 호출 (POK-396). chzzkLink.ts의 쌍둥이다: auth 서버가 /api/soop-link/*를 소유한다
// (SoopLinkController). 서버 응답에는 SOOP 아이디 칸이 없지만, 생기더라도 화면 쪽으로 새지 않게
// 받은 자리에서 화이트리스트로 옮긴다. 토큰은 어떤 응답에도 실리지 않는다.
// 치지직과 달리 BROKEN·UNLINKED에는 닉네임도 없다: 닫힐 때 서버가 바로 파기한다.

export type SoopLinkStatus = 'ACTIVE' | 'EXPIRED' | 'BROKEN' | 'UNLINKED';

/** 화면이 다루는 연동 상태. `linked`는 ACTIVE·EXPIRED일 때만 true다. */
export interface SoopLinkState {
  linked: boolean;
  nickname?: string;
  status?: SoopLinkStatus;
  linkedAt?: string;
  lastRefreshedAt?: string;
  accessExpiresAt?: string;
}

/** POST /api/soop-link 성공 응답(201). */
export interface SoopLinked {
  nickname: string;
  linkedAt: string;
}

export async function fetchSoopLink(): Promise<SoopLinkState> {
  const res = await apiFetch('/api/soop-link');
  const wire = (await res.json()) as SoopLinkState;
  return {
    linked: wire.linked,
    nickname: wire.nickname,
    status: wire.status,
    linkedAt: wire.linkedAt,
    lastRefreshedAt: wire.lastRefreshedAt,
    accessExpiresAt: wire.accessExpiresAt,
  };
}

export const soopLinkQueryOptions = {
  queryKey: ['soopLink'] as const,
  queryFn: fetchSoopLink,
};

/** 동의 주소 발급. client_id와 서버가 서명한 state가 들어 있다. */
export async function startSoopLink(): Promise<string> {
  const res = await apiFetch('/api/soop-link/start', { method: 'POST' });
  const { authorizeUrl } = (await res.json()) as { authorizeUrl: string };
  return authorizeUrl;
}

/** 동의 복귀 교환. 방송국은 본문이 아니라 서버가 SOOP 조회로 확정한다. */
export async function completeSoopLink(params: {
  code: string;
  state: string;
}): Promise<SoopLinked> {
  const res = await apiFetch('/api/soop-link', {
    method: 'POST',
    body: JSON.stringify(params),
  });
  const wire = (await res.json()) as SoopLinked;
  return { nickname: wire.nickname, linkedAt: wire.linkedAt };
}

/** 연동 해제. 연동이 없어도 204(멱등)라 본문을 읽지 않는다. */
export async function unlinkSoop(): Promise<void> {
  await apiFetch('/api/soop-link', { method: 'DELETE' });
}

export type SoopLinkFailure =
  | 'INVALID_STATE'
  | 'INVALID_CODE'
  | 'CHANNEL_ALREADY_LINKED'
  | 'IDENTITY_UNAVAILABLE'
  | 'SOOP_UNAVAILABLE';

// 오류 본문은 {"reason": "<이름>"}(SoopLinkExceptionHandler). 판정 규칙은 chzzkLink.ts와 같다:
// status도 짝으로 맞아야 그 실패로 읽는다.
const FAILURE_STATUS: Record<SoopLinkFailure, number> = {
  INVALID_STATE: 400,
  INVALID_CODE: 400,
  CHANNEL_ALREADY_LINKED: 409,
  // 방송 중이 아니고 대표 이미지에서도 방송국 아이디를 못 꺼냈다(서버 SoopLinkFailure). 일시 장애가 아니다
  IDENTITY_UNAVAILABLE: 422,
  SOOP_UNAVAILABLE: 502,
};

export function soopLinkFailureOf(e: unknown): SoopLinkFailure | null {
  if (!(e instanceof ApiError)) return null;
  const reason = e.message;
  if (!Object.hasOwn(FAILURE_STATUS, reason)) return null;
  const failure = reason as SoopLinkFailure;
  return FAILURE_STATUS[failure] === e.status ? failure : null;
}

export interface SoopLinkMessage {
  title: string;
  description: string;
}

// INVALID_STATE는 만료만이 아니라 다른 사용자의 state·위조도 같은 코드다(SoopLinkStateCodec, 치지직과 같다).
const FAILURE_MESSAGE: Record<SoopLinkFailure, SoopLinkMessage> = {
  INVALID_STATE: {
    title: '연동 요청을 확인할 수 없어요',
    description:
      '동의 화면에서 시간이 너무 지났거나, 그 사이 로그인 계정이 바뀌었어요. 연동을 처음부터 다시 시작해 주세요.',
  },
  INVALID_CODE: {
    title: '연동을 마치지 못했어요',
    description: 'SOOP 동의 정보가 더 이상 유효하지 않아요. 동의부터 다시 진행해 주세요.',
  },
  CHANNEL_ALREADY_LINKED: {
    title: '이미 다른 계정에 연동된 방송국이에요',
    description:
      '이 SOOP 방송국은 다른 PokeClip 계정에 연결돼 있어요. 그 계정에서 연동을 해제한 뒤 다시 시도해 주세요.',
  },
  IDENTITY_UNAVAILABLE: {
    title: 'SOOP 방송국을 확인하지 못했어요',
    description: '방송을 켠 상태에서 다시 연동해 주세요',
  },
  SOOP_UNAVAILABLE: {
    title: 'SOOP과 연결하지 못했어요',
    description: 'SOOP 응답이 지연되고 있어요. 잠시 후 다시 시도해 주세요.',
  },
};

const FALLBACK_MESSAGE: SoopLinkMessage = {
  title: '연동에 실패했어요',
  description: '잠시 후 다시 시도해 주세요.',
};

// reason 없는 5xx·네트워크 단절은 백엔드에 닿지 못한 실패다(POK-217, chzzkLink.ts와 같은 판정).
const TRANSPORT_FAILURE_STATUS = new Set([500, 502, 503, 504]);

const TRANSPORT_FAILURE_MESSAGE: SoopLinkMessage = {
  title: '서버와 연결이 원활하지 않아요',
  description: '연동이 완료되지 않았어요. 잠시 후 채널 설정에서 연동을 다시 시작해 주세요.',
};

/** 실패 이름 하나의 문구. 콜백이 서버를 부르기 전에 state가 없을 때도 쓴다. */
export function soopLinkMessageOf(failure: SoopLinkFailure): SoopLinkMessage {
  return FAILURE_MESSAGE[failure];
}

/** 연동 교환 실패를 사용자 문구로. 알 수 없는 실패는 폴백으로, reason 원문을 노출하지 않는다. */
export function soopLinkFailureMessage(e: unknown): SoopLinkMessage {
  const failure = soopLinkFailureOf(e);
  if (failure !== null) return FAILURE_MESSAGE[failure];
  if (e instanceof ApiError && TRANSPORT_FAILURE_STATUS.has(e.status))
    return TRANSPORT_FAILURE_MESSAGE;
  if (e instanceof TypeError) return TRANSPORT_FAILURE_MESSAGE;
  return FALLBACK_MESSAGE;
}
