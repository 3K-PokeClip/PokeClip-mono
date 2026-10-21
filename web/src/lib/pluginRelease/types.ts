// OBS 플러그인 설치 파일 계약 (POK-379) — 서버 라우트 /api/plugin-release와 설정 → 플러그인 화면이 같이 쓴다.
// 설치 파일은 .github/workflows/obs-plugin-release.yml이 공개 저장소의 GitHub Release에 올린다. 웹은 읽기만 한다.
// 설치·연결 상태는 이 계약에 없다 — 웹은 플러그인이 깔렸는지·붙었는지 알 수 없다(POK-377).

export const PLUGIN_RELEASE_REPO = '3K-PokeClip/PokeClip-mono';
export const RELEASES_PAGE_URL = `https://github.com/${PLUGIN_RELEASE_REPO}/releases`;

/** 워크플로 version 잡과 같은 태그 모양. 1~3번 묶음이 X.Y.Z, 4번이 시험판 꼬리(-rc.1 …) */
export const OBS_PLUGIN_TAG_RE = /^obs-plugin-v(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?$/;

export type PluginOs = 'windows' | 'macos';
/** installer=Windows 설치 파일(exe) · pkg=macOS 설치 파일. 포터블 OBS용 zip은 웹에서 내보이지 않는다 */
export type PluginAssetKind = 'installer' | 'pkg';

export interface PluginReleaseAsset {
  name: string;
  /** GitHub가 준 browser_download_url 그대로 — 조립하지 않는다 */
  url: string;
  sizeBytes: number;
}

export interface PluginRelease {
  /** 태그에서 접두어를 뗀 판. 시험판이면 꼬리까지(0.2.0-rc.1) — 파일 이름의 판엔 꼬리가 없다 */
  version: string;
  tag: string;
  prerelease: boolean;
  publishedAt: string;
  releaseUrl: string;
  /** 빠진 파일은 키가 없다 — 다른 판의 파일로 채우지 않는다 */
  assets: Partial<Record<PluginAssetKind, PluginReleaseAsset>>;
}

export type PluginReleaseResponse =
  { status: 'ready'; release: PluginRelease } | { status: 'unavailable' };
