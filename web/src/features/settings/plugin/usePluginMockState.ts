'use client';

// 디자인 1m 플러그인 화면의 남은 목업 — 연결 상태 배너.
// 플러그인 신호(연결·버전·지연) API가 아직 없어 표시값만 목업으로 유지한다.
// 연동 코드는 POK-102에서 useStreamKeyState(실제 API)로 교체됐다.

/** 연결 상태 배너 표기 (디자인 1m 값 그대로) */
export interface PluginConnection {
  connected: boolean;
  version: string;
  device: string;
  obsVersion: string;
  lastSignal: string;
  latency: string;
}

// 신호 API가 없는데 「연결됨」을 보이면 실제 사용자에게 거짓이 된다(POK-251) — 배너는 연결 안 됨 갈래를 그린다.
// 연결됨 갈래의 칸(버전·기기·지연)은 신호 API가 생기면 채운다.
const MOCK_CONNECTION: PluginConnection = {
  connected: false,
  version: '',
  device: '',
  obsVersion: '',
  lastSignal: '',
  latency: '',
};

export interface PluginMockState {
  connection: PluginConnection;
}

export function usePluginMockState(): PluginMockState {
  return { connection: MOCK_CONNECTION };
}
