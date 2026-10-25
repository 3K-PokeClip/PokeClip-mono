import Hls from 'hls.js';
import { describe, expect, it, vi } from 'vitest';
import { HLS_BASE_CONFIG, HLS_LIVE_CONFIG, HLS_REWIND_CONFIG } from './hlsConfig';

// "catch-up 끄기"의 강제 시크 절반은 hls.js 기본값에 의존한다 — 명시하면 생성자 검증이
// liveSyncDurationCount까지 요구해 LL-HLS 저지연 타겟이 깨진다 (hlsConfig.ts 주석).
// 업그레이드로 기본값이 바뀌면 되감기 중 강제 라이브 점프가 조용히 부활하므로 여기서 고정한다.
//
// 기본값 상수(Hls.DefaultConfig)가 아니라 이 설정으로 만든 인스턴스의 실효 config를 본다 —
// 병합·파생 로직이 바뀌어 기본값은 그대로인데 적용 값만 달라지는 경우까지 잡기 위해서다.
// 라이브 · 되감기 재생기의 두 설정은 같은 바탕(HLS_BASE_CONFIG)을 펼친다(설계 5.4절 설정 분리). 되감기 목록도 ENDLIST 없는
// live 목록이라 catch-up 끔은 두 설정 모두의 계약이다 — 한쪽이 바탕을 빠뜨리면 되감기 중 강제 라이브 점프가 살아난다.
describe.each([
  ['HLS_LIVE_CONFIG', HLS_LIVE_CONFIG],
  ['HLS_REWIND_CONFIG', HLS_REWIND_CONFIG],
] as const)('%s의 실효 설정', (_name, config) => {
  function effectiveConfig() {
    const hls = new Hls(config);
    try {
      return hls.config;
    } finally {
      hls.destroy();
    }
  }

  it('강제 시크 catch-up이 비활성이다 — 두 키를 모두 고정한다', () => {
    const effective = effectiveConfig();
    // hls.js는 Duration을 먼저 보고, 없을 때만 Count x targetduration을 쓴다
    // (StreamController.synchronizeToLiveEdge / LatencyController.maxLatency).
    // Duration에 기본값이 생기면 Count가 Infinity여도 강제 점프가 되살아난다.
    expect(effective.liveMaxLatencyDuration).toBeUndefined();
    expect(effective.liveMaxLatencyDurationCount).toBe(Infinity);
  });

  it('배속 추격이 비활성이고 저지연 모드다', () => {
    expect(effectiveConfig().maxLiveSyncPlaybackRate).toBe(1);
    expect(effectiveConfig().lowLatencyMode).toBe(true);
  });

  it('liveDurationInfinity가 false다 — seekable.end가 재생목록 edge라는 전제 (dvrWindow.ts) · ENDLIST 한 줄 갱신도 이 전제다', () => {
    expect(effectiveConfig().liveDurationInfinity).toBe(false);
  });

  it('설정이 강제 시크 쪽 키를 명시하지 않는다 — 명시하면 검증 연쇄가 발동한다', () => {
    expect('liveMaxLatencyDurationCount' in config).toBe(false);
    expect('liveMaxLatencyDuration' in config).toBe(false);
    expect('liveSyncDurationCount' in config).toBe(false);
    expect('liveSyncDuration' in config).toBe(false);
  });

  it('출입증 쿠키 싣기(xhrSetup)를 바탕에서 물려받는다', () => {
    expect(config.xhrSetup).toBe(HLS_BASE_CONFIG.xhrSetup);
  });
});

describe('라이브 · 되감기 설정은 뒤 버퍼만 다르다', () => {
  it('라이브 90초 · 되감기 120초', () => {
    expect(HLS_LIVE_CONFIG.backBufferLength).toBe(90);
    expect(HLS_REWIND_CONFIG.backBufferLength).toBe(120);
  });
});

describe('라이브 조각 요청의 쿠키 싣기 (POK-317)', () => {
  it('출입증이 발급된 뒤에만 withCredentials를 켠다: 로컬 영상 서버는 Allow-Origin * 라 켜면 조각을 못 받는다', async () => {
    vi.resetModules();
    const { HLS_BASE_CONFIG: config } = await import('./hlsConfig');
    const media = await import('@/api/mediaPlayback');
    const xhr = () => {
      const x = { withCredentials: false } as XMLHttpRequest;
      config.xhrSetup(x);
      return x.withCredentials;
    };

    expect(xhr()).toBe(false);
    media.markPlaybackCookiesIssued();
    expect(xhr()).toBe(true);
  });
});
