import type { CaptureResult, Properties } from 'posthog-js';

// 분석 도구(PostHog)로 나가는 이벤트에서 주소에 실린 비밀값을 지운다 (POK-356).
// - 쿼리와 해시는 통째로 뗀다. OAuth 콜백의 code·state, 화면의 ?stream=·?card=·?recipe= 가 다 여기 실린다.
//   유입 분석에 쓰는 utm_* 는 SDK가 before_send 전에 따로 속성으로 뽑아 두므로 쿼리를 떼도 남는다.
// - 예전 방송 번호는 스트림 키와 같다(services/README 「clip: 방송 번호와 물리 키」) — /broadcast/vod/<번호>도 가린다.
// - OAuth 콜백 화면의 페이지 조회는 담을 것이 없으니 아예 보내지 않는다.

const OAUTH_CALLBACK_PATHS = new Set([
  '/auth/callback',
  '/oauth/chzzk/callback',
  '/oauth/youtube/callback',
]);
const PAGE_EVENTS = new Set(['$pageview', '$pageleave']);
const VOD_PATH_RE = /^\/broadcast\/vod\/[^/]+/;

function scrubPath(path: string): string {
  return path.replace(/[?#].*$/, '').replace(VOD_PATH_RE, '/broadcast/vod/_');
}

/** 절대 주소면 origin + 정제한 경로, `/`로 시작하는 경로면 정제한 경로. 그 밖의 값은 그대로 둔다. */
export function scrubUrl(value: string): string {
  if (value.startsWith('/')) return scrubPath(value);
  if (!/^https?:\/\//i.test(value)) return value;
  try {
    const url = new URL(value);
    return `${url.origin}${scrubPath(url.pathname)}`;
  } catch {
    // 주소처럼 생겼는데 못 읽으면 쿼리만이라도 뗀다
    return value.replace(/[?#].*$/, '');
  }
}

function scrubProperties(properties: Properties | undefined): void {
  if (!properties) return;
  for (const [key, value] of Object.entries(properties)) {
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
