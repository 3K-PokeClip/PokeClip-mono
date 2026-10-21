import { describe, expect, it } from 'vitest';
import { selectPluginRelease } from './parseRelease';

const REPO_DL = 'https://github.com/3K-PokeClip/PokeClip-mono/releases/download';

function asset(tag: string, name: string, extra: Record<string, unknown> = {}) {
  return {
    name,
    state: 'uploaded',
    size: 1234,
    browser_download_url: `${REPO_DL}/${tag}/${name}`,
    ...extra,
  };
}

/** GitHub `GET /releases` 한 줄 모양 — 판 core는 파일 이름에, 태그엔 꼬리까지 */
function release(
  tag: string,
  opts: {
    core?: string;
    prerelease?: boolean;
    draft?: boolean;
    publishedAt?: string;
    assets?: unknown[];
  } = {},
) {
  const core = opts.core ?? tag.replace(/^obs-plugin-v/, '').replace(/-.*$/, '');
  return {
    tag_name: tag,
    draft: opts.draft ?? false,
    prerelease: opts.prerelease ?? false,
    published_at: opts.publishedAt ?? '2026-10-20T03:00:00Z',
    assets: opts.assets ?? [
      asset(tag, `pokeclip-obs-${core}-windows-x64-installer.exe`),
      asset(tag, `pokeclip-obs-${core}-windows-x64.zip`),
      asset(tag, `pokeclip-obs-${core}-macos-universal.pkg`),
      asset(tag, 'SHA256SUMS.txt'),
    ],
  };
}

describe('selectPluginRelease: 웹이 내보일 플러그인 Release 고르기', () => {
  it('Release가 없으면 null(=아직 없음)이다', () => {
    expect(selectPluginRelease([])).toBeNull();
  });

  it('응답이 배열이 아니면 던진다 — 모양이 틀린 응답을 「아직 없음」으로 바꾸지 않는다', () => {
    expect(() => selectPluginRelease({ message: 'Not Found' })).toThrow();
  });

  it('정식판 하나를 설치 파일 둘로 정리하고, 포터블 zip·SHA256SUMS 같은 다른 자산은 무시한다', () => {
    const tag = 'obs-plugin-v0.2.0';
    const picked = selectPluginRelease([release(tag)]);
    expect(picked).toMatchObject({
      version: '0.2.0',
      tag,
      prerelease: false,
      releaseUrl: `https://github.com/3K-PokeClip/PokeClip-mono/releases/tag/${tag}`,
    });
    expect(Object.keys(picked?.assets ?? {}).sort()).toEqual(['installer', 'pkg']);
    expect(picked?.assets.installer?.url).toBe(
      `${REPO_DL}/${tag}/pokeclip-obs-0.2.0-windows-x64-installer.exe`,
    );
    expect(picked?.assets.pkg?.sizeBytes).toBe(1234);
  });

  it('다른 제품의 Release는 고르지 않는다: 모노레포라 /releases/latest를 쓰지 않는 이유다', () => {
    expect(selectPluginRelease([release('web-v1.0.0', { core: '1.0.0' })])).toBeNull();
    const picked = selectPluginRelease([
      release('web-v9.0.0', { core: '9.0.0' }),
      release('obs-plugin-v0.2.0'),
    ]);
    expect(picked?.tag).toBe('obs-plugin-v0.2.0');
  });

  it('draft는 빼고, 시험판은 옵션이 있을 때만 넣는다', () => {
    expect(selectPluginRelease([release('obs-plugin-v0.3.0', { draft: true })])).toBeNull();
    const rc = release('obs-plugin-v0.2.0-rc.1', { prerelease: true });
    expect(selectPluginRelease([rc])).toBeNull();
    const picked = selectPluginRelease([rc], { includePrerelease: true });
    // 표기 판엔 꼬리가 있고, 파일 이름엔 buildspec 판(꼬리 없음)이 들어간다
    expect(picked?.version).toBe('0.2.0-rc.1');
    expect(picked?.assets.installer?.name).toBe('pokeclip-obs-0.2.0-windows-x64-installer.exe');
  });

  it('판 번호를 숫자로 비교한다: 0.10.0이 0.9.0보다 높고, 나중에 올라온 옛 판이 최신을 뺏지 않는다', () => {
    const picked = selectPluginRelease([
      release('obs-plugin-v0.9.0', { publishedAt: '2026-12-01T00:00:00Z' }),
      release('obs-plugin-v0.10.0', { publishedAt: '2026-11-01T00:00:00Z' }),
    ]);
    expect(picked?.tag).toBe('obs-plugin-v0.10.0');
  });

  it('판이 같으면 늦게 공개된 것이다', () => {
    const picked = selectPluginRelease(
      [
        release('obs-plugin-v0.3.0-rc.1', {
          prerelease: true,
          publishedAt: '2026-11-01T00:00:00Z',
        }),
        release('obs-plugin-v0.3.0', { publishedAt: '2026-11-05T00:00:00Z' }),
      ],
      { includePrerelease: true },
    );
    expect(picked?.tag).toBe('obs-plugin-v0.3.0');
  });

  it('파일 이름의 판이 태그와 다르면 그 파일은 버린다', () => {
    const tag = 'obs-plugin-v0.3.0';
    const picked = selectPluginRelease([
      release(tag, {
        assets: [
          asset(tag, 'pokeclip-obs-0.2.0-windows-x64-installer.exe'),
          asset(tag, 'pokeclip-obs-0.3.0-macos-universal.pkg'),
        ],
      }),
    ]);
    expect(picked?.assets.installer).toBeUndefined();
    expect(picked?.assets.pkg).toBeDefined();
  });

  it('다른 저장소·다른 태그·http 주소, 올리다 만 자산, 빈 파일은 링크로 내보이지 않는다', () => {
    const tag = 'obs-plugin-v0.2.0';
    const name = 'pokeclip-obs-0.2.0-windows-x64-installer.exe';
    const bad = [
      asset(tag, name, {
        browser_download_url: `https://github.com/evil/repo/releases/download/${tag}/${name}`,
      }),
      asset(tag, name, { browser_download_url: `${REPO_DL}/obs-plugin-v0.1.0/${name}` }),
      asset(tag, name, {
        browser_download_url: `http://github.com/3K-PokeClip/PokeClip-mono/releases/download/${tag}/${name}`,
      }),
      asset(tag, name, { state: 'starter' }),
      asset(tag, name, { size: 0 }),
    ];
    for (const a of bad) {
      expect(selectPluginRelease([release(tag, { assets: [a] })])).toBeNull();
    }
  });

  it('일부 파일만 빠지면 그 칸만 빈다', () => {
    const tag = 'obs-plugin-v0.2.0';
    const picked = selectPluginRelease([
      release(tag, { assets: [asset(tag, 'pokeclip-obs-0.2.0-macos-universal.pkg')] }),
    ]);
    expect(picked?.assets.pkg).toBeDefined();
    expect(picked?.assets.installer).toBeUndefined();
  });

  it('설치 파일이 하나도 없는 Release는 건너뛰고 다음 후보를 고른다', () => {
    const picked = selectPluginRelease([
      release('obs-plugin-v0.3.0', { assets: [asset('obs-plugin-v0.3.0', 'SHA256SUMS.txt')] }),
      release('obs-plugin-v0.2.0'),
    ]);
    expect(picked?.tag).toBe('obs-plugin-v0.2.0');
  });

  it('모르는 필드·틀린 타입의 줄은 무시한다', () => {
    const picked = selectPluginRelease([null, 'x', { tag_name: 42 }, release('obs-plugin-v0.2.0')]);
    expect(picked?.tag).toBe('obs-plugin-v0.2.0');
  });
});
