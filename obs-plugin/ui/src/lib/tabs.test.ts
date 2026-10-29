import { describe, expect, it } from 'vitest';
import { audioNeedsAttention, checksNeedAttention } from './tabs';
import type { AudioRouting, BridgeState } from './types';

const CHECKS_UNKNOWN: BridgeState['checks'] = {
  gop2s: 'unknown',
  res1080p: 'unknown',
  sharedEncoder: 'unknown',
  keyintSec: -1,
  width: 0,
  height: 0,
  fps: 0,
  audioTracks: 'unknown',
  audioTrackCount: 0,
};

const AUDIO: AudioRouting = {
  known: true,
  autoAssign: true,
  applied: true,
  deferred: false,
  prompt: false,
  customRouting: false,
  locked: false,
  tracks: [],
  mixOnly: [],
  monitorOnly: [],
};

function state(checks: Partial<BridgeState['checks']>, audio: Partial<AudioRouting> = {}): BridgeState {
  return { checks: { ...CHECKS_UNKNOWN, ...checks }, audio: { ...AUDIO, ...audio } } as BridgeState;
}

const GAME = { key: 'uuid:game', name: '게임', kind: 'app' as const, showing: true };

describe('checksNeedAttention', () => {
  it('방송 전(모두 unknown)과 모두 통과는 점이 없다', () => {
    expect(checksNeedAttention(state({}))).toBe(false);
    expect(checksNeedAttention(state({ gop2s: true, res1080p: true, sharedEncoder: true, audioTracks: true }))).toBe(false);
  });

  it('하나라도 통과 못 하면 점 — 권장인 해상도도', () => {
    expect(checksNeedAttention(state({ gop2s: true, res1080p: false, sharedEncoder: true, audioTracks: true }))).toBe(true);
    expect(checksNeedAttention(state({ gop2s: false }))).toBe(true);
  });
});

describe('audioNeedsAttention', () => {
  it('자동 배정이 맡고 있는데 트랙에 없는 소스가 있으면 점', () => {
    expect(audioNeedsAttention(state({}, { mixOnly: [GAME] }))).toBe(true);
    expect(audioNeedsAttention(state({}))).toBe(false);
  });

  it('자동 배정이 꺼졌거나 아직 모르면 점이 없다 — 트랙 체크는 스트리머 몫이다', () => {
    expect(audioNeedsAttention(state({}, { applied: false, mixOnly: [GAME] }))).toBe(false);
    expect(audioNeedsAttention(state({}, { known: false, mixOnly: [GAME] }))).toBe(false);
  });
});
