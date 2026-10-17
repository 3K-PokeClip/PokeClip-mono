import { initAnalytics } from '@/lib/analytics';

// hydration 전에 한 번 돈다(Next 16 instrumentation-client). 여기서 던지면 앱이 뜨지 않으니 감싼다.
try {
  // 사용 통계(PostHog) — env에 키가 없으면 아무것도 불러오지 않는다 (POK-356)
  initAnalytics();
} catch {
  /* 분석 없이 그대로 뜬다 */
}
