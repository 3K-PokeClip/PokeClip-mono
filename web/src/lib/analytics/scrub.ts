import type { CaptureResult, Properties } from 'posthog-js';

// 분석 도구(PostHog)로 나가는 이벤트에서 주소에 실린 비밀값을 지운다 (POK-356).
// - 쿼리와 해시는 통째로 뗀다. OAuth 콜백의 code·state, 화면의 ?stream=·?card=·?recipe= 가 다 여기 실린다.
//   유입 분석에 쓰는 utm_* 는 SDK가 before_send 전에 따로 속성으로 뽑아 두므로 쿼리를 떼도 남는다.
// - 예전 방송 번호는 스트림 키와 같다(services/README 「clip: 방송 번호와 물리 키」) — /broadcast/vod/<번호>와
//   녹화 네트워크 기록에 남는 API·재생 주소의 방송 번호도 가린다.
// - 우리 주소가 아닌 곳(유입 경로·녹화 속 바깥 링크)은 origin만 남긴다 — youtu.be/<영상 ID>, 방송 채널 주소의 채널 ID가
//   경로에 실린다(처리방침 13절 「영상 정보는 분석 도구에 보내지 않는다」).
// - OAuth 콜백 화면의 페이지 조회는 담을 것이 없으니 아예 보내지 않는다.
// - 홍보 링크 값(utm_*)은 landing/README 「홍보 링크와 UTM」 표에 적은 값만 그대로 두고 나머지는 other로 바꾼다.
//   자유 문자열(utm_term)과 광고 클릭 식별자는 지운다.

const OAUTH_CALLBACK_PATHS = new Set([
  '/auth/callback',
  '/oauth/chzzk/callback',
  '/oauth/soop/callback',
  '/oauth/youtube/callback',
]);
const PAGE_EVENTS = new Set(['$pageview', '$pageleave']);
const VOD_PATH_RE = /^\/broadcast\/vod\/[^/]+/;
const BROADCAST_API_RE = /^\/api\/clip\/broadcasts\/[^/]+/;
const PLAYBACK_PATH_RE = /^\/playback\/[^/]+/;
/**
 * HLS 재생목록·조각 파일을 담은 폴더 — 라이브 LL-HLS는 `<base>/<송출 키>/index.m3u8`·`<송출 키>/<…>_seg.mp4`라 그 폴더가 키다
 * (features/player/mediaSource.ts). base가 설정과 달라도 새지 않게 파일 바로 위 폴더를 가린다
 */
const MEDIA_FILE_DIR_RE = /\/[^/]+(\/[^/]+\.(?:m3u8|m4s|mp4|ts|aac|m4a))$/;
/** 라이브 재생 주소의 앞부분 — 그 뒤 첫 경로 조각이 송출 키다(PR #382 리뷰) */
const LIVE_BASE = (process.env.NEXT_PUBLIC_MEDIA_LIVE_BASE_URL ?? '').replace(/\/+$/, '');

function scrubPath(path: string): string {
  return path
    .replace(/[?#].*$/, '')
    .replace(VOD_PATH_RE, '/broadcast/vod/_')
    .replace(BROADCAST_API_RE, '/api/clip/broadcasts/_')
    .replace(PLAYBACK_PATH_RE, '/playback/_')
    .replace(MEDIA_FILE_DIR_RE, '/_$1');
}

/** 라이브 재생 base 아래 주소면 송출 키 자리(첫 경로 조각)를 가린다. 아니면 null */
function scrubLiveMediaUrl(value: string): string | null {
  if (LIVE_BASE === '' || !value.startsWith(`${LIVE_BASE}/`)) return null;
  const rest = value.slice(LIVE_BASE.length + 1).replace(/[?#].*$/, '');
  const slash = rest.indexOf('/');
  return `${LIVE_BASE}/_${slash === -1 ? '' : scrubPath(rest.slice(slash))}`;
}

/** pokeclip.com과 그 하위 주소, 그리고 지금 페이지의 호스트(로컬 개발)만 우리 주소로 본다 */
function isOwnHost(hostname: string): boolean {
  if (hostname === 'pokeclip.com' || hostname.endsWith('.pokeclip.com')) return true;
  return typeof window !== 'undefined' && hostname === window.location.hostname;
}

/**
 * 절대 주소면 우리 주소는 origin + 정제한 경로, 바깥 주소는 origin만. `/`로 시작하는 경로면 정제한 경로.
 * 그 밖의 값은 그대로 둔다.
 */
export function scrubUrl(value: string): string {
  if (value.startsWith('/')) return scrubPath(value);
  if (!/^https?:\/\//i.test(value)) return value;
  const live = scrubLiveMediaUrl(value);
  if (live !== null) return live;
  try {
    const url = new URL(value);
    return isOwnHost(url.hostname) ? `${url.origin}${scrubPath(url.pathname)}` : url.origin;
  } catch {
    // 주소처럼 생겼는데 못 읽으면 쿼리만이라도 뗀다
    return value.replace(/[?#].*$/, '');
  }
}

/** landing/README 「홍보 링크와 UTM」 표 — 값을 더하면 그 표와 landing/analytics.js도 함께 고친다 */
const UTM_ALLOWED: Record<string, RegExp> = {
  utm_source: /^(youtube|chzzk|soop|discord|kakao|x|naver|instagram)$/,
  utm_medium: /^(video_description|profile|community|post|dm|ad)$/,
  utm_campaign: /^(launch_2026_10|\d{4}_\d{2}_[a-z0-9_]{1,40})$/,
  utm_content: /^[a-z0-9_]{1,32}$/,
};
const UTM_KEY_RE = /^(?:\$initial_)?(utm_[a-z]+)$/;
/** SDK가 주소에서 따로 뽑는 광고 클릭 식별자(posthog-js 1.435 캠페인 매개변수 목록) — 쓰지 않으니 지운다 */
const CLICK_ID_KEY_RE =
  /^(?:\$initial_)?(?:gad_source|mc_cid|gclid|gclsrc|dclid|gbraid|wbraid|fbclid|msclkid|twclid|li_fat_id|igshid|ttclid|rdt_cid|epik|qclid|sccid|oppref|irclid|_kx)$/;

/** 홍보 링크 속성이면 정리하고 true. utm_term·광고 클릭 식별자처럼 담지 않을 것은 지운다 */
function scrubCampaignProperty(properties: Properties, key: string, value: unknown): boolean {
  if (CLICK_ID_KEY_RE.test(key)) {
    delete properties[key];
    return true;
  }
  const utm = UTM_KEY_RE.exec(key)?.[1];
  if (utm === undefined) return false;
  const allowed = UTM_ALLOWED[utm];
  if (allowed === undefined) delete properties[key];
  else if (typeof value === 'string' && value !== '' && !allowed.test(value))
    properties[key] = 'other';
  return true;
}

function scrubProperties(properties: Properties | undefined): void {
  if (!properties) return;
  for (const [key, value] of Object.entries(properties)) {
    if (scrubCampaignProperty(properties, key, value)) continue;
    if (typeof value === 'string') {
      // $pathname·$prev_pageview_pathname 같은 경로 값과 $current_url·$referrer 같은 주소 값
      if (/pathname$/.test(key) || /^https?:\/\//i.test(value)) properties[key] = scrubUrl(value);
    } else if ((key === '$set' || key === '$set_once') && value && typeof value === 'object') {
      // $identify·$set 이벤트는 사람 속성($initial_current_url 등)을 properties 안에도 싣는다
      scrubProperties(value as Properties);
    }
  }
}

function pathOf(properties: Properties): string | null {
  const pathname = properties.$pathname;
  if (typeof pathname === 'string') return pathname.replace(/[?#].*$/, '');
  const current = properties.$current_url;
  if (typeof current !== 'string') return null;
  try {
    return new URL(current).pathname;
  } catch {
    return null;
  }
}

/** rrweb 메타 이벤트(type 4)는 녹화가 시작된 페이지 주소를 쿼리째 싣는다 (POK-358) */
const RRWEB_META = 4;

function scrubSnapshots(properties: Properties): void {
  const snapshots: unknown = properties.$snapshot_data;
  if (!Array.isArray(snapshots)) return;
  for (const item of snapshots) {
    const data = (item as { type?: unknown; data?: { href?: unknown } } | null)?.data;
    if ((item as { type?: unknown }).type === RRWEB_META && typeof data?.href === 'string') {
      data.href = scrubUrl(data.href);
    }
  }
}

/** PostHog `before_send` — 보낼 이벤트를 고쳐 돌려주거나, 보내지 않을 이벤트면 null. */
export function scrubEvent(event: CaptureResult | null): CaptureResult | null {
  if (event === null) return null;
  const properties = event.properties ?? {};
  if (PAGE_EVENTS.has(event.event)) {
    const path = pathOf(properties);
    if (path !== null && OAUTH_CALLBACK_PATHS.has(path)) return null;
  }
  scrubProperties(properties);
  scrubProperties(event.$set);
  scrubProperties(event.$set_once);
  if (event.event === '$snapshot') scrubSnapshots(properties);
  return event;
}

const URL_ATTRIBUTES = new Set(['href', 'src', 'srcset', 'poster', 'action', 'formaction']);
/** 사람이 읽는 글을 담는 속성 — 글자 가리기(maskTextSelector)는 속성을 건드리지 않는다. 채널 이름·회원 이름이 여기 실린다 */
const TEXT_ATTRIBUTES = new Set([
  'aria-label',
  'aria-description',
  'aria-valuetext',
  'aria-placeholder',
  'title',
  'alt',
  'placeholder',
  'value',
]);

/**
 * 녹화 속 요소 속성을 가린다 — `session_recording.maskAttributeFn`.
 * 링크·이미지 주소는 쿼리와 방송 번호를 떼고(?stream=·?card=, /broadcast/vod/<번호>), 글 속성은 글자 수만 남긴다.
 */
export function scrubAttribute(name: string, value: string): string {
  if (URL_ATTRIBUTES.has(name)) return scrubUrl(value);
  if (TEXT_ATTRIBUTES.has(name)) return '*'.repeat(value.length);
  return value;
}
