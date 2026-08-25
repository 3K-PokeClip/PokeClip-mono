'use client';

import { ApiError, apiFetch } from './client';

// 편집자 위임·초대 호출 (POK-208) — auth 서버가 /api/editor-delegations/*·
// /api/editor-invitations/*를 소유한다 (EditorDelegationController·EditorInvitationController,
// POK-57). 계약 정본: https://kth4778.github.io/pokeclip-api-docs/#api (auth 명세의
// 「편집자 위임」 태그). 아래 타입·오류 코드는 그 OpenAPI 문서와 필드 단위로 대조했다.
// 백엔드 컨트롤러는 둘이지만 화면 도메인이 하나(설정 · 편집자 관리)라 모듈 하나로 묶는다.
//
// 정원(quota)·sent 페이징·편집자 이메일은 POK-207 몫이라 아직 계약에 없다 — 그쪽이
// 머지되면 이 모듈이 확장 지점이다.

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELED' | 'EXPIRED';

/**
 * GET /api/editor-delegations/as-streamer의 서버 응답 원형. `counterpartId`(상대 회원ID)가
 * 실려 있다 — 이 타입은 파일 밖으로 나가지 않고, 아래 fetch가 경계에서 그 필드를 버린다.
 */
interface DelegationWire {
  id: number;
  counterpartId: number;
  counterpartName: string;
  grantedAt: string;
}

/** GET /api/editor-invitations/sent의 서버 응답 원형 — `inviteeId`는 경계에서 버린다. */
interface SentInvitationWire {
  id: number;
  inviteeId: number;
  inviteeName: string;
  inviteeEmail: string;
  status: InvitationStatus;
  expiresAt: string;
  createdAt: string;
}

/**
 * 화면이 다루는 위임 한 건 — 스트리머 시점이라 상대가 곧 편집자다. 회원ID는 담지 않는다.
 * 이메일은 서버가 의도적으로 안 준다(계약 주석: 「이메일은 안 준다」) — POK-207이 보강 예정.
 */
export interface EditorDelegation {
  id: number;
  name: string;
  grantedAt: string;
}

/** 화면이 다루는 보낸 초대 한 건. EXPIRED는 DB가 아니라 조회 시점 계산이다. */
export interface SentInvitation {
  id: number;
  inviteeName: string;
  inviteeEmail: string;
  status: InvitationStatus;
  expiresAt: string;
  createdAt: string;
}

/** 살아있는 위임만 온다 (revoked는 서버가 거른다). */
export async function fetchEditorDelegations(): Promise<EditorDelegation[]> {
  const res = await apiFetch('/api/editor-delegations/as-streamer');
  const wires = (await res.json()) as DelegationWire[];
  // 화이트리스트로 옮긴다 — 스프레드로 받으면 서버가 필드를 늘릴 때 조용히 딸려 들어온다.
  return wires.map((wire) => ({
    id: wire.id,
    name: wire.counterpartName,
    grantedAt: wire.grantedAt,
  }));
}

/** 전체 이력이 최신순으로 온다 — 대기 중(PENDING)만 고르는 것은 호출부 몫이다. */
export async function fetchSentInvitations(): Promise<SentInvitation[]> {
  const res = await apiFetch('/api/editor-invitations/sent');
  const wires = (await res.json()) as SentInvitationWire[];
  return wires.map((wire) => ({
    id: wire.id,
    inviteeName: wire.inviteeName,
    inviteeEmail: wire.inviteeEmail,
    status: wire.status,
    expiresAt: wire.expiresAt,
    createdAt: wire.createdAt,
  }));
}

export const editorDelegationsQueryOptions = {
  queryKey: ['editorDelegations'] as const,
  queryFn: fetchEditorDelegations,
};

export const sentInvitationsQueryOptions = {
  queryKey: ['editorInvitations', 'sent'] as const,
  queryFn: fetchSentInvitations,
};

/**
 * 이메일로 초대 — 정확히 일치하는 가입 계정이 있어야 한다. 이미 살아있는 초대가 있으면
 * 새 초대 대신 기한이 7일로 다시 밀리는데 응답은 같은 201이라 본문을 읽지 않는다 —
 * 호출부 입장에서 결과는 둘 다 "초대가 살아 있다"로 같다.
 */
export async function inviteEditor(email: string): Promise<void> {
  await apiFetch('/api/editor-invitations', {
    method: 'POST',
    body: JSON.stringify({ email }),
  });
}

/** 위임 해제(회수) — 행을 지우지 않고 서버가 revoked_at·revoked_by로 닫는다. */
export async function revokeDelegation(id: number): Promise<void> {
  await apiFetch(`/api/editor-delegations/${id}`, { method: 'DELETE' });
}

/** 보낸 초대 취소 — 없는 초대에도 404다(존재 여부를 알려주지 않는다). */
export async function cancelInvitation(id: number): Promise<void> {
  await apiFetch(`/api/editor-invitations/${id}`, { method: 'DELETE' });
}

export type EditorInviteFailure =
  'SELF_INVITE' | 'INVITEE_NOT_FOUND' | 'ALREADY_EDITOR' | 'TOO_MANY_PENDING';

// 오류 본문은 {"reason": "<코드>"} 한 필드이고 client.ts의 errorMessage가 그것을
// ApiError.message에 담아 준다. 409 둘(ALREADY_EDITOR·TOO_MANY_PENDING)은 상태 코드로 못
// 가르므로 문자열 판정이 불가피하다 — 다만 status도 짝으로 검사해, 서버가 본문 형식을
// 바꾸거나 다른 409가 섞여 들어와도 엉뚱한 문구 대신 폴백으로 떨어지게 한다 (chzzkLink 선례).
const FAILURE_STATUS: Record<EditorInviteFailure, number> = {
  SELF_INVITE: 400,
  INVITEE_NOT_FOUND: 404,
  ALREADY_EDITOR: 409,
  TOO_MANY_PENDING: 409,
};

export function inviteFailureOf(e: unknown): EditorInviteFailure | null {
  if (!(e instanceof ApiError)) return null;
  const reason = e.message;
  if (!Object.hasOwn(FAILURE_STATUS, reason)) return null;
  const failure = reason as EditorInviteFailure;
  return FAILURE_STATUS[failure] === e.status ? failure : null;
}

export interface EditorInviteMessage {
  title: string;
  description?: string;
}

// TOO_MANY_PENDING이 자리 비우는 방법까지 말하는 이유: 정원 배지·사전 차단 UI가 아직
// 없어서(정원 API는 POK-207) 상한을 처음 만나는 자리가 바로 이 문구다. 티켓 완료 조건
// 「정원 초과 시 초대가 막히고 자리를 비우는 방법을 알린다」를 여기서 갚는다.
const FAILURE_MESSAGE: Record<EditorInviteFailure, EditorInviteMessage> = {
  SELF_INVITE: {
    title: '자기 자신은 초대할 수 없어요',
  },
  INVITEE_NOT_FOUND: {
    title: '그 이메일로 가입한 계정이 없어요',
    description: 'PokeClip에 먼저 가입한 사람만 초대할 수 있어요.',
  },
  ALREADY_EDITOR: {
    title: '이미 편집자예요',
    description: '이 사람은 이미 회원님의 편집자로 등록돼 있어요.',
  },
  TOO_MANY_PENDING: {
    title: '대기 중인 초대가 가득 찼어요',
    description:
      '초대는 동시에 20건까지만 대기할 수 있어요. 대기 중인 초대를 취소하거나 기존 편집자 권한을 회수해 자리를 비운 뒤 다시 시도해 주세요.',
  },
};

const FALLBACK_MESSAGE: EditorInviteMessage = {
  title: '초대를 보내지 못했어요',
  description: '잠시 후 다시 시도해 주세요.',
};

/** 초대 실패를 사용자 문구로. 알 수 없는 실패는 폴백으로 — reason 원문을 노출하지 않는다. */
export function inviteFailureMessage(e: unknown): EditorInviteMessage {
  const failure = inviteFailureOf(e);
  return failure !== null ? FAILURE_MESSAGE[failure] : FALLBACK_MESSAGE;
}
