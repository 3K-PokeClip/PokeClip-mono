import { describe, expect, it } from 'vitest';
import { applyAudioPreset, presetAvailability, trackRole, type PresetTrack } from './audioPresets';

// 실제 모드의 모양: 0번 = 최종 믹스, 1~5번 = 스트리머가 이름 붙인 소스 트랙(이름 없는 칸은 「트랙 n」)
const TRACKS: PresetTrack[] = [
  { id: 'track-0', label: '최종 믹스' },
  { id: 'track-1', label: '마이크' },
  { id: 'track-2', label: '게임' },
  { id: 'track-3', label: '디스코드' },
  { id: 'track-4', label: 'BGM' },
  { id: 'track-5', label: '트랙 6' },
];
const MIX = 'track-0';
const MIX_ONLY = {
  muted: {
    'track-0': false,
    'track-1': true,
    'track-2': true,
    'track-3': true,
    'track-4': true,
    'track-5': true,
  },
  volumes: {
    'track-0': 100,
    'track-1': 100,
    'track-2': 100,
    'track-3': 100,
    'track-4': 100,
    'track-5': 100,
  },
};

describe('trackRole — 스트리머가 적은 트랙 이름으로 역할을 짐작한다', () => {
  it.each([
    ['마이크', 'voice'],
    ['Mic', 'voice'],
    ['내 목소리', 'voice'],
    ['게임 사운드', 'game'],
    ['GAME', 'game'],
    ['BGM', 'bgm'],
    ['bgm 음악', 'bgm'],
    ['Music', 'bgm'],
    ['노래', 'bgm'],
    ['디스코드', 'other'],
    ['트랙 6', 'other'],
  ])('%s → %s', (label, role) => {
    expect(trackRole(label)).toBe(role);
  });
});

describe('applyAudioPreset', () => {
  it('저작권 걱정 없이: 최종 믹스만 켠 상태면 소스로 바꾸고 BGM만 뺀다', () => {
    const next = applyAudioPreset('copyright-safe', TRACKS, MIX, MIX_ONLY);
    expect(next.muted).toEqual({
      'track-0': true,
      'track-1': false,
      'track-2': false,
      'track-3': false,
      'track-4': true,
      'track-5': false,
    });
    // 음량은 손대지 않는다
    expect(next.volumes).toEqual(MIX_ONLY.volumes);
  });

  it('저작권 걱정 없이: 이미 소스를 쓰고 있으면 켜고 끈 것은 그대로 두고 BGM만 뺀다', () => {
    const current = {
      muted: { ...MIX_ONLY.muted, 'track-0': true, 'track-1': false, 'track-4': false },
      volumes: { ...MIX_ONLY.volumes, 'track-1': 140 },
    };
    const next = applyAudioPreset('copyright-safe', TRACKS, MIX, current);
    expect(next.muted['track-1']).toBe(false);
    expect(next.muted['track-2']).toBe(true);
    expect(next.muted['track-4']).toBe(true);
    expect(next.volumes['track-1']).toBe(140);
  });

  it('목소리 크게: 목소리 130%, 게임 60%로 맞추고 나머지 켬·음량은 그대로', () => {
    const next = applyAudioPreset('voice-up', TRACKS, MIX, MIX_ONLY);
    expect(next.muted['track-0']).toBe(true);
    expect(next.muted['track-1']).toBe(false);
    expect(next.volumes['track-1']).toBe(130);
    expect(next.volumes['track-2']).toBe(60);
    expect(next.volumes['track-3']).toBe(100);
    expect(next.muted['track-4']).toBe(false);
  });

  it('게임 소리 크게: 게임 130%, 목소리 80%', () => {
    const next = applyAudioPreset('game-up', TRACKS, MIX, MIX_ONLY);
    expect(next.volumes['track-2']).toBe(130);
    expect(next.volumes['track-1']).toBe(80);
  });

  it('원래 소리: 최종 믹스만 켠다', () => {
    const current = {
      muted: { ...MIX_ONLY.muted, 'track-0': true, 'track-1': false },
      volumes: MIX_ONLY.volumes,
    };
    expect(applyAudioPreset('original', TRACKS, MIX, current).muted).toEqual(MIX_ONLY.muted);
  });
});

describe('presetAvailability — 맞는 트랙이 없으면 그 카드는 꺼지고 이유를 말한다', () => {
  it('트랙 이름에 BGM이 없으면 「저작권 걱정 없이」를 못 고른다', () => {
    const noBgm = TRACKS.filter((t) => t.label !== 'BGM');
    expect(presetAvailability('copyright-safe', noBgm, MIX)).toEqual({
      available: false,
      reason: '트랙 이름에 BGM이 없어요',
    });
    expect(presetAvailability('copyright-safe', TRACKS, MIX)).toEqual({ available: true });
  });

  it('목소리·게임 트랙 이름이 없으면 그 카드를 못 고른다', () => {
    const unnamed: PresetTrack[] = [
      { id: 'track-0', label: '최종 믹스' },
      { id: 'track-1', label: '트랙 2' },
    ];
    expect(presetAvailability('voice-up', unnamed, MIX).available).toBe(false);
    expect(presetAvailability('game-up', unnamed, MIX).available).toBe(false);
  });

  it('최종 믹스 트랙이 없으면(목업) 「원래 소리」를 못 고른다', () => {
    expect(presetAvailability('original', TRACKS, undefined).available).toBe(false);
    expect(presetAvailability('original', TRACKS, MIX).available).toBe(true);
  });

  it('BGM 말고 남는 소스가 없으면 「저작권 걱정 없이」를 못 고른다 — 소리 없는 영상은 렌더가 거절한다', () => {
    const onlyBgm: PresetTrack[] = [
      { id: 'track-0', label: '최종 믹스' },
      { id: 'track-1', label: 'BGM' },
    ];
    expect(presetAvailability('copyright-safe', onlyBgm, MIX).available).toBe(false);
  });
});
