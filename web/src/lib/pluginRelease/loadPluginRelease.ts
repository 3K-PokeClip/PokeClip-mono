import { selectPluginRelease } from './parseRelease';
import { PLUGIN_RELEASE_REPO, type PluginReleaseResponse } from './types';

// 서버 전용 — app/api/plugin-release/route.ts만 부른다. 클라이언트에서 import하지 않는다(토큰 env를 읽는다).
//
// 캐시는 Next fetch 캐시가 아니라 이 모듈의 메모다: fetch 캐시는 200만 저장해서(patch-fetch), GitHub가
// 한도 초과(403)·장애를 주는 동안엔 요청마다 다시 때린다. 비로그인 한도는 서버 IP당 시간 60회이고
// ETag 304도 한도를 쓴다(2026-10-21 실측) — 그래서 조건부 요청 대신 5분 메모로 시간당 12회 이하로 묶는다.

export const TTL_MS = 5 * 60_000;
/** 실패 때 대신 줄 지난 값의 한계. 공개된 Release·자산은 덮어쓰지 않으므로(워크플로) 지난 링크도 유효하다 */
export const STALE_MAX_MS = 24 * 60 * 60_000;
export const FAILURE_BACKOFF_MS = 60_000;
const TIMEOUT_MS = 5_000;
export const RELEASES_API = `https://api.github.com/repos/${PLUGIN_RELEASE_REPO}/releases?per_page=100`;

export type CacheState = 'hit' | 'miss' | 'stale';

export interface LoadDeps {
  fetch?: typeof fetch;
  now?: () => number;
  /** 기본은 env OBS_PLUGIN_INCLUDE_PRERELEASE=1 — dev 웹에서만 켠다 */
  includePrerelease?: boolean;
  /** 기본은 env OBS_PLUGIN_GITHUB_TOKEN(선택, 읽기 권한만) — 있으면 한도가 시간 5000회가 된다 */
  token?: string;
}

/** GitHub에서 못 읽었고 대신 줄 지난 값도 없다 — 라우트가 503으로 바꾼다 */
export class PluginReleaseUnavailableError extends Error {}

interface Entry {
  body: PluginReleaseResponse;
  fetchedAt: number;
}

let entry: Entry | undefined;
/** 실패 뒤 이 시각 전엔 GitHub를 다시 부르지 않는다 */
let blockedUntil = 0;
/** 동시에 들어온 요청은 GitHub 한 번으로 합친다 */
let inflight: Promise<Entry> | undefined;

export async function loadPluginRelease(
  deps: LoadDeps = {},
): Promise<{ body: PluginReleaseResponse; cache: CacheState }> {
  const now = deps.now ?? Date.now;
  if (entry && now() - entry.fetchedAt < TTL_MS) return { body: entry.body, cache: 'hit' };
  if (now() < blockedUntil) return staleOrThrow(now());

  try {
    inflight ??= fetchFresh(deps, now).finally(() => {
      inflight = undefined;
    });
    entry = await inflight;
    return { body: entry.body, cache: 'miss' };
  } catch {
    return staleOrThrow(now());
  }
}

/** 테스트 전용 — 모듈 메모를 비운다 */
export function resetPluginReleaseCache() {
  entry = undefined;
  blockedUntil = 0;
  inflight = undefined;
}

function staleOrThrow(t: number) {
  if (entry && t - entry.fetchedAt < STALE_MAX_MS)
    return { body: entry.body, cache: 'stale' as const };
  throw new PluginReleaseUnavailableError('플러그인 Release 정보를 읽지 못했다');
}

async function fetchFresh(deps: LoadDeps, now: () => number): Promise<Entry> {
  const token = deps.token ?? process.env.OBS_PLUGIN_GITHUB_TOKEN;
  const includePrerelease =
    deps.includePrerelease ?? process.env.OBS_PLUGIN_INCLUDE_PRERELEASE === '1';
  try {
    const res = await (deps.fetch ?? fetch)(RELEASES_API, {
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
      headers: {
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        // User-Agent가 없으면 GitHub가 403을 준다
        'User-Agent': 'pokeclip-web',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
    if (!res.ok) {
      blockedUntil = Math.max(now() + FAILURE_BACKOFF_MS, retryAt(res, now()));
      // 로그엔 상태 코드만 — 응답 본문·토큰은 남기지 않는다
      console.warn(`[plugin-release] GitHub releases 조회 실패: ${res.status}`);
      throw new Error(`GitHub ${res.status}`);
    }
    const release = selectPluginRelease(await res.json(), { includePrerelease });
    return {
      body: release ? { status: 'ready', release } : { status: 'unavailable' },
      fetchedAt: now(),
    };
  } catch (err) {
    blockedUntil = Math.max(blockedUntil, now() + FAILURE_BACKOFF_MS);
    throw err;
  }
}

/** 한도 초과 응답이 알려 준 재시도 시각(ms). 모르면 0 */
function retryAt(res: Response, t: number): number {
  const retryAfter = Number(res.headers.get('retry-after'));
  if (retryAfter > 0) return t + retryAfter * 1000;
  if (res.headers.get('x-ratelimit-remaining') === '0') {
    // x-ratelimit-reset은 절대 시각(epoch 초)이다
    const reset = Number(res.headers.get('x-ratelimit-reset'));
    if (reset > 0) return reset * 1000;
  }
  return 0;
}
