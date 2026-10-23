import type { PrivacyStatus, ThumbnailSource } from '@/api/clipEditor';
import { withAnalytics } from './client';

/**
 * 도움말 · 문의 「자주 묻는 질문」의 질문 식별자 — `faq_feedback_sent`의 `faq` 속성 열거.
 * 정본은 여기다: 공용 analytics가 화면(features)을 가져오지 않도록 화면 쪽 `faqItems.ts`가 이 목록을 따른다
 * (key가 여기 없으면 그쪽에서 타입 오류). 질문을 더하면 여기부터 더한다(PR #318 리뷰).
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

// 행동 이벤트 목록 (POK-357). 이름은 「대상_동사」 snake_case, 속성은 열거형·boolean·number만 —
// 자유 문자열(이메일·채널 이름·채팅·제목·토큰·페어링 코드)이 들어갈 자리를 타입으로 없앤다(처리방침 2절).
// 새 이벤트는 docs/ANALYTICS.md 「이벤트」 표부터 고친다. 성공한 뒤에만 보낸다(실패·취소는 세지 않는다).
export interface AnalyticsEvents {
  /** Google 로그인 콜백 성공. 처음 가입인지는 모른다 — 사람별 첫 이벤트로 센다 */
  login_completed: Record<string, never>;
  platform_linked: { platform: 'chzzk' | 'youtube' };
  platform_unlinked: { platform: 'chzzk' | 'youtube' };
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

/** 행동 이벤트를 보낸다. 분석이 꺼져 있으면(키 없음·SDK 차단) 아무 일도 하지 않는다. */
export function track<K extends keyof AnalyticsEvents>(
  name: K,
  properties: AnalyticsEvents[K],
): void {
  withAnalytics((posthog) => {
    posthog.capture(name, { ...properties });
  });
}
