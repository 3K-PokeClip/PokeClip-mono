import type { PrivacyStatus, ThumbnailSource } from '@/api/clipEditor';
import type { Theme } from '@/ui/theme/themeConfig';
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

  // ── 2026-10-24 핵심 행동(서비스 전체 PostHog 계획 5a) ─────────────────────────────

  /** 채널 스위처로 다른 채널로 옮김. picked=골라서, left=편집자로 있던 채널에서 나와서 내 채널로. 처음 자동 선택은 세지 않는다 */
  channel_switched: { to_role: 'owner' | 'editor'; reason: 'picked' | 'left' };
  /**
   * 편집기 「저장」 성공. 영상 주문에 딸린 자동 저장은 세지 않는다. 열거는 화면 타입과 같다(바뀌면 부르는 쪽에서 컴파일이 깨진다).
   * cut_seconds는 자른 길이(정수 초)
   */
  clip_recipe_saved: {
    first_save: boolean;
    source: 'card' | 'recipe';
    layout: 'vert' | 'split' | 'center' | 'crop' | 'horiz';
    caption_position: 'top' | 'edge' | 'bottom';
    subtitle_mode: 'burn-cc' | 'burn' | 'cc';
    cut_seconds: number;
  };
  /** 보관함에서 유튜브 업로드를 새로 주문함(서버가 새 주문을 받았을 때만). intent=영상 만들기 창에서 고른 정보로, title=제목만 */
  video_upload_requested: { via: 'intent' | 'title' };
  /** 실패한 영상을 서버가 다시 주문 받음 */
  video_render_retried: Record<string, never>;
  /** 실패한 업로드를 새로 다시 주문함(서버가 새 주문을 만들었을 때만) */
  video_upload_retried: Record<string, never>;
  /** 「확인 필요」 업로드를 회원이 직접 정리함 — 올라감·안 올라감 */
  video_upload_resolved: { result: 'uploaded' | 'not_uploaded' };
  /** 영상 받기 주소를 받아 브라우저에 넘김 — 저장이 끝났는지는 웹이 모른다 */
  clip_download_requested: Record<string, never>;
  /** 플레이어가 그 방송을 처음 재생함(방송마다 한 번). live=생방송, vod=끝난 방송 다시보기 */
  broadcast_playback_started: { mode: 'live' | 'vod' };
  /** 받은 편집자 초대에 답함 */
  editor_invitation_responded: { outcome: 'accepted' | 'declined' };
  /** 편집자 위임이 끝남. owner=스트리머가 내보냄, editor=편집자가 나감 */
  editor_delegation_ended: { initiator: 'owner' | 'editor' };
  /** 라이브·다시보기 화면에서 수동 마킹으로 카드가 새로 생김(가까운 카드가 이미 있으면 세지 않는다) */
  highlight_marked: { mode: 'live' | 'past' };

  // ── 2026-10-24 확장 묶음(서비스 전체 PostHog 계획 5b) ─────────────────────────────
  // `_started`·`_clicked`는 「성공한 뒤에만」의 예외다 — 누른 순간에 센다(plugin_download_clicked와 같은 이유: 그 뒤는 웹이 모른다)

  /** 「Google로 로그인」을 눌러 동의 화면으로 떠남(비콘). login_completed와 짝 → 로그인 이탈률 */
  login_started: Record<string, never>;
  /** 방송 채널·YouTube 연동을 시작해 동의 화면으로 떠남(비콘). platform_linked와 짝 → 연동 이탈률 */
  platform_link_started: { platform: 'chzzk' | 'youtube' | 'soop' };
  /** 처음 사용 안내를 시작함. welcome=환영 창, chip=다시 보기 칩, help=도움말의 「안내 다시 보기」. onboarding_tour_finished와 짝 */
  onboarding_tour_started: { entry: 'welcome' | 'chip' | 'help' };
  /** 「준비 중」 기능을 누름 — 수요를 본다 */
  pending_feature_clicked: {
    feature: 'ai_subtitle' | 'vod_download' | 'mini_player';
  };
  /** 하이라이트 카드·통계 핀을 눌러 그 자리로 옮김. 카드 번호·시각은 싣지 않는다 */
  highlight_card_seeked: { from: 'card' | 'stats_pin'; mode: 'live' | 'past' };
  /** 홈의 시작 안내 단계를 누름 */
  setup_step_clicked: { step: 'chzzk' | 'plugin' | 'broadcast' | 'publish' };
  /** 도움말 · 문의의 문의 창구를 누름 */
  support_link_clicked: { channel: 'discord' | 'email' };
  /** 보낸 편집자 초대를 취소함 */
  editor_invitation_canceled: Record<string, never>;
  /** 프로필 사진을 바꿈. file=올린 사진, preset=기본 아바타 */
  profile_photo_changed: { source: 'file' | 'preset' };
  /** 표시 이름을 바꿈 — 이름은 싣지 않는다 */
  profile_name_changed: Record<string, never>;
  /** 플러그인 설정의 오디오 트랙 이름을 저장함. 이름이 있는 칸 수(0~6)만 싣는다 */
  audio_track_names_saved: { named_count: number };
  /** 설정 › 계정 「화면 테마」에서 다른 값을 고름(같은 값을 다시 누른 건 세지 않는다) */
  theme_changed: { theme: Theme };
}

/** `track`의 보내는 방식 */
export interface TrackOptions {
  /**
   * 곧 페이지를 떠나는 자리 — 일괄 전송을 기다리지 않고 비콘으로 바로 보낸다(랜딩 `analytics.js`의 시작 버튼과 같은 방식).
   * SDK가 아직 안 붙었으면 버려진다(이동을 막지 않는다)
   */
  beacon?: boolean;
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
  options: TrackOptions = {},
): void {
  withAnalytics((posthog) => {
    if (options.beacon) {
      posthog.capture(name, safeProperties(properties), {
        send_instantly: true,
        transport: 'sendBeacon',
      });
    } else {
      posthog.capture(name, safeProperties(properties));
    }
  });
}
