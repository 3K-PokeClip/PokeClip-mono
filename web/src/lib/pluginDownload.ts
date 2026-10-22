// OBS 플러그인 설치 파일 주소 (POK-379) — 설정 → 플러그인의 다운로드 창이 쓴다.
// 정식 배포 전까지는 mono 공개 저장소의 GitHub Release 파일을 고정 주소로 건다 — 웹은 GitHub를 조회하지 않는다.
// 새 판을 내면 PLUGIN_VERSION만 올린다: 파일 이름은 .github/workflows/obs-plugin-release.yml이 buildspec 판으로 짓는다.
// 정식 배포 때 S3 + CloudFront로 옮기면 PLUGIN_DOWNLOAD_BASE만 바꾼다(mono를 비공개로 돌리면 Release 파일이 막힌다).
// 설치·연결 상태는 여기 없다 — 웹은 플러그인이 깔렸는지·붙었는지 알 수 없다(POK-377).

export const PLUGIN_VERSION = '0.2.0';

const PLUGIN_RELEASE_REPO = '3K-PokeClip/PokeClip-mono';
const PLUGIN_DOWNLOAD_BASE = `https://github.com/${PLUGIN_RELEASE_REPO}/releases/download/obs-plugin-v${PLUGIN_VERSION}`;

/** 지난 판·포터블 zip·SHA256SUMS.txt는 Release 페이지에만 있다 */
export const RELEASES_PAGE_URL = `https://github.com/${PLUGIN_RELEASE_REPO}/releases`;

export type PluginOs = 'windows' | 'macos';
/** installer=Windows 설치 파일(exe) · pkg=macOS 설치 파일. 포터블 OBS용 zip은 웹에서 내보이지 않는다 */
export type PluginAssetKind = 'installer' | 'pkg';

export const PLUGIN_DOWNLOADS: Record<PluginOs, { kind: PluginAssetKind; url: string }> = {
  windows: {
    kind: 'installer',
    url: `${PLUGIN_DOWNLOAD_BASE}/pokeclip-obs-${PLUGIN_VERSION}-windows-x64-installer.exe`,
  },
  macos: {
    kind: 'pkg',
    url: `${PLUGIN_DOWNLOAD_BASE}/pokeclip-obs-${PLUGIN_VERSION}-macos-universal.pkg`,
  },
};
