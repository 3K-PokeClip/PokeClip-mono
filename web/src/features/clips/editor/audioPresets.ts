// 오디오 메뉴 「빠른 설정」(POK-389). 우리는 방송 소리를 트랙별로 따로 받는다 — 쇼츠 서비스들이 AI로 짐작해 걷어 내는
// 배경음악을 트랙째 뺄 수 있다. 빠른 설정은 그 트랙별 넣기·음량 값만 한 번에 바꾼다(저장 계약은 그대로).
//
// 트랙의 역할(목소리·게임·BGM)을 적는 칸이 서버에 없어서 스트리머가 적은 트랙 이름으로 짐작한다. 맞는 이름이 없으면
// 그 카드는 꺼지고 이유를 말한다.

export type AudioPresetId = 'copyright-safe' | 'voice-up' | 'game-up' | 'original';
export type TrackRole = 'voice' | 'game' | 'bgm' | 'other';

export interface PresetTrack {
  id: string;
  label: string;
}

export interface TrackMix {
  muted: Readonly<Record<string, boolean>>;
  volumes: Readonly<Record<string, number>>;
}

const ROLE_PATTERNS: readonly [Exclude<TrackRole, 'other'>, RegExp][] = [
  // BGM을 먼저 본다 — 「게임 BGM」은 빼야 할 음악이다
  ['bgm', /bgm|브금|음악|노래|music/i],
  ['voice', /마이크|목소리|보이스|mic|voice/i],
  ['game', /게임|game/i],
];

export function trackRole(label: string): TrackRole {
  return ROLE_PATTERNS.find(([, pattern]) => pattern.test(label))?.[0] ?? 'other';
}

/** 목소리 크게 · 게임 소리 크게의 음량(%) — 계약 범위 0~200 안. 누른 뒤에도 막대로 고칠 수 있다 */
const BOOST = 130;
const VOICE_UP_GAME = 60;
const GAME_UP_VOICE = 80;

export function presetAvailability(
  id: AudioPresetId,
  tracks: readonly PresetTrack[],
  mixTrackId: string | undefined,
): { available: true } | { available: false; reason: string } {
  const sources = tracks.filter((track) => track.id !== mixTrackId);
  const has = (role: TrackRole) => sources.some((track) => trackRole(track.label) === role);
  switch (id) {
    case 'copyright-safe':
      if (!has('bgm')) return { available: false, reason: '트랙 이름에 BGM이 없어요' };
      // BGM을 빼고 남는 소리가 없으면 렌더가 거절한다(소리 없는 영상)
      if (sources.every((track) => trackRole(track.label) === 'bgm'))
        return { available: false, reason: 'BGM 말고 남는 소리가 없어요' };
      return { available: true };
    case 'voice-up':
      return has('voice')
        ? { available: true }
        : { available: false, reason: '트랙 이름에 마이크가 없어요' };
    case 'game-up':
      return has('game')
        ? { available: true }
        : { available: false, reason: '트랙 이름에 게임이 없어요' };
    case 'original':
      return mixTrackId !== undefined && tracks.some((track) => track.id === mixTrackId)
        ? { available: true }
        : { available: false, reason: '최종 믹스 트랙이 없어요' };
  }
}

/**
 * 빠른 설정을 적용한 넣기·음량. 최종 믹스만 켠 상태(저장 기본값)에서 소스 쪽 설정을 고르면 소스를 모두 켜고
 * 믹스를 끈 뒤 바꾼다 — 믹스와 소스는 함께 켤 수 없다(계약6). 이미 소스를 쓰고 있으면 켜고 끈 것은 그대로 둔다.
 */
export function applyAudioPreset(
  id: AudioPresetId,
  tracks: readonly PresetTrack[],
  mixTrackId: string | undefined,
  current: TrackMix,
): { muted: Record<string, boolean>; volumes: Record<string, number> } {
  const muted: Record<string, boolean> = { ...current.muted };
  const volumes: Record<string, number> = { ...current.volumes };
  const sources = tracks.filter((track) => track.id !== mixTrackId);

  if (id === 'original') {
    for (const track of tracks) muted[track.id] = track.id !== mixTrackId;
    return { muted, volumes };
  }

  const usingMix = mixTrackId !== undefined && !(current.muted[mixTrackId] ?? false);
  const noSourceOn = sources.every((track) => current.muted[track.id] ?? false);
  if (usingMix || noSourceOn) {
    for (const track of sources) muted[track.id] = false;
  }
  if (mixTrackId !== undefined) muted[mixTrackId] = true;

  for (const track of sources) {
    const role = trackRole(track.label);
    if (id === 'copyright-safe' && role === 'bgm') muted[track.id] = true;
    if (id === 'voice-up' && role === 'voice') volumes[track.id] = BOOST;
    if (id === 'voice-up' && role === 'game') volumes[track.id] = VOICE_UP_GAME;
    if (id === 'game-up' && role === 'game') volumes[track.id] = BOOST;
    if (id === 'game-up' && role === 'voice') volumes[track.id] = GAME_UP_VOICE;
  }
  return { muted, volumes };
}
