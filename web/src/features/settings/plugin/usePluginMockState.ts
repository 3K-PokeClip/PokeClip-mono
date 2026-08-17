'use client';

import { useCallback, useState } from 'react';

// 디자인 1m 플러그인 화면의 목업 상태.
// 플러그인 상태·연동 코드 API가 아직 없어 표시값은 전부 목업이다 —
// 연동 티켓에서 이 훅 내부만 useQuery/useMutation으로 갈아끼우면 화면은 그대로 쓴다.

/** 연결 상태 배너 표기 (디자인 1m 값 그대로) */
export interface PluginConnection {
  connected: boolean;
  version: string;
  device: string;
  obsVersion: string;
  lastSignal: string;
  latency: string;
}

const MOCK_CONNECTION: PluginConnection = {
  connected: true,
  version: 'v2.4.1',
  device: 'DESKTOP-RACCOON',
  obsVersion: '30.2',
  lastSignal: '방금 전',
  latency: '0.8초',
};

/** 코드 원문은 발급 직후에만 존재한다 — 화면은 발급 여부와 발행일까지만 안다. */
export interface PairingCodeStatus {
  issued: boolean;
  issuedAt?: string;
}

// 이미 포맷된 문자열로 둔다 — 서버와 브라우저의 타임존이 달라도 하이드레이션이 어긋나지 않는다.
const MOCK_ISSUED_AT = '2026. 8. 2.'; // 디자인 표기값

export interface PluginMockState {
  connection: PluginConnection;
  code: PairingCodeStatus;
  /** 발급·재발급은 같은 동작이다 — 기존 코드를 무효화하고 새로 찍는다. */
  issueCode: () => void;
}

export function usePluginMockState(): PluginMockState {
  const [code, setCode] = useState<PairingCodeStatus>({
    issued: true,
    issuedAt: MOCK_ISSUED_AT,
  });

  const issueCode = useCallback(() => {
    setCode({ issued: true, issuedAt: new Date().toLocaleDateString('ko-KR') });
  }, []);

  return { connection: MOCK_CONNECTION, code, issueCode };
}
