import Hls from 'hls.js';
import { describe, expect, it } from 'vitest';
import { HLS_DVR_CONFIG } from './hlsConfig';

// "catch-up 끄기"의 강제 시크 절반은 hls.js 기본값에 의존한다 — 명시하면 생성자 검증이
// liveSyncDurationCount까지 요구해 LL-HLS 저지연 타겟이 깨진다 (hlsConfig.ts 주석).
// 업그레이드로 기본값이 바뀌면 되감기 중 강제 라이브 점프가 조용히 부활하므로 여기서 고정한다.
//
// 기본값 상수(Hls.DefaultConfig)가 아니라 이 설정으로 만든 인스턴스의 실효 config를 본다 —
// 병합·파생 로직이 바뀌어 기본값은 그대로인데 적용 값만 달라지는 경우까지 잡기 위해서다.
describe('HLS_DVR_CONFIG의 실효 설정', () => {
  function effectiveConfig() {
    const hls = new Hls(HLS_DVR_CONFIG);
    try {
      return hls.config;
    } finally {
      hls.destroy();
    }
  }

  it('강제 시크 catch-up이 비활성이다 — 두 키를 모두 고정한다', () => {
    const config = effectiveConfig();
    // hls.js는 Duration을 먼저 보고, 없을 때만 Count x targetduration을 쓴다
    // (StreamController.synchronizeToLiveEdge / LatencyController.maxLatency).
    // Duration에 기본값이 생기면 Count가 Infinity여도 강제 점프가 되살아난다.
    expect(config.liveMaxLatencyDuration).toBeUndefined();
    expect(config.liveMaxLatencyDurationCount).toBe(Infinity);
  });

  it('배속 추격이 비활성이다', () => {
    expect(effectiveConfig().maxLiveSyncPlaybackRate).toBe(1);
  });

  it('liveDurationInfinity가 false다 — seekable.end가 재생목록 edge라는 전제 (dvrWindow.ts)', () => {
    expect(effectiveConfig().liveDurationInfinity).toBe(false);
  });

  it('설정이 강제 시크 쪽 키를 명시하지 않는다 — 명시하면 검증 연쇄가 발동한다', () => {
    expect('liveMaxLatencyDurationCount' in HLS_DVR_CONFIG).toBe(false);
    expect('liveMaxLatencyDuration' in HLS_DVR_CONFIG).toBe(false);
    expect('liveSyncDurationCount' in HLS_DVR_CONFIG).toBe(false);
    expect('liveSyncDuration' in HLS_DVR_CONFIG).toBe(false);
  });
});
