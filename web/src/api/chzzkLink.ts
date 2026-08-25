'use client';

import { ApiError, apiFetch } from './client';

// 치지직 채널 연동 호출 (POK-205) — auth 서버가 /api/chzzk-link/*를 소유한다 (ChzzkLinkController).
// 계약 정본: https://kth4778.github.io/pokeclip-api-docs/#api (auth 명세의 「치지직 연동」 태그).
// 아래 타입·오류 코드는 그 OpenAPI 문서와 필드 단위로 대조했다.
// state는 서버가 HMAC으로 서명해 authorizeUrl 안에 넣는다(TTL 10분) — 구글과 달리 프론트는
// state를 만들지도 보관하지도 비교하지도 않는다. 돌아온 값을 그대로 되돌려주기만 한다.
// 토큰은 어떤 응답에도 실리지 않는다 (ADR-038).

export type ChzzkLinkStatus = 'ACTIVE' | 'EXPIRED' | 'BROKEN' | 'UNLINKED';

/**
 * GET /api/chzzk-link의 서버 응답 원형. `channelId`가 실려 있다 — 이 타입은 파일 밖으로
 * 나가지 않고, 아래 fetchChzzkLink가 경계에서 그 필드를 버린다.
 */
interface ChzzkLinkWire {
  linked: boolean;
  channelId?: string;
  channelName?: string;
  status?: ChzzkLinkStatus;
  linkedAt?: string;
  lastRefreshedAt?: string;
  accessExpiresAt?: string;
}

/**
 * 화면이 다루는 연동 상태. **`channelId`를 담지 않는다** — 쿼리 캐시·React Query devtools·
 * DOM 어디에도 남지 않게 응답을 받은 자리에서 버린다. 응답 본문에 실리는 것 자체는 서버
 * 계약이라 네트워크 탭의 그 한 줄까지는 프론트가 못 없앤다.
 *
 * `linked`는 `ACTIVE`·`EXPIRED`일 때만 true다. `BROKEN`·`UNLINKED`도 `channelName`·`status`는
 * 오므로 화면이 "끊겼다"를 그릴 수 있다.
 */
export interface ChzzkLinkState {
  linked: boolean;
  channelName?: string;
  status?: ChzzkLinkStatus;
  linkedAt?: string;
  lastRefreshedAt?: string;
  accessExpiresAt?: string;
}

/** POST /api/chzzk-link 성공 응답 — 여기서도 channelId는 버린다. */
export interface ChzzkLinked {
  channelName: string;
  linkedAt: string;
}

export async function fetchChzzkLink(): Promise<ChzzkLinkState> {
  const res = await apiFetch('/api/chzzk-link');
  const wire = (await res.json()) as ChzzkLinkWire;
  // 화이트리스트로 옮긴다 — 스프레드로 받으면 서버가 필드를 늘릴 때 조용히 딸려 들어온다.
  return {
    linked: wire.linked,
    channelName: wire.channelName,
    status: wire.status,
    linkedAt: wire.linkedAt,
    lastRefreshedAt: wire.lastRefreshedAt,
    accessExpiresAt: wire.accessExpiresAt,
  };
}

export const chzzkLinkQueryOptions = {
  queryKey: ['chzzkLink'] as const,
  queryFn: fetchChzzkLink,
};

/** 동의 URL 발급 — clientId·redirectUri·서명된 state까지 서버가 조립해서 준다. */
export async function startChzzkLink(): Promise<string> {
  const res = await apiFetch('/api/chzzk-link/start', { method: 'POST' });
  const { authorizeUrl } = (await res.json()) as { authorizeUrl: string };
  return authorizeUrl;
}

/** 동의 복귀 교환. 채널은 본문이 아니라 서버가 치지직 users/me로 확정한다. */
export async function completeChzzkLink(params: {
  code: string;
  state: string;
}): Promise<ChzzkLinked> {
  const res = await apiFetch('/api/chzzk-link', {
    method: 'POST',
    body: JSON.stringify(params),
  });
  const wire = (await res.json()) as { channelId: string; channelName: string; linkedAt: string };
  return { channelName: wire.channelName, linkedAt: wire.linkedAt };
}

/** 연동 해제 — 연동이 없어도 204(멱등)라 본문을 읽지 않는다. */
export async function unlinkChzzk(): Promise<void> {
  await apiFetch('/api/chzzk-link', { method: 'DELETE' });
}

export type ChzzkLinkFailure =
  'INVALID_STATE' | 'INVALID_CODE' | 'CHANNEL_ALREADY_LINKED' | 'CHZZK_UNAVAILABLE';

// 오류 본문은 {"reason": "<코드>"} 한 필드이고 client.ts의 errorMessage가 그것을
// ApiError.message에 담아 준다. 400 둘(INVALID_STATE·INVALID_CODE)은 상태 코드로 못
// 가르므로 문자열 판정이 불가피하다 — 다만 status도 짝으로 검사해, 서버가 본문 형식을
// 바꾸거나 다른 400이 섞여 들어와도 엉뚱한 문구 대신 폴백으로 떨어지게 한다.
const FAILURE_STATUS: Record<ChzzkLinkFailure, number> = {
  INVALID_STATE: 400,
  INVALID_CODE: 400,
  CHANNEL_ALREADY_LINKED: 409,
  CHZZK_UNAVAILABLE: 502,
};

export function chzzkLinkFailureOf(e: unknown): ChzzkLinkFailure | null {
  if (!(e instanceof ApiError)) return null;
  const reason = e.message;
  if (!Object.hasOwn(FAILURE_STATUS, reason)) return null;
  const failure = reason as ChzzkLinkFailure;
  return FAILURE_STATUS[failure] === e.status ? failure : null;
}

export interface ChzzkLinkMessage {
  title: string;
  description: string;
}

// INVALID_STATE와 INVALID_CODE는 사용자가 할 일이 둘 다 "동의부터 다시"로 같지만 원인이
// 달라 문구를 가른다. 다만 INVALID_STATE를 만료 하나로 단정하지 않는다 — 서버의
// ChzzkLinkStateCodec.matches(state, userId, now)는 TTL 초과뿐 아니라 **다른 사용자의
// state·위조**도 같은 코드로 떨어뜨린다(동의 도중 다른 탭에서 계정을 바꾸면 바로 이것이다).
// 409만 재시도가 답이 아니라 구체 지시를 넣는다.
const FAILURE_MESSAGE: Record<ChzzkLinkFailure, ChzzkLinkMessage> = {
  INVALID_STATE: {
    title: '연동 요청을 확인할 수 없어요',
    description:
      '동의 화면에서 시간이 너무 지났거나, 그 사이 로그인 계정이 바뀌었어요. 연동을 처음부터 다시 시작해 주세요.',
  },
  INVALID_CODE: {
    title: '연동을 마치지 못했어요',
    description: '치지직 동의 정보가 더 이상 유효하지 않아요. 동의부터 다시 진행해 주세요.',
  },
  CHANNEL_ALREADY_LINKED: {
    title: '이미 다른 계정에 연동된 채널이에요',
    description:
      '이 치지직 채널은 다른 PokeClip 계정에 연결돼 있어요. 그 계정에서 연동을 해제한 뒤 다시 시도해 주세요.',
  },
  CHZZK_UNAVAILABLE: {
    title: '치지직과 연결하지 못했어요',
    description: '치지직 응답이 지연되고 있어요. 잠시 후 다시 시도해 주세요.',
  },
};

const FALLBACK_MESSAGE: ChzzkLinkMessage = {
  title: '연동에 실패했어요',
  description: '잠시 후 다시 시도해 주세요.',
};

// reason 없이 온 5xx — 백엔드 판정이 아니라 **백엔드에 닿지 못한** 실패다 (POK-217:
// dev 프록시가 재시작 중인 auth에 연결하지 못하면 본문 없는 500이 온다. apiFetch의
// refresh 불가 판정 503도 같은 부류). code·state는 이미 주소창에서 지워졌으므로
// 사용자가 할 일은 폴백의 "다시 시도"가 아니라 연동을 처음부터 다시 시작하는 것이다.
const TRANSPORT_FAILURE_STATUS = new Set([500, 502, 503, 504]);

const TRANSPORT_FAILURE_MESSAGE: ChzzkLinkMessage = {
  title: '서버와 연결이 원활하지 않아요',
  description: '연동이 완료되지 않았어요. 잠시 후 채널 설정에서 연동을 다시 시작해 주세요.',
};

/** 연동 교환 실패를 사용자 문구로. 알 수 없는 실패는 폴백으로 — reason 원문을 노출하지 않는다. */
export function chzzkLinkFailureMessage(e: unknown): ChzzkLinkMessage {
  const failure = chzzkLinkFailureOf(e);
  if (failure !== null) return FAILURE_MESSAGE[failure];
  // reason 매칭(위)이 먼저다 — 502가 CHZZK_UNAVAILABLE을 싣고 오면 그 문구가 이긴다.
  if (e instanceof ApiError && TRANSPORT_FAILURE_STATUS.has(e.status))
    return TRANSPORT_FAILURE_MESSAGE;
  return FALLBACK_MESSAGE;
}
