import type { PostHog, PostHogConfig } from 'posthog-js';
import { useAuthStore } from '@/stores/auth';
import { announceAnalyticsPreference } from './preferenceChannel';
import { scrubAttribute, scrubEvent, scrubUrl } from './scrub';

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
/**
 * 세션 녹화는 `on`일 때만 켠다 (POK-358). 켜도 PostHog 프로젝트의 「Record user sessions」가 꺼져 있으면 시작하지 않는다 —
 * SDK가 원격 설정의 녹화 켜짐을 요구한다. 그래서 운영에서 켜고 끄는 스위치는 둘(이 env와 콘솔)이다
 */
const REPLAY = process.env.NEXT_PUBLIC_POSTHOG_REPLAY === 'on';

/**
 * 이 클래스를 단 요소는 세션 녹화에서 통째로 빠진다(크기만 남는다, SDK 기본 blockClass) (POK-358).
 * 글자는 이미 전부 가리지만, 채팅 줄 수·후원 표시·코드 자릿수 같은 구조까지 남기지 않을 곳에 단다
 */
export const REPLAY_BLOCK_CLASS = 'ph-no-capture';
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
  // 다른 거부 수단 「이용 분석 참여」(설정 › 계정, setAnalyticsOptOut)는 이 브라우저에만 저장한다 — 그 기억을 .pokeclip.com
  // 쿠키에 둔다. 기본(localStorage)은 출처마다 따로라, 대시보드에서 꺼도 같은 브라우저의 랜딩은 계속 센다. 랜딩도 같은 값이다
  opt_out_capturing_persistence_type: 'cookie',
  // 자동 수집은 끈다 — 누른 단추의 글자(채팅·채널 이름·이메일)가 실려 나간다. 행동은 track()으로만 보낸다(POK-357)
  autocapture: false,
  rageclick: false,
  capture_dead_clicks: false,
  capture_heatmaps: false,
  capture_exceptions: false,
  capture_performance: false,
  // 세션 녹화 (POK-358) — 화면을 다시 그릴 수 있을 만큼만 담고 사람·비밀값은 담지 않는다(처리방침 2절
  // 「화면의 글자와 입력 내용을 가린 채」). 랜딩은 녹화하지 않는다. 로그인·OAuth 콜백·탈퇴 화면은 콘솔의 URL 차단 목록에서 뺀다
  disable_session_recording: !REPLAY,
  // 녹화에 브라우저 콘솔 로그를 담지 않는다. 정하지 않으면 원격 설정을 따르는데, 새 프로젝트는 콘솔 로그 녹화가
  // 켜져 있다(2026-10-17 원격 설정 확인) — 로그에 주소·응답이 찍힐 수 있다
  enable_recording_console_log: false,
  session_recording: {
    // 모든 글자와 입력값을 가린다 — 채팅·채널 이름·이메일·업로드 제목·페어링 코드가 다 글자다
    maskAllInputs: true,
    maskTextSelector: '*',
    // 이미지·영상·캔버스는 통째로 뺀다(크기만 남는다) — 사진 주소의 서명 토큰, 방송 화면의 얼굴
    blockSelector: 'img, picture, video, canvas, iframe',
    // 링크·이미지 주소의 ?stream=·?card= 와 /broadcast/vod/<번호> — 예전 방송 번호는 스트림 키와 같다
    maskAttributeFn: scrubAttribute,
    // 네트워크 기록은 주소만 정제해 남기고 헤더·본문은 담지 않는다
    recordHeaders: false,
    recordBody: false,
    streamNetworkBody: false,
    recordCrossOriginIframes: false,
    maskCapturedNetworkRequestFn: (request) => ({ ...request, name: scrubUrl(request.name) }),
  },
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

/** 지금 로그인한 회원 번호 — 꺼 둔 브라우저에서 다시 켜면 그때 식별한다 */
let signedInUserId: string | null = null;

/** SDK가 「끔」으로 읽는 값(@posthog/core `isNoLike`) */
const NO_LIKE: unknown[] = [false, 'false', 0, '0', 'no'];

/**
 * 이 브라우저에 「끔」이 저장돼 있나 — SDK 동의 쿠키(`__ph_opt_in_out_<키>`, 값은 `encodeURIComponent(JSON.stringify(값))`)만
 * 본다. SDK의 동의 상태(`get_explicit_consent_status`)는 추적 금지가 켜져 있으면 저장값과 상관없이 denied라, 그것으로 판정하면
 * 스위치를 만진 적 없는 브라우저에 끔 쿠키를 새로 써서 추적 금지를 꺼도 꺼진 채로 남았다(PR #382 2차 리뷰)
 */
function storedOptOut(): boolean {
  const prefix = `__ph_opt_in_out_${KEY}=`;
  const raw = document.cookie
    .split('; ')
    .find((cookie) => cookie.startsWith(prefix))
    ?.slice(prefix.length);
  if (raw === undefined) return false;
  try {
    return NO_LIKE.includes(JSON.parse(decodeURIComponent(raw)));
  } catch {
    return false;
  }
}

/**
 * 식별·기기 식별자를 지운다. SDK의 reset은 동의 선택까지 지우는데, 「이용 분석 참여」는 이 브라우저의 선택이라 로그아웃·
 * 다른 회원 로그인을 넘어 남아야 한다 — 저장된 끔은 reset 뒤에 다시 건다.
 */
function resetKeepingOptOut(posthog: PostHog): void {
  const optedOut = storedOptOut();
  posthog.reset();
  if (optedOut) posthog.opt_out_capturing();
}

/**
 * 로그인한 회원을 내부 회원 번호로 식별한다. 이메일·이름·Google 식별자는 넘기지 않는다(처리방침 2절).
 * 같은 브라우저에 다른 회원으로 식별된 흔적이 남아 있으면(로그아웃 없이 세션이 끝난 공용 PC) 먼저 지운다 —
 * SDK는 식별된 사람 위에 다른 번호로 identify하면 둘을 합치지 않고 번호만 바꾸므로, 지우지 않으면 앞사람의 기기 식별자를 잇는다.
 * 이 브라우저에서 「이용 분석 참여」를 껐으면 식별하지 않는다 — 켜면 setAnalyticsOptOut이 이 번호로 식별한다.
 */
export function identifyUser(userId: number): void {
  const id = String(userId);
  signedInUserId = id;
  withAnalytics((posthog) => {
    if (posthog._isIdentified() && posthog.get_distinct_id() !== id) resetKeepingOptOut(posthog);
    if (posthog.get_explicit_consent_status() === 'denied') return;
    posthog.identify(id);
  });
}

// ── 「이용 분석 참여」(설정 › 계정, 처리방침 7·9절) ──────────────────────────────────
// 선택은 이 브라우저에만 둔다(SDK의 동의 쿠키 `__ph_opt_in_out_<키>`, .pokeclip.com) — 서버는 모른다. 기기·브라우저마다 따로
// 끄고, 쿠키를 지우면 다시 참여다. SDK는 보낼 때마다 그 쿠키를 다시 읽어서 SDK가 뜨기 전부터 끈 브라우저에서는 아무것도 안 나간다.

/**
 * 이 브라우저의 상태. `unavailable` = SDK를 안 불렀다(키 없음·차단기) — 보내지 않는다.
 * `dnt` = 브라우저의 추적 금지가 켜져 있다 — 스위치와 상관없이 SDK가 막는다
 */
export type AnalyticsChoice = 'participating' | 'opted_out' | 'dnt' | 'unavailable';

/** SDK가 「켜짐」으로 읽는 값(@posthog/core `isYesLike`) */
const YES_LIKE: unknown[] = [true, 'true', 1, '1', 'yes'];

/** SDK의 추적 금지 판정(posthog-js consent.js `_getDnt`)과 같은 자리를 본다 */
function doNotTrackOn(): boolean {
  const nav = navigator as Navigator & { msDoNotTrack?: unknown; globalPrivacyControl?: unknown };
  return [
    nav.doNotTrack,
    nav.msDoNotTrack,
    (window as Window & { doNotTrack?: unknown }).doNotTrack,
    nav.globalPrivacyControl,
  ].some((value) => YES_LIKE.includes(value));
}

/** 설정 화면이 읽는다. SDK를 불러오는 중이면 끝날 때까지 기다린다 */
export async function readAnalyticsChoice(): Promise<AnalyticsChoice> {
  await loading;
  if (status !== 'ready' || client === null) return 'unavailable';
  if (doNotTrackOn()) return 'dnt';
  return client.get_explicit_consent_status() === 'denied' ? 'opted_out' : 'participating';
}

/**
 * 이 탭의 SDK에 선택을 건다. 끄면 이벤트·녹화를 멈춘다. 켜면 opt_in이다 — 선택만 지우는 clear는 SDK의 전송 대기열을 다시
 * 시작하지 않아, 꺼진 채 연 페이지에서는 새로고침 전까지 아무것도 안 나갔다(2026-10-24 실측). `$opt_in` 이벤트는 보내지 않고,
 * 로그인한 회원이면 그 번호로 식별한다
 */
export function applyAnalyticsOptOut(optOut: boolean): void {
  withAnalytics((posthog) => {
    if (optOut) {
      posthog.opt_out_capturing();
      posthog.stopSessionRecording();
      return;
    }
    posthog.opt_in_capturing({ captureEventName: false });
    // 끌 때의 stopSessionRecording은 설정(disable_session_recording)을 true로 박아, opt_in만으로는 녹화가 새로고침 전까지
    // 안 돌아왔다(PR #382 2차 리뷰). 처음 설정으로 되돌린다 — 녹화를 안 켜는 배포에서는 그대로 꺼져 있다
    posthog.set_config({ disable_session_recording: !REPLAY });
    if (signedInUserId !== null) posthog.identify(signedInUserId);
  });
}

/**
 * 설정 화면의 스위치 — 이 탭에 걸고 같은 브라우저의 다른 탭에 알린다. 쿠키는 탭이 함께 읽지만 SDK의 전송 대기열·녹화·식별은
 * 탭마다 메모리에 있어서, 다시 켠 것을 알리지 않으면 다른 탭은 새로고침 전까지 안 보낸다(받는 쪽은 AuthGuard)
 */
export function setAnalyticsOptOut(optOut: boolean): void {
  applyAnalyticsOptOut(optOut);
  announceAnalyticsPreference({ optOut });
}

/**
 * 지금 채널에서의 역할을 이후 모든 이벤트에 싣는다(`active_role`) — 스트리머와 편집자의 쓰임을 나눠 본다.
 * 열거 둘뿐이고 상대 채널 번호는 싣지 않는다. 로그아웃의 reset이 함께 지운다.
 */
export function setActiveRole(role: 'owner' | 'editor'): void {
  withAnalytics((posthog) => posthog.register({ active_role: role }));
}

/** 다음 방문자가 앞사람으로 묶이지 않게 식별·기기 식별자를 지운다. */
export function resetIdentity(): void {
  signedInUserId = null;
  withAnalytics(resetKeepingOptOut);
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
        if (posthog._isIdentified()) resetKeepingOptOut(posthog);
      });
    }
  });
}
