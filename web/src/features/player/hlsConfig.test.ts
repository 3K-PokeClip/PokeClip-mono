import Hls from 'hls.js';
import { describe, expect, it } from 'vitest';
import { HLS_DVR_CONFIG } from './hlsConfig';

// "catch-up 끄기"의 강제 시크 절반은 hls.js 기본값에 의존한다 — 명시하면 생성자 검증이
// liveSyncDurationCount까지 요구해 LL-HLS 저지연 타겟이 깨진다 (hlsConfig.ts 주석).
// 업그레이드로 기본값이 바뀌면 되감기 중 강제 라이브 점프가 조용히 부활하므로 여기서 고정한다.
describe('HLS_DVR_CONFIG가 기대는 hls.js 기본값', () => {
  it('강제 시크 catch-up은 기본값이 비활성(Infinity)이다', () => {
    expect(Hls.DefaultConfig.liveMaxLatencyDurationCount).toBe(Infinity);
  });

  it('liveDurationInfinity 기본값은 false — seekable.end가 재생목록 edge가 되는 전제 (dvrWindow.ts)', () => {
    expect(Hls.DefaultConfig.liveDurationInfinity).toBe(false);
  });

  it('설정이 강제 시크 쪽 키를 명시하지 않는다 — 명시하면 검증 연쇄가 발동한다', () => {
    expect('liveMaxLatencyDurationCount' in HLS_DVR_CONFIG).toBe(false);
    expect('liveSyncDurationCount' in HLS_DVR_CONFIG).toBe(false);
    expect('liveSyncDuration' in HLS_DVR_CONFIG).toBe(false);
  });

  it('배속 추격은 명시적으로 꺼져 있다', () => {
    expect(HLS_DVR_CONFIG.maxLiveSyncPlaybackRate).toBe(1);
  });
});
