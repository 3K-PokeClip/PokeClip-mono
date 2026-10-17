// 사용 통계(PostHog) — 화면 코드는 여기서만 가져다 쓴다 (POK-356). 규칙은 web/docs/ANALYTICS.md
export {
  analyticsConfigured,
  identifyUser,
  initAnalytics,
  REPLAY_BLOCK_CLASS,
  resetIdentity,
} from './client';
export { track, type AnalyticsEvents } from './events';
