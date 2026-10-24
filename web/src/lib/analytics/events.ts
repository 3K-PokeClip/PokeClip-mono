import type { PrivacyStatus, ThumbnailSource } from '@/api/clipEditor';
import { withAnalytics } from './client';

/**
 * 도움말 · 문의 「자주 묻는 질문」의 질문 식별자 — `faq_feedback_sent`의 `faq` 속성 열거.
 * 정본은 여기다: 공용 analytics가 화면(features)을 가져오지 않도록 화면 쪽 `faqItems.ts`가 이 목록을 따른다
 * (key가 여기 없으면 그쪽 satisfies에서, 화면에서 지운 key가 여기 남으면 HelpSettingsScreen 테스트의 타입 검사에서
 * 오류가 난다). 질문을 더하거나 뺄 때는 여기부터 고친다(PR #318 리뷰).
 */
export type FaqKey =
  | 'requirements'
  | 'getting-started'
  | 'replay-tour'
  | 'plugin-download'
  | 'install-warning'
  | 'pairing-code'
  | 'pc-performance'
  | 'relay-disconnected'
  | 'unlink-channel'
  | 'channel-already-linked'
  | 'no-highlight-cards'
  | 'retention'
  | 'full-vod-download'
  | 'landscape-video'
  | 'privacy-after-upload'
  | 'invitation-not-received'
  | 'editor-permissions'
  | 'remove-editor'
  | 'profile'
  | 'chat-retention';

/**
 * 사람이 고를 수 있는 유튜브 분류 번호(POK-381) — `video_render_requested`의 `category_id` 열거. 정본은 여기다:
 * 화면 쪽 `uploadInfo.ts`의 `CATEGORIES`가 이 목록을 따른다(번호가 여기 없으면 그쪽 타입에서, 빠지면 그쪽 테스트에서 걸린다).
 */
export const YOUTUBE_CATEGORY_IDS = [
  '1',
  '2',
  '10',
  '15',
  '17',
  '19',
  '20',
  '22',
  '23',
  '24',
  '25',
  '26',
  '27',
  '28',
  '29',
] as const;
export type YoutubeCategoryId = (typeof YOUTUBE_CATEGORY_IDS)[number];

/** 서버 응답의 분류 번호(문자열)를 이벤트 열거로 좁힌다. 목록 밖이면 other */
export function toYoutubeCategoryId(value: string): YoutubeCategoryId | 'other' {
  return (YOUTUBE_CATEGORY_IDS as readonly string[]).includes(value)
    ? (value as YoutubeCategoryId)
    : 'other';
}

// 행동 이벤트 목록 (POK-357). 이름은 「대상_동사」 snake_case, 속성은 열거형·boolean·number만 —
// 자유 문자열(이메일·채널 이름·채팅·제목·토큰·페어링 코드)이 들어갈 자리를 타입으로 없앤다(처리방침 2절).
// 새 이벤트는 docs/ANALYTICS.md 「이벤트」 표부터 고친다. 성공한 뒤에만 보낸다(실패·취소는 세지 않는다).
export interface AnalyticsEvents {
  /** Google 로그인 콜백 성공. 처음 가입인지는 모른다 — 사람별 첫 이벤트로 센다 */
  login_completed: Record<string, never>;
  platform_linked: { platform: 'chzzk' | 'youtube' | 'soop' };
  platform_unlinked: { platform: 'chzzk' | 'youtube' | 'soop' };
  /** 플러그인 페어링 코드 발급(처음·재발급 모두) */
  pairing_code_issued: Record<string, never>;
  /**
   * 처음 사용 안내가 끝남. completed=마지막 단계에서 「다음」, skipped=「건너뛰기」,
   * auto=가리킬 화면 요소가 없어 저절로 끝남, later=환영 창의 「나중에 볼게요」(안내를 시작하지 않음)
   */
  onboarding_tour_finished: { outcome: 'completed' | 'skipped' | 'auto' | 'later' };
  /** 편집기 진입. card=하이라이트 카드에서, recipe=보관함의 저장된 편집본에서 */
  clip_editor_opened: { source: 'card' | 'recipe' };
  /** 「영상 만들기」 주문을 서버가 받음. 렌더·업로드 성공이 아니다 */
  video_render_requested: {
    privacy_status: PrivacyStatus;
    thumbnail_source: ThumbnailSource;
    made_for_kids: boolean;
    /** 유튜브 분류 번호(POK-381) */
    category_id: YoutubeCategoryId | 'other';
    /** 예약 공개로 주문했나(POK-381). 예약이면 privacy_status는 private다 */
    scheduled: boolean;
  };
  /** 편집자 초대(기한 연장 재초대 포함) */
  editor_invited: Record<string, never>;
  /**
   * 플러그인 설치 파일 링크를 누름 — 받은 수가 아니다. 다운로드는 브라우저가 맡아 막혔는지 웹은 모르므로,
   * 「성공한 뒤에만」 규칙의 예외로 누른 순간을 센다(POK-379). 판 번호는 자유 문자열이라 싣지 않는다
   */
  plugin_download_clicked: {
    os: 'windows' | 'macos';
    kind: 'installer' | 'pkg';
  };
  /** 도움말 · 문의의 「이 답변이 도움이 되었나요?」에 답함. 질문은 문구가 아니라 고정 key로 싣는다 */
  faq_feedback_sent: { faq: FaqKey; helpful: boolean };
}

/** 열거 값의 모양 — 소문자·숫자·`_`·`-`만, 40자까지. 이메일·채널 이름·제목 같은 자유 문자열은 여기 맞지 않는다 */
const ENUM_VALUE_RE = /^[a-z0-9_-]{1,40}$/;

/**
 * 타입이 막는 자유 문자열을 런타임에도 한 번 더 거른다 — 단언(as)이나 응답 객체 펼치기로 섞여 들어온 값.
 * 열거 모양 문자열·boolean·유한한 number만 남기고 나머지는 속성째 뺀다(이벤트는 보낸다).
 */
function safeProperties(properties: object): Record<string, string | number | boolean> {
  const safe: Record<string, string | number | boolean> = {};
  for (const [key, value] of Object.entries(properties)) {
    if (
      typeof value === 'boolean' ||
      (typeof value === 'number' && Number.isFinite(value)) ||
      (typeof value === 'string' && ENUM_VALUE_RE.test(value))
    ) {
      safe[key] = value;
    }
  }
  return safe;
}

/** 행동 이벤트를 보낸다. 분석이 꺼져 있으면(키 없음·SDK 차단) 아무 일도 하지 않는다. */
export function track<K extends keyof AnalyticsEvents>(
  name: K,
  properties: AnalyticsEvents[K],
): void {
  withAnalytics((posthog) => {
    posthog.capture(name, safeProperties(properties));
  });
}
