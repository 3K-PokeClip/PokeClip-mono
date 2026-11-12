import type { CaptureResult, Properties } from 'posthog-js';
import { PRIVATE_TEXT_CLASS } from '@/ui/privateText';

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
  // 모양(패턴)이 아니라 목록이다 — 패턴이면 채널 ID·핸들 같은 소문자 값이 통과한다(PR #382 리뷰). 새 캠페인·자리는 표부터 더한다
  utm_campaign: /^(launch_2026_10)$/,
  utm_content: /^(pinned_comment|banner)$/,
};
/**
 * SDK가 홍보 링크 값을 싣는 자리 셋 — 그 페이지의 값(접두 없음), 처음 방문(`$initial_`, 사람 속성), 그 세션의 첫 진입
 * (`$session_entry_`, 모든 이벤트 — posthog-js session-props.js `getSessionProps`). 셋째를 빠뜨리면 utm_term·클릭 식별자가
 * 그 세션의 모든 이벤트에 실려 나갔다(PR #382 3차 리뷰)
 */
const UTM_KEY_RE = /^(?:\$initial_|\$session_entry_)?(utm_[a-z]+)$/;
/**
 * SDK가 주소에서 따로 뽑는 광고 클릭 식별자(@posthog/browser-common 0.9.5 `CAMPAIGN_PARAMS`·`PERSONAL_DATA_CAMPAIGN_PARAMS`)와
 * 그것으로 만드는 Meta 식별자(`$fbc` = fbclid·`_fbc` 쿠키, `$fbp` = `_fbp` 쿠키, `$set`에 실림) — 쓰지 않으니 지운다
 */
const CLICK_ID_KEY_RE =
  /^(?:(?:\$initial_|\$session_entry_)?(?:gad_source|mc_cid|gclid|gclsrc|dclid|gbraid|wbraid|fbclid|msclkid|twclid|li_fat_id|igshid|ttclid|rdt_cid|epik|qclid|sccid|oppref|bfcid|irclid|_kx)|\$fbc|\$fbp)$/;

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

// 가림 칸 = PRIVATE_TEXT_CLASS(@/ui, SDK 기본 maskTextClass와 같은 'ph-mask')를 단 요소 (POK-572). 녹화는 단추·메뉴·안내문
// 글자를 그대로 담고, 사람을 가리키거나 사람이 쓴 글자(채널·회원 이름, 이메일, 방송·클립·업로드 제목, 알림 내용, 편집기에 넣은
// 글자 등)가 나오는 칸에만 이 클래스를 단다. 화면은 @/ui에서 가져다 쓴다 — 분석 모듈을 통째로 목 하는 테스트가 상수 하나 때문에
// 깨지지 않게. 규칙은 web/docs/ANALYTICS.md 「세션 녹화」

/** 가림 표시가 없어도 가리는 글자 — 이메일 꼴. 표시를 빠뜨린 칸의 마지막 그물이다 */
const EMAIL_RE = /[^\s@]+@[^\s@]+\.[^\s@]+/g;

function stars(text: string): string {
  return text.replace(/\S/g, '*');
}

function inMaskedArea(element: Element | null | undefined): boolean {
  if (!element) return true; // 어느 요소의 글자인지 모르면 가린다
  // <title>(문서 제목·SVG 툴팁)은 화면에 안 보이고 방송·채널 이름이 실리기 쉬워 늘 가린다
  if (element.tagName.toUpperCase() === 'TITLE') return true;
  return element.closest(`.${PRIVATE_TEXT_CLASS}`) !== null;
}

/**
 * 녹화 속 글자를 요소마다 가린다 — `session_recording.maskTextFn` (POK-572). `maskTextSelector: '*'`와 함께 써서 모든 글자가
 * 이 함수를 거친다. 가림 칸(PRIVATE_TEXT_CLASS) 안이거나 어느 요소인지 모르면 전부, 그 밖에서는 이메일 꼴만 `*`로 바꾼다.
 * rrweb이 화면을 읽는 도중에 부르므로 던지지 않는다 — 판단이 실패하면 가린다.
 */
export function maskReplayText(text: string, element?: Element | null): string {
  try {
    return inMaskedArea(element) ? stars(text) : text.replace(EMAIL_RE, stars);
  } catch {
    return stars(text);
  }
}

/**
 * 녹화에서 통째로 빼는 요소(크기만 남는다) — 영상·캔버스·iframe과, 우리 그림(`public/brand/`) 밖의 이미지.
 * 썸네일·프로필 사진은 바깥 주소(서명 토큰)·`data:`라 빠지고, 포키 그림만 남는다. `next/image`는 src를
 * `/_next/image?url=%2Fbrand%2F…`로 바꾸므로 두 꼴을 다 둔다. 허용 목록이라 src가 비었거나 다른 이미지는 빠진다
 */
export const REPLAY_BLOCK_SELECTOR =
  'img:not([src^="/brand/"]):not([src^="/_next/image?url=%2Fbrand%2F"]), picture, video, canvas, iframe';

/** 우리 그림 주소(경로+쿼리) — 녹화에서 빼지 않는 이미지(REPLAY_BLOCK_SELECTOR)와 같은 두 꼴 */
const BRAND_ASSET_RE = /^\/(?:brand\/|_next\/image\?url=%2Fbrand%2F)/;

function isBrandAssetUrl(url: string): boolean {
  if (url.startsWith('/')) return BRAND_ASSET_RE.test(url);
  try {
    const parsed = new URL(url);
    return isOwnHost(parsed.hostname) && BRAND_ASSET_RE.test(parsed.pathname + parsed.search);
  } catch {
    return false;
  }
}

/**
 * src·srcset이 모두 우리 그림이면 참 — 그 쿼리(`?url=%2Fbrand%2F…&w=…`)에는 비밀값이 없고, 떼면 next/image 주소가 깨져
 * 녹화에 빈 그림이 남는다(POK-572 실측: `srcset`이 `/_next/image` 하나로 뭉개졌다). srcset은 「주소 너비, …」 목록이다
 */
function isBrandAsset(value: string): boolean {
  const urls = value
    .split(',')
    .map((candidate) => candidate.trim().split(/\s+/)[0] ?? '')
    .filter((url) => url !== '');
  return urls.length > 0 && urls.every(isBrandAssetUrl);
}

const URL_ATTRIBUTES = new Set(['href', 'src', 'srcset', 'poster', 'action', 'formaction']);
/**
 * 사람이 읽는 글을 담는 속성 — 글자 가리기(maskTextFn)는 속성을 건드리지 않는다. 채널 이름·회원 이름이 여기 실린다.
 * 녹화 화면에 보이지 않는 속성이라 늘 가린다
 */
const TEXT_ATTRIBUTES = new Set([
  'aria-label',
  'aria-description',
  'aria-valuetext',
  'aria-placeholder',
  'title',
  'alt',
  'value',
]);

/**
 * 녹화 속 요소 속성을 가린다 — `session_recording.maskAttributeFn`.
 * 링크·이미지 주소는 쿼리와 방송 번호를 떼고(?stream=·?card=, /broadcast/vod/<번호>), 글 속성은 글자 수만 남긴다.
 * 빈 입력 칸에 보이는 placeholder는 글자와 같은 규칙(maskReplayText)을 따른다 — 「검색어 입력」 같은 안내가 보여야 한다.
 */
export function scrubAttribute(name: string, value: string, element?: Element | null): string {
  if ((name === 'src' || name === 'srcset') && isBrandAsset(value)) return value;
  if (URL_ATTRIBUTES.has(name)) return scrubUrl(value);
  if (TEXT_ATTRIBUTES.has(name)) return '*'.repeat(value.length);
  if (name === 'placeholder') return maskReplayText(value, element);
  return value;
}
