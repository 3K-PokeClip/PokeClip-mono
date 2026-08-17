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

const MOCK_CONNECTION: PluginConnection = {
  connected: true,
  version: 'v2.4.1',
  device: 'DESKTOP-RACCOON',
  obsVersion: '30.2',
  lastSignal: '방금 전',
  latency: '0.8초',
};

export interface PluginMockState {
  connection: PluginConnection;
}

export function usePluginMockState(): PluginMockState {
  return { connection: MOCK_CONNECTION };
}
