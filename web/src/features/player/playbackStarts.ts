import { track } from '@/lib/analytics';

// 사용 통계 `broadcast_playback_started`의 「방송마다 한 번」을 플레이어 밖에서 지킨다 (POK-357).
// LiveScreen은 방송 시작 시각이 늦게 오거나 재생 자격이 회복되면 GlassPlayer key를 바꿔 다시 마운트한다 — 훅 안의 ref로 기억하면
// 그때마다 비워져 같은 방송을 또 셌다(PR #382 리뷰). 탭이 살아 있는 동안 기억한다.
// 🔴 키는 방송 번호(회차, POK-233)다. 영상 주소·물리 키(`ingestStreamId`, 지금은 스트림 키)는 한 스트리머의 방송이 모두 같아,
// 그것으로 기억하면 같은 탭에서 두 번째 방송부터 안 셌다(PR #382 2차 리뷰). 키는 기억에만 쓰고 보내지 않는다.
const counted = new Set<string>();

/** 그 방송을 이 탭에서 처음 틀었을 때만 센다. `broadcastId`(방송 번호)는 기억에만 쓰고 보내지 않는다 */
export function trackPlaybackStartOnce(broadcastId: string, mode: 'live' | 'vod'): void {
  const key = `${mode}:${broadcastId}`;
  if (counted.has(key)) return;
  counted.add(key);
  track('broadcast_playback_started', { mode });
}

/** 테스트용 — 모듈 기억을 비운다 */
export function resetPlaybackStartsForTest(): void {
  counted.clear();
}
