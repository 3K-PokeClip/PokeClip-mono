'use client';

import { apiFetch } from './client';

// 스트림키·페어링 코드 호출 (POK-102) — auth 서버가 /api/stream-keys/*를 소유한다.
// 키 원문은 어떤 응답에도 실리지 않는다(ADR-019) — 웹은 유무·시각·페어링 코드만 다룬다.
// 키 회전(rotate)은 프론트 흐름에서 쓰지 않는다 — 재발급도 같은 키의 새 코드 발급이다.

export interface StreamKeyStatus {
  issued: boolean;
  /** 발급 시각(ISO) — 키가 없으면 필드 자체가 없다. */
  createdAt?: string;
}

export interface IssuedPairingCode {
  /** 사람이 읽는 XXXX-XXXX 표기 — 서버가 이미 포맷해서 준다 (PairingCodeService.format). */
  code: string;
  /** 만료 시각(ISO, 발급 후 10분). */
  expiresAt: string;
}

export async function fetchStreamKeyStatus(): Promise<StreamKeyStatus> {
  const res = await apiFetch('/api/stream-keys');
  return res.json() as Promise<StreamKeyStatus>;
}

export const streamKeyStatusQueryOptions = {
  queryKey: ['streamKeys', 'status'] as const,
  queryFn: fetchStreamKeyStatus,
};

/** 일회용 코드 발급 — 키가 없으면 서버 ensureKey가 만들므로 최초 발급의 입구이기도 하다. */
export async function issuePairingCode(): Promise<IssuedPairingCode> {
  const res = await apiFetch('/api/stream-keys/pairing-codes', { method: 'POST' });
  return res.json() as Promise<IssuedPairingCode>;
}
