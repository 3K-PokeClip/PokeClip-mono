// 사용 통계(PostHog) — 화면 코드는 여기서만 가져다 쓴다 (POK-356). 규칙은 web/docs/ANALYTICS.md
export {
  analyticsConfigured,
  identifyUser,
  initAnalytics,
  REPLAY_BLOCK_CLASS,
  resetIdentity,
  setActiveRole,
} from './client';
export {
  toYoutubeCategoryId,
  track,
  YOUTUBE_CATEGORY_IDS,
  type AnalyticsEvents,
  type FaqKey,
  type YoutubeCategoryId,
} from './events';
export {
  announceAnalyticsPreference,
  subscribeAnalyticsPreference,
  type AnalyticsPreferenceMessage,
} from './preferenceChannel';
