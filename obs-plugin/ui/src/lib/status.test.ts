import { describe, expect, it } from 'vitest';
import { KEY_SUSPECT_HINT, REASON } from './copy';
import { statusView } from './status';
import type { BridgeState } from './types';

const NOW = 1_791_234_567_000;

function state(patch: Partial<BridgeState>): BridgeState {
  return {
    version: 1,
    paired: true,
    keyHint: 'Q7ZK',
    ingest: 'ingest.pokeclip.com:8890',
    apiBase: 'https://dev.pokeclip.com',
    phase: 'idle',
    errorCode: '',
    errorDetail: '',
    obsStreaming: false,
    syncStart: true,
    canSendNow: false,
    retry: { attempt: 0, nextAt: 0, gaveUp: false, keySuspect: false },
    theme: 'dark',
    stats: { bitrateKbps: 0, totalFrames: 0, droppedFrames: 0, uptimeSec: 0 },
    checks: {
      gop2s: 'unknown',
      res1080p: 'unknown',
      sharedEncoder: 'unknown',
      keyintSec: -1,
      width: 0,
      height: 0,
      fps: 0,
      audioTracks: 'unknown',
      audioTrackCount: 0,
    },
    audio: {
      known: false,
      autoAssign: false,
      applied: false,
      deferred: false,
      prompt: false,
      customRouting: false,
      locked: false,
      tracks: [],
      mixOnly: [],
      monitorOnly: [],
    },
    marks: { hotkey: '', sent: 0, pending: 0, failed: 0, seq: 0, result: '', reason: '', lastAt: 0 },
    ...patch,
  };
}

describe('statusView — 자동 재시도', () => {
  it('붙어 있다 끊기면 재연결 중으로, 시도 번호와 남은 시간을 보여 준다', () => {
    const v = statusView(
      state({
        phase: 'reconnecting',
        obsStreaming: true,
        errorCode: 'disconnected',
        retry: { attempt: 3, nextAt: NOW + 11_400, gaveUp: false, keySuspect: false },
      }),
      NOW,
    );
    expect(v.title).toBe('재연결 중');
    expect(v.retrying).toBe(true);
    expect(v.retryText).toBe('12초 뒤 다시 시도해요');
    expect(v.retryCount).toBe('3번째');
    expect(v.alert).toBe(REASON.disconnected);
    expect(v.canRetryNow).toBe(true);
    expect(v.canSendNow).toBe(false);
  });

  it('처음부터 붙지 못하면 연결 재시도 중이고, 접속 중인 시도는 건너뛸 수 없다', () => {
    const v = statusView(
      state({
        phase: 'starting',
        obsStreaming: true,
        errorCode: 'bad_path',
        retry: { attempt: 1, nextAt: 0, gaveUp: false, keySuspect: false },
      }),
      NOW,
    );
    expect(v.title).toBe('연결 재시도 중');
    expect(v.retryText).toBe('다시 연결하는 중이에요');
    expect(v.retryCount).toBe('1번째');
    expect(v.canRetryNow).toBe(false);
    expect(v.alert).toBe(REASON.bad_path);
  });

  it('거절이 이어지면 원인 자리에 키 확인 안내를 보여 준다', () => {
    const v = statusView(
      state({
        phase: 'starting',
        obsStreaming: true,
        errorCode: 'connect_failed',
        retry: { attempt: 4, nextAt: NOW + 5000, gaveUp: false, keySuspect: true },
      }),
      NOW,
    );
    // 문단을 하나 더 쌓지 않고 원인 자리에 대신 싣는다
    expect(v.alert).toBe(KEY_SUSPECT_HINT);
    expect(v.alert).not.toBe(REASON.connect_failed);
  });

  it('재시도 정보 없이 연결 중이면 그냥 연결 중이다', () => {
    const v = statusView(state({ phase: 'starting', obsStreaming: true }), NOW);
    expect(v.title).toBe('연결 중');
    expect(v.retrying).toBe(false);
    expect(v.retryText).toBe('');
  });
});

describe('statusView — 멈춘 뒤의 다음 행동', () => {
  it('본방이 나가는 중에 멈췄으면 「다시 연결」을 준다', () => {
    const v = statusView(state({ phase: 'error', errorCode: 'connect_failed', obsStreaming: true, canSendNow: true }), NOW);
    expect(v.title).toBe('전송이 멈췄어요');
    expect(v.canSendNow).toBe(true);
    expect(v.desc).toContain('본방은 그대로 두고');
    expect(v.desc).not.toContain('방송을 다시 시작');
  });

  it('재시도를 다 쓰고 멈췄으면 그 사실을 먼저 알린다', () => {
    const v = statusView(
      state({
        phase: 'error',
        errorCode: 'connect_failed',
        obsStreaming: true,
        canSendNow: true,
        retry: { attempt: 0, nextAt: 0, gaveUp: true, keySuspect: false },
      }),
      NOW,
    );
    expect(v.desc.startsWith('65분 동안')).toBe(true);
    expect(v.canSendNow).toBe(true);
    expect(v.retrying).toBe(false);
  });

  it('거절이 이어지다 재시도를 멈췄으면 키 확인 안내를 남긴다', () => {
    const v = statusView(
      state({
        phase: 'error',
        errorCode: 'connect_failed',
        obsStreaming: true,
        canSendNow: true,
        retry: { attempt: 0, nextAt: 0, gaveUp: false, keySuspect: true },
      }),
      NOW,
    );
    expect(v.alert).toBe(KEY_SUSPECT_HINT);
    expect(v.retrying).toBe(false);
    expect(v.canSendNow).toBe(true);
  });

  it('본방을 다시 켜야 풀리는 오류에는 버튼을 주지 않는다', () => {
    const v = statusView(state({ phase: 'error', errorCode: 'encoder_active', obsStreaming: true, canSendNow: false }), NOW);
    expect(v.canSendNow).toBe(false);
    expect(v.desc).toContain('본 방송을 다시 시작');
  });

  it('키가 올바르지 않으면 버튼 대신 새 코드를 안내한다', () => {
    const v = statusView(state({ phase: 'error', errorCode: 'invalid_key', obsStreaming: true, canSendNow: false }), NOW);
    expect(v.canSendNow).toBe(false);
    expect(v.desc).toBe('새 코드를 입력하면 다시 전송할 수 있어요.');
  });

  it('본방이 끝난 뒤의 오류는 다음 방송을 안내한다', () => {
    const v = statusView(state({ phase: 'error', errorCode: 'connect_failed' }), NOW);
    expect(v.canSendNow).toBe(false);
    expect(v.desc).toBe('다음 방송을 시작하면 다시 전송해요.');
  });

  it('방송 중에 페어링하면 대기 단계에서도 「다시 연결」을 준다', () => {
    const v = statusView(state({ phase: 'idle', obsStreaming: true, canSendNow: true }), NOW);
    expect(v.canSendNow).toBe(true);
    expect(v.title).toBe('PokeClip 전송이 꺼져 있어요');
    expect(v.alert).toBe('');
  });

  it('방송 전 대기는 준비됨이다', () => {
    const v = statusView(state({}), NOW);
    expect(v.title).toBe('준비됐어요');
    expect(v.canSendNow).toBe(false);
  });
});
