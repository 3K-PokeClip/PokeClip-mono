import type { BridgeState } from './types';

// 상태 카드 아래 탭(점검 · 오디오 · 설정) — 다른 탭을 보고 있어도 확인할 것이 있으면 그 탭에 점을 찍는다.

// 송출 점검 중 하나라도 통과하지 못했다(해상도는 권장이라 「경고」지만 같이 알린다). 방송 전(모두 unknown)은 아니다.
export function checksNeedAttention(s: BridgeState): boolean {
  const c = s.checks;
  return [c.gop2s, c.sharedEncoder, c.res1080p, c.audioTracks].some((v) => v === false);
}

// 자동 배정이 트랙을 맡고 있는데 어느 트랙에도 없는 소스가 있다(독 「트랙 없음」 경고와 같은 조건).
export function audioNeedsAttention(s: BridgeState): boolean {
  return s.audio.known && s.audio.applied && s.audio.mixOnly.length > 0;
}
