'use client';

import { ApiError, apiFetch } from './client';

// 유튜브 채널 연동 호출 (POK-221) — auth 서버가 /api/youtube-link/*를 소유한다 (YoutubeLinkController).
// 계약 정본: mono services/README.md auth 절 (Swagger: https://kth4778.github.io/pokeclip-api-docs/#api
// auth 서버 드롭다운). 아래 타입·오류 코드는 그 문서·백엔드 DTO와 필드 단위로 대조했다.
// state는 서버가 HMAC으로 서명해 authorizeUrl 안에 넣는다(TTL 10분) — 치지직과 같은 모양이라
// 프론트는 state를 만들지도 보관하지도 비교하지도 않는다. 돌아온 값을 그대로 되돌려주기만 한다.
// 토큰은 어떤 응답에도 실리지 않는다 (ADR-052).

/**
 * 치지직과 달리 `EXPIRED`가 없다 — 구글 access는 1시간이라 만료가 일상이고 갱신으로
 * 항상 해소된다. 화면에 「만료」가 깜빡이면 사용자가 할 일이 있는 것처럼 보이는데
 * 실제로는 없다 (서버 LinkStatus enum과 동일한 셋).
 */
export type YoutubeLinkStatus = 'ACTIVE' | 'BROKEN' | 'UNLINKED';

/**
 * GET /api/youtube-link의 서버 응답 원형. `channelId`가 실려 있다 — 이 타입은 파일 밖으로
 * 나가지 않고, 아래 fetchYoutubeLink가 경계에서 그 필드를 버린다.
 */
interface YoutubeLinkWire {
  linked: boolean;
  channelId?: string;
  channelName?: string;
  status?: YoutubeLinkStatus;
  linkedAt?: string;
  lastRefreshedAt?: string;
  accessExpiresAt?: string;
}

/**
 * 화면이 다루는 연동 상태. **`channelId`를 담지 않는다** — 쿼리 캐시·React Query devtools·
 * DOM 어디에도 남지 않게 응답을 받은 자리에서 버린다. 응답 본문에 실리는 것 자체는 서버
 * 계약이라 네트워크 탭의 그 한 줄까지는 프론트가 못 없앤다.
 *
 * `linked`는 `ACTIVE`일 때만 true다 — 치지직(`ACTIVE`·`EXPIRED`)과 다르다. `BROKEN`·`UNLINKED`도
 * `channelName`·`status`는 오므로 화면이 "끊겼다"를 그릴 수 있다. 연동한 적 없으면
 * `{linked:false}` 한 필드만 온다.
 */
export interface YoutubeLinkState {
  linked: boolean;
  channelName?: string;
  status?: YoutubeLinkStatus;
  linkedAt?: string;
  lastRefreshedAt?: string;
  accessExpiresAt?: string;
}

/** POST /api/youtube-link 성공 응답(201) — 여기서도 channelId는 버린다. */
export interface YoutubeLinked {
  channelName: string;
  linkedAt: string;
}

export async function fetchYoutubeLink(): Promise<YoutubeLinkState> {
  const res = await apiFetch('/api/youtube-link');
  const wire = (await res.json()) as YoutubeLinkWire;
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

export const youtubeLinkQueryOptions = {
  queryKey: ['youtubeLink'] as const,
  queryFn: fetchYoutubeLink,
};

/** 동의 URL 발급 — client_id·redirect_uri·서명된 state까지 서버가 조립해서 준다. */
export async function startYoutubeLink(): Promise<string> {
  const res = await apiFetch('/api/youtube-link/start', { method: 'POST' });
  const { authorizeUrl } = (await res.json()) as { authorizeUrl: string };
  return authorizeUrl;
}

/**
 * 동의 복귀 교환. 채널은 본문이 아니라 구글 동의 화면에서 확정된다(ADR-052) — 그 토큰의
 * channels.list에는 고른 채널 하나만 보이므로 채널 목록·선택이라는 개념 자체가 없다.
 * 재연동도 같은 이 문이다 — 서버가 옛 행을 닫고 새 행을 만든다.
 */
export async function completeYoutubeLink(params: {
  code: string;
  state: string;
}): Promise<YoutubeLinked> {
  const res = await apiFetch('/api/youtube-link', {
    method: 'POST',
    body: JSON.stringify(params),
  });
  const wire = (await res.json()) as { channelId: string; channelName: string; linkedAt: string };
  return { channelName: wire.channelName, linkedAt: wire.linkedAt };
}

/**
 * 연동 해제 — 연동이 없어도 204(멱등)라 본문을 읽지 않는다. 구글에 revoke를 보내지
 * 않으므로 구글 계정의 PokeClip 권한은 남는다 — 화면이 myaccount.google.com/permissions를
 * 안내한다 (ADR-052: revoke는 계정 단위라 남의 멀쩡한 연동까지 끊는다).
 */
export async function unlinkYoutube(): Promise<void> {
  await apiFetch('/api/youtube-link', { method: 'DELETE' });
}

export type YoutubeLinkFailure =
  | 'INVALID_STATE'
  | 'INVALID_CODE'
  | 'SCOPE_MISSING'
  | 'NO_CHANNEL'
  | 'CHANNEL_ALREADY_LINKED'
  | 'YOUTUBE_UNAVAILABLE';

// 오류 본문은 {"reason": "<코드>"} 한 필드이고 client.ts의 errorMessage가 그것을
// ApiError.message에 담아 준다. 400 넷은 상태 코드로 못 가르므로 문자열 판정이
// 불가피하다 — 다만 status도 짝으로 검사해, 서버가 본문 형식을 바꾸거나 다른 400이
// 섞여 들어와도 엉뚱한 문구 대신 폴백으로 떨어지게 한다.
const FAILURE_STATUS: Record<YoutubeLinkFailure, number> = {
  INVALID_STATE: 400,
  INVALID_CODE: 400,
  SCOPE_MISSING: 400,
  NO_CHANNEL: 400,
  CHANNEL_ALREADY_LINKED: 409,
  YOUTUBE_UNAVAILABLE: 502,
};

export function youtubeLinkFailureOf(e: unknown): YoutubeLinkFailure | null {
  if (!(e instanceof ApiError)) return null;
  const reason = e.message;
  if (!Object.hasOwn(FAILURE_STATUS, reason)) return null;
  const failure = reason as YoutubeLinkFailure;
  return FAILURE_STATUS[failure] === e.status ? failure : null;
}

export interface YoutubeLinkMessage {
  title: string;
  description: string;
}

// INVALID_STATE를 만료 하나로 단정하지 않는다 — 서버의 YoutubeLinkStateCodec.matches는
// TTL 초과뿐 아니라 **다른 사용자의 state·위조**도 같은 코드로 떨어뜨린다(동의 도중
// 다른 탭에서 계정을 바꾸면 바로 이것이다). SCOPE_MISSING·NO_CHANNEL은 400 넷 중
// 사용자의 구체 행동이 명확한 둘이라 "무엇을 어떻게"까지 지시한다(409와 같은 결).
const FAILURE_MESSAGE: Record<YoutubeLinkFailure, YoutubeLinkMessage> = {
  INVALID_STATE: {
    title: '연동 요청을 확인할 수 없어요',
    description:
      '동의 화면에서 시간이 너무 지났거나, 그 사이 로그인 계정이 바뀌었어요. 연동을 처음부터 다시 시작해 주세요.',
  },
  INVALID_CODE: {
    title: '연동을 마치지 못했어요',
    description: '구글 동의 정보가 더 이상 유효하지 않아요. 동의부터 다시 진행해 주세요.',
  },
  SCOPE_MISSING: {
    title: '필요한 권한이 빠졌어요',
    description:
      '동의 화면에서 유튜브 업로드 권한 체크가 해제됐어요. 다시 연동하면서 권한을 모두 허용해 주세요.',
  },
  NO_CHANNEL: {
    title: '연동할 유튜브 채널이 없어요',
    description:
      '이 구글 계정에는 유튜브 채널이 없어요. 유튜브에서 채널을 만들거나 채널이 있는 계정으로 다시 연동해 주세요.',
  },
  CHANNEL_ALREADY_LINKED: {
    title: '이미 다른 계정에 연동된 채널이에요',
    description:
      '이 유튜브 채널은 다른 PokeClip 계정에 연결돼 있어요. 그 계정에서 연동을 해제한 뒤 다시 시도해 주세요.',
  },
  YOUTUBE_UNAVAILABLE: {
    title: '유튜브와 연결하지 못했어요',
    description: '유튜브 응답이 지연되고 있어요. 잠시 후 다시 시도해 주세요.',
  },
};

const FALLBACK_MESSAGE: YoutubeLinkMessage = {
  title: '연동에 실패했어요',
  description: '잠시 후 다시 시도해 주세요.',
};

// reason 없이 온 5xx — 백엔드 판정이 아니라 **백엔드에 닿지 못한** 실패다 (POK-217:
// dev 프록시가 재시작 중인 auth에 연결하지 못하면 본문 없는 500이 온다. apiFetch의
// refresh 불가 판정 503도 같은 부류). code·state는 이미 주소창에서 지워졌으므로
// 사용자가 할 일은 폴백의 "다시 시도"가 아니라 연동을 처음부터 다시 시작하는 것이다.
const TRANSPORT_FAILURE_STATUS = new Set([500, 502, 503, 504]);

const TRANSPORT_FAILURE_MESSAGE: YoutubeLinkMessage = {
  title: '서버와 연결이 원활하지 않아요',
  description: '연동이 완료되지 않았어요. 잠시 후 채널 설정에서 연동을 다시 시작해 주세요.',
};

/** 연동 교환 실패를 사용자 문구로. 알 수 없는 실패는 폴백으로 — reason 원문을 노출하지 않는다. */
export function youtubeLinkFailureMessage(e: unknown): YoutubeLinkMessage {
  const failure = youtubeLinkFailureOf(e);
  if (failure !== null) return FAILURE_MESSAGE[failure];
  // reason 매칭(위)이 먼저다 — 502가 YOUTUBE_UNAVAILABLE을 싣고 오면 그 문구가 이긴다.
  if (e instanceof ApiError && TRANSPORT_FAILURE_STATUS.has(e.status))
    return TRANSPORT_FAILURE_MESSAGE;
  // fetch 자체가 거부된 네트워크 단절(TypeError)도 같은 상황이다 — code는 이미
  // 소실됐고 그 자리의 "다시 시도"는 실행 불가능하다. 단 TypeError로 좁힌다:
  // 다른 예외까지 합류시키면 프로그래밍 버그가 연결 장애 문구로 위장된다.
  if (e instanceof TypeError) return TRANSPORT_FAILURE_MESSAGE;
  return FALLBACK_MESSAGE;
}
