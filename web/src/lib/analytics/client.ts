import type { PostHog, PostHogConfig } from 'posthog-js';
import { useAuthStore } from '@/stores/auth';
import { scrubEvent } from './scrub';

// 사용 통계(PostHog) 연결 (POK-354·356). 결정은 POK-354 코멘트와 위키 ADR — US 지역, 정당한 이익 + 처리방침 고지
// (동의 창 없음, 추적 금지 존중), 랜딩과 같은 프로젝트(.pokeclip.com 쿠키 공유), 프록시 없음.
//
// 키·주소가 env에 없으면(로컬·CI·dev 기본) SDK를 아예 불러오지 않는다 — next.config.ts가 env 없으면 프록시를 빼는 것과 같은 방식.
// NEXT_PUBLIC_* 는 빌드 때 굳으므로 운영 값을 바꾸면 다시 빌드해야 한다.
//
// SDK는 instrumentation-client에서 동적 import로 불러온다. 그 import는 기다리지 않아(Next 16 문서 「Execution timing」)
// hydration 뒤에 끝날 수 있다. 그 사이 들어온 식별·이벤트는 작은 대기열에 두었다가 붙는 대로 흘려보낸다.

const KEY = process.env.NEXT_PUBLIC_POSTHOG_KEY ?? '';
const HOST = process.env.NEXT_PUBLIC_POSTHOG_HOST ?? '';
/** 로딩이 끝나기 전에 쌓을 수 있는 일의 수 — 첫 화면의 식별과 이벤트 몇 개면 충분하다. */
const MAX_PENDING = 50;

type Task = (posthog: PostHog) => void;

let status: 'idle' | 'off' | 'loading' | 'ready' = 'idle';
let client: PostHog | null = null;
let pending: Task[] = [];
let loading: Promise<void> = Promise.resolve();

export function analyticsConfigured(): boolean {
  return KEY !== '' && HOST !== '';
}

/** 랜딩(landing/analytics.js)과 같은 값을 쓴다 — 둘이 어긋나면 한 사람이 두 사람으로 갈린다. */
export const ANALYTICS_CONFIG: Partial<PostHogConfig> = {
  // 2026-08-29: 서브도메인이 함께 쓰는 쿠키가 출처별 localStorage보다 이긴다(cookieWinsOnConflict) — 랜딩 → 대시보드를 한 사람으로.
  // 그다음 판(2026-08-30)은 녹화에 JSON-LD 수집을 더해서 쓰지 않는다
  defaults: '2026-08-29',
  persistence: 'localStorage+cookie',
  cross_subdomain_cookie: true,
  // 로그인한 사람만 사람 프로필을 만든다 — 익명 방문은 이벤트로만 센다
  person_profiles: 'identified_only',
  capture_pageview: 'history_change',
  // 처리방침 2·9절의 거부 수단: 브라우저 추적 금지(Do Not Track)를 켜면 보내지 않는다
  respect_dnt: true,
  // 자동 수집은 끈다 — 누른 단추의 글자(채팅·채널 이름·이메일)가 실려 나간다. 행동은 track()으로만 보낸다(POK-357)
  autocapture: false,
  rageclick: false,
  capture_dead_clicks: false,
  capture_heatmaps: false,
  capture_exceptions: false,
  capture_performance: false,
  // 세션 녹화는 가리기를 확인한 뒤 켠다(POK-358)
  disable_session_recording: true,
  disable_surveys: true,
  disable_product_tours: true,
  disable_conversations: true,
  disable_web_experiments: true,
  // 피처 플래그 평가를 끈다 — /flags 요청이 나가지 않는다. 그 본문은 before_send를 거치지 않고 첫 방문 주소
  // ($initial_current_url)를 쿼리째 싣는다(실측 2026-10-17: ?email= 이 그대로 나갔다). 플래그·설문·실험은 쓰지 않는다.
  // advanced_disable_flags는 원격 설정(토큰만 싣는 GET)까지 막아서 쓰지 않는다 — 세션 녹화는 원격 설정의
  // 「녹화 켜짐」이 있어야 시작한다(POK-358). identify·reset 뒤에도 /flags가 나가지 않는 것을 실측으로 확인했다
  advanced_disable_feature_flags: true,
  before_send: scrubEvent,
};

function run(task: Task) {
  if (client === null) return;
  try {
    task(client);
  } catch {
    /* 분석 실패가 화면 동작을 막지 않게 — 이벤트 하나를 잃는 것까지만 감수 */
  }
}

/**
 * PostHog에 일을 맡긴다. 붙어 있으면 바로, 불러오는 중이면 대기열에, 꺼져 있으면 버린다.
 * 화면 코드는 이것(또는 track·identify 함수)만 쓰고 posthog-js를 직접 import하지 않는다.
 */
export function withAnalytics(task: Task): void {
  if (status === 'ready') run(task);
  else if (status === 'loading' && pending.length < MAX_PENDING) pending.push(task);
}

/** instrumentation-client가 hydration 전에 한 번 부른다. 키가 없으면 SDK를 불러오지 않는다. */
export function initAnalytics(): void {
  if (status !== 'idle') return;
  if (typeof window === 'undefined' || !analyticsConfigured()) {
    status = 'off';
    return;
  }
  status = 'loading';
  bindIdentityToAuthStore();
  loading = import('posthog-js')
    .then(({ default: posthog }) => {
      posthog.init(KEY, { ...ANALYTICS_CONFIG, api_host: HOST });
      client = posthog;
      status = 'ready';
      const queued = pending;
      pending = [];
      queued.forEach(run);
    })
    .catch(() => {
      // 광고 차단기가 청크를 막는 등 — 분석 없이 그대로 쓴다
      status = 'off';
      pending = [];
    });
}

/** 테스트용 — 동적 import가 끝날 때까지 기다린다. */
export function whenAnalyticsLoaded(): Promise<void> {
  return loading;
}

// ── 식별 ─────────────────────────────────────────────────────────────────────

/**
 * 로그인한 회원을 내부 회원 번호로 식별한다. 이메일·이름·Google 식별자는 넘기지 않는다(처리방침 2절).
 * 같은 브라우저에 다른 회원으로 식별된 흔적이 남아 있으면(로그아웃 없이 세션이 끝난 공용 PC) 먼저 지운다 —
 * SDK는 식별된 사람 위에 다른 번호로 identify하면 둘을 합치지 않고 번호만 바꾸므로, 지우지 않으면 앞사람의 기기 식별자를 잇는다.
 */
export function identifyUser(userId: number): void {
  const id = String(userId);
  withAnalytics((posthog) => {
    if (posthog._isIdentified() && posthog.get_distinct_id() !== id) posthog.reset();
    posthog.identify(id);
  });
}

/** 다음 방문자가 앞사람으로 묶이지 않게 식별·기기 식별자를 지운다. */
export function resetIdentity(): void {
  withAnalytics((posthog) => posthog.reset());
}

let unbindAuth: (() => void) | null = null;

/**
 * 로그아웃·새 로그인을 auth 스토어 한 곳에서 본다. 명시 로그아웃(useLogout)·401 자동 로그아웃(apiFetch)·탈퇴(/goodbye)·
 * 다른 탭 로그아웃이 모두 refreshToken을 null로 만든다.
 * - 세션이 끝나면(값 → null) reset. 첫 hydration의 null → 값은 세션 끝이 아니다.
 * - 새 로그인(회전이 아닌 새 토큰 쌍)인데 이미 누군가로 식별돼 있으면 reset — 로그인 이벤트가 앞사람에게 붙지 않게.
 *   랜딩에서 온 익명 방문자는 지우지 않는다: 지우면 랜딩 → 로그인 흐름이 끊긴다.
 */
export function bindIdentityToAuthStore(): void {
  if (unbindAuth !== null) return;
  unbindAuth = useAuthStore.subscribe((state, prev) => {
    if (state.refreshToken === prev.refreshToken) return;
    if (prev.refreshToken !== null && state.refreshToken === null) {
      resetIdentity();
      return;
    }
    const isNewLogin =
      state.refreshToken !== null && state.accessToken !== null && state.rotatedFrom === null;
    if (isNewLogin) {
      withAnalytics((posthog) => {
        if (posthog._isIdentified()) posthog.reset();
      });
    }
  });
}
