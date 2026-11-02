import { describe, expect, it } from 'vitest';
import { detectOs } from './detectOs';

const WIN_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/141.0 Safari/537.36';
const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15';

describe('detectOs: 다운로드 기본 탭을 고를 힌트', () => {
  it('UA-CH platform을 먼저 본다', () => {
    expect(detectOs({ userAgentData: { platform: 'Windows' }, platform: 'MacIntel' })).toBe(
      'windows',
    );
    expect(detectOs({ userAgentData: { platform: 'macOS' } })).toBe('macos');
    expect(detectOs({ userAgentData: { platform: 'Linux' }, userAgent: WIN_UA })).toBe('unknown');
  });

  it('UA-CH가 없으면 navigator.platform이다(Safari·Firefox)', () => {
    expect(detectOs({ platform: 'Win32' })).toBe('windows');
    expect(detectOs({ platform: 'MacIntel', userAgent: MAC_UA, maxTouchPoints: 0 })).toBe('macos');
    expect(detectOs({ platform: 'Linux x86_64' })).toBe('unknown');
  });

  it('MacIntel로 위장한 iPad는 macOS로 보지 않는다', () => {
    expect(detectOs({ platform: 'MacIntel', userAgent: MAC_UA, maxTouchPoints: 5 })).toBe(
      'unknown',
    );
  });

  it('휴대폰은 데스크톱 표기보다 먼저 거른다', () => {
    expect(detectOs({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)' })).toBe(
      'unknown',
    );
    expect(
      detectOs({
        userAgent: 'Mozilla/5.0 (Linux; Android 15)',
        userAgentData: { platform: 'Android' },
      }),
    ).toBe('unknown');
  });

  it('platform도 없으면 UA 글자로 판단한다', () => {
    expect(detectOs({ userAgent: WIN_UA })).toBe('windows');
    expect(detectOs({ userAgent: MAC_UA })).toBe('macos');
    expect(detectOs({})).toBe('unknown');
  });
});
