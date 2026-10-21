import {
  OBS_PLUGIN_TAG_RE,
  PLUGIN_RELEASE_REPO,
  RELEASES_PAGE_URL,
  type PluginAssetKind,
  type PluginRelease,
  type PluginReleaseAsset,
} from './types';

// GitHub `GET /repos/{repo}/releases` 응답에서 웹이 내보일 플러그인 Release 하나를 고른다 (POK-379).
// `/releases/latest`는 쓰지 않는다 — 저장소 전체의 최신 정식판이라, 모노레포의 다른 제품이 Release를 내면
// 엉뚱한 파일을 가리킨다. 태그 접두어로 걸러 판 번호가 가장 높은 것을 고른다.

/** Package-Windows.ps1·package-macos가 짓는 이름의 꼬리. 앞은 `pokeclip-obs-<X.Y.Z>` (buildspec 판) */
const ASSET_SUFFIX: Record<PluginAssetKind, string> = {
  installer: '-windows-x64-installer.exe',
  portable: '-windows-x64.zip',
  pkg: '-macos-universal.pkg',
};

interface Candidate {
  core: [number, number, number];
  publishedMs: number;
  release: PluginRelease;
}

export interface SelectOptions {
  /** 시험판(prerelease)도 후보에 넣는다 — dev 웹에서 첫 정식판 전에 확인할 때만 */
  includePrerelease?: boolean;
}

/**
 * 고르는 규칙: draft 아님 · 태그가 obs-plugin-v<X.Y.Z>[-꼬리] · (옵션이 없으면) 정식판 · 설치 파일이 하나 이상.
 * 그중 X.Y.Z가 가장 높은 것, 같으면 늦게 공개된 것. 고를 게 없으면 null(=아직 없음).
 * 응답이 배열이 아니면 던진다 — 모양이 틀린 응답을 「아직 없음」으로 바꾸지 않는다.
 */
export function selectPluginRelease(
  releases: unknown,
  { includePrerelease = false }: SelectOptions = {},
): PluginRelease | null {
  if (!Array.isArray(releases)) throw new Error('releases 응답이 배열이 아니다');

  const candidates = releases
    .map((raw) => toCandidate(raw, includePrerelease))
    .filter((c): c is Candidate => c !== null);
  candidates.sort((a, b) => compareCore(b.core, a.core) || b.publishedMs - a.publishedMs);
  return candidates[0]?.release ?? null;
}

function toCandidate(raw: unknown, includePrerelease: boolean): Candidate | null {
  if (!isRecord(raw)) return null;
  const { tag_name: tag, draft, prerelease, published_at: publishedAt } = raw;
  if (draft !== false || typeof prerelease !== 'boolean') return null;
  if (prerelease && !includePrerelease) return null;
  if (typeof tag !== 'string' || typeof publishedAt !== 'string') return null;
  const publishedMs = Date.parse(publishedAt);
  if (Number.isNaN(publishedMs)) return null;

  const match = OBS_PLUGIN_TAG_RE.exec(tag);
  if (!match) return null;
  const core: [number, number, number] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const coreText = core.join('.');

  // 이 저장소·이 태그의 다운로드 경로만 받는다 — 다른 곳을 가리키는 주소는 내보이지 않는다
  const downloadPrefix = `https://github.com/${PLUGIN_RELEASE_REPO}/releases/download/${tag}/`;
  const assets: PluginRelease['assets'] = {};
  for (const asset of Array.isArray(raw.assets) ? raw.assets : []) {
    const parsed = toAsset(asset, downloadPrefix);
    if (!parsed) continue;
    // 파일 이름의 판이 태그의 X.Y.Z와 같아야 한다 — buildspec과 태그가 어긋난 파일을 걸러 낸다
    const kind = (Object.keys(ASSET_SUFFIX) as PluginAssetKind[]).find(
      (k) => parsed.name === `pokeclip-obs-${coreText}${ASSET_SUFFIX[k]}`,
    );
    if (kind) assets[kind] = parsed;
  }
  if (Object.keys(assets).length === 0) return null;

  return {
    core,
    publishedMs,
    release: {
      version: tag.slice('obs-plugin-v'.length),
      tag,
      prerelease,
      publishedAt,
      releaseUrl: `${RELEASES_PAGE_URL}/tag/${tag}`,
      assets,
    },
  };
}

function toAsset(raw: unknown, downloadPrefix: string): PluginReleaseAsset | null {
  if (!isRecord(raw)) return null;
  const { name, state, size, browser_download_url: url } = raw;
  if (typeof name !== 'string' || typeof url !== 'string' || typeof size !== 'number') return null;
  // 올리다 만 자산(state=starter)·빈 파일은 링크로 내보이지 않는다
  if (state !== 'uploaded' || size <= 0) return null;
  if (!url.startsWith(downloadPrefix)) return null;
  return { name, url, sizeBytes: size };
}

function compareCore(a: [number, number, number], b: [number, number, number]): number {
  return a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
