// 브라우저가 알려 주는 운영체제 (POK-379) — 플러그인 다운로드의 기본 탭을 고르는 데만 쓴다.
// 감지는 힌트일 뿐이다: 「다른 PC에 설치」가 배너의 명분이라 다른 OS도 항상 직접 고를 수 있어야 한다.

export type DetectedOs = 'windows' | 'macos' | 'unknown';

export interface NavigatorLike {
  userAgent?: string;
  platform?: string;
  maxTouchPoints?: number;
  userAgentData?: { platform?: string };
}

/** 판별 순서: UA-CH platform → navigator.platform → UA. 모바일·Linux·모르는 것은 unknown */
export function detectOs(nav: NavigatorLike): DetectedOs {
  const ua = nav.userAgent ?? '';
  // iPhone·iPad·Android는 데스크톱 표기보다 먼저 거른다 — PC용 파일을 받을 기기가 아니다
  if (/iPhone|iPad|iPod|Android/i.test(ua)) return 'unknown';

  const hinted = nav.userAgentData?.platform;
  if (hinted === 'Windows') return 'windows';
  if (hinted === 'macOS') return 'macos';
  if (hinted) return 'unknown';

  const platform = nav.platform ?? '';
  if (/^Win/.test(platform)) return 'windows';
  // iPadOS는 데스크톱 사이트 요청 때 MacIntel로 위장한다 — 터치 지점으로 가른다
  if (/^Mac/.test(platform)) return (nav.maxTouchPoints ?? 0) > 1 ? 'unknown' : 'macos';
  if (platform) return 'unknown';

  if (/Windows NT/.test(ua)) return 'windows';
  if (/Mac OS X/.test(ua)) return 'macos';
  return 'unknown';
}
