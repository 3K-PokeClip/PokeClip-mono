import { describe, expect, it } from 'vitest';
import { PLUGIN_DOWNLOADS, PLUGIN_VERSION } from './pluginDownload';

// 고정 주소가 배포 워크플로(obs-plugin-release.yml)의 태그·파일 이름과 어긋나면 GitHub 404로 떨어진다.
describe('pluginDownload: 고정 주소', () => {
  it('판은 X.Y.Z — 시험판 꼬리를 붙이면 태그와 파일 이름의 판이 갈라져 주소가 틀어진다', () => {
    expect(PLUGIN_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('obs-plugin-v<판> 태그의 다운로드 경로에 buildspec 판으로 지은 파일 이름이다', () => {
    const dl = `https://github.com/3K-PokeClip/PokeClip-mono/releases/download/obs-plugin-v${PLUGIN_VERSION}`;
    expect(PLUGIN_DOWNLOADS.windows).toEqual({
      kind: 'installer',
      url: `${dl}/pokeclip-obs-${PLUGIN_VERSION}-windows-x64-installer.exe`,
    });
    expect(PLUGIN_DOWNLOADS.macos).toEqual({
      kind: 'pkg',
      url: `${dl}/pokeclip-obs-${PLUGIN_VERSION}-macos-universal.pkg`,
    });
  });
});
