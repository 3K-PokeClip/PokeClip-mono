import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

// 영상 출입증을 받아 두는 한 곳(POK-317). 라이브 화면 · 편집기 · 다시보기가 모두 여기서 받는다.

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('영상 출입증 받기와 쿠키 싣기 (POK-317)', () => {
  // 모듈 안 기억(받은 출입증 · 쿠키 표시)이 시험끼리 새지 않게 매번 새로 읽는다
  async function load(answer: (url: string) => Response, playbackBase = 'https://media.test') {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', playbackBase);
    const spy = stubFetch((url) => Promise.resolve(answer(String(url))));
    // 문 부르기는 clipEditor, 받아 두기 · 다시 쓰기 · 403 처리는 playbackAccess다
    const api = {
      ...(await import('@/api/clipEditor')),
      ...(await import('@/api/playbackAccess')),
    };
    const media = await import('@/api/mediaPlayback');
    const posts = () =>
      spy.mock.calls
        .filter(([url]) => String(url).endsWith('/playback-access'))
        .map(([url]) => String(url));
    return { api, media, posts };
  }
  const granted = (expiresAt: string) => () => jsonResponse(200, { expiresAt });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
  });

  it('서명 재료가 없는 503만 「필요 없음」이다: 자격 확인이 안 되는 503은 실패라 다시 묻는다', async () => {
    const off = await load(() => jsonResponse(503, { error: 'playback_signing_unavailable' }));
    expect(await off.api.requestPlaybackAccess('s1')).toEqual({ kind: 'not_needed' });
    const authDown = await load(() => jsonResponse(503, { error: 'authorization_unavailable' }));
    expect(await authDown.api.requestPlaybackAccess('s1')).toEqual({ kind: 'failed' });
  });

  it('출입증을 실제로 받기 전에는 쿠키를 안 싣는다: 로컬 영상 서버는 Allow-Origin * 라 쿠키를 실으면 응답을 버린다', async () => {
    const off = await load(() => jsonResponse(503, { error: 'playback_signing_unavailable' }));
    expect(off.media.mediaCredentials()).toBe('same-origin');
    await off.api.requestPlaybackAccess('s1');
    expect(off.media.mediaCredentials()).toBe('same-origin');

    const on = await load(granted('2026-10-23T01:00:00Z'));
    await on.api.requestPlaybackAccess('s1');
    expect(on.media.mediaCredentials()).toBe('include');
  });

  it('받은 출입증은 만료 5분 전까지 다시 쓴다: 그 뒤에는 다시 받는다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-23T00:00:00Z'));
    const { api, posts } = await load(granted('2026-10-23T01:00:00Z'));

    await api.ensurePlaybackAccess('s1', 'key-1');
    await api.ensurePlaybackAccess('s1', 'key-1');
    expect(posts()).toHaveLength(1);

    vi.setSystemTime(Date.parse('2026-10-23T00:54:59Z'));
    await api.ensurePlaybackAccess('s1', 'key-1');
    expect(posts()).toHaveLength(1);
    vi.setSystemTime(Date.parse('2026-10-23T00:55:01Z'));
    await api.ensurePlaybackAccess('s1', 'key-1');
    expect(posts()).toHaveLength(2);
  });

  it('같은 방송이라도 영상 키가 바뀌면 다시 받는다: 쿠키 경로가 영상 키라 옛 키로 받은 것은 새 주소에 안 실린다(PR #320 codex)', async () => {
    const { api, posts } = await load(granted('2999-01-01T00:00:00Z'));
    await api.ensurePlaybackAccess('s1', 's1');
    await api.ensurePlaybackAccess('s1', 's1');
    expect(posts()).toHaveLength(1);
    await api.ensurePlaybackAccess('s1', 'physical-key');
    expect(posts()).toHaveLength(2);
  });

  it('동시에 여러 번 불러도 한 번만 묻는다: 편집기 화면과 미리보기가 같은 방송을 거의 동시에 부른다', async () => {
    const { api, posts } = await load(granted('2026-10-23T01:00:00Z'));
    await Promise.all([api.ensurePlaybackAccess('s1', 'k'), api.ensurePlaybackAccess('s1', 'k')]);
    expect(posts()).toHaveLength(1);
  });

  it('실패는 기억하지 않는다: 다음에 부르면 다시 묻는다', async () => {
    const { api, posts } = await load(() => jsonResponse(500, {}));
    await api.ensurePlaybackAccess('s1', 'k');
    await api.ensurePlaybackAccess('s1', 'k');
    expect(posts()).toHaveLength(2);
  });

  it('「필요 없음」을 받은 방송은 더 묻지 않고, 다른 방송은 묻는다: 같은 사유 코드가 한 방송의 키 글자 검사 실패에도 온다(PR #320 codex)', async () => {
    const { api, posts } = await load(() =>
      jsonResponse(503, { error: 'playback_signing_unavailable' }),
    );
    await api.ensurePlaybackAccess('s1', 'k1');
    await api.ensurePlaybackAccess('s1', 'k1');
    expect(posts()).toHaveLength(1);
    await api.ensurePlaybackAccess('s2', 'k2');
    expect(posts()).toHaveLength(2);
  });

  it('라이브 화면이 받은 「필요 없음」도 기억한다: 편집기가 같은 방송의 답을 받으러 다시 가지 않는다', async () => {
    const { api, posts } = await load(() =>
      jsonResponse(503, { error: 'playback_signing_unavailable' }),
    );
    await api.ensureLiveAccess('s1', 'k1');
    expect(api.ensurePlaybackAccess('s1', 'k1')).toBeNull();
    expect(posts()).toHaveLength(1);
  });

  it('녹화 재생 서버 주소가 없는 곳에서는 받지 않는다: 녹화를 부르지 않으니 쿠키도 필요 없다', async () => {
    const { api, posts } = await load(granted('2026-10-23T01:00:00Z'), '');
    expect(api.ensurePlaybackAccess('s1', 'k1')).toBeNull();
    expect(posts()).toHaveLength(0);
  });

  it('녹화 목록이 403이면 받아 둔 출입증을 버리고 다음에 다시 받는다: 다른 탭이나 쿠키 한도로 브라우저가 쿠키를 지웠다(PR #332 codex)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-23T00:00:00Z'));
    const { api, media, posts } = await load(granted('2026-10-23T01:00:00Z'));
    await api.ensurePlaybackAccess('S-1', 'k-1');
    expect(api.ensurePlaybackAccess('S-1', 'k-1')).toBeNull();

    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve(
          String(url).includes('/playback/')
            ? jsonResponse(403, {})
            : jsonResponse(200, { expiresAt: '2026-10-23T01:00:00Z' }),
        ),
      ),
    );
    expect(await media.lookupRecordingSpans('k-1')).toBeNull();
    expect(api.ensurePlaybackAccess('S-1', 'k-1')).not.toBeNull();
    expect(posts()).toHaveLength(1);
  });

  it('영상 키로 부르면 그 키로 받아 둔 방송의 출입증을 다시 쓰거나 새로 받는다: 모르는 키면 묻지 않는다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-23T00:00:00Z'));
    const { api, posts } = await load(granted('2026-10-23T01:00:00Z'));

    await api.ensureMediaAccess('unknown-key');
    expect(posts()).toHaveLength(0);

    await api.ensurePlaybackAccess('S-1', 'key-1');
    await api.ensureMediaAccess('key-1');
    expect(posts()).toHaveLength(1);
    vi.setSystemTime(Date.parse('2026-10-23T00:56:00Z'));
    await api.ensureMediaAccess('key-1');
    expect(posts()).toEqual([
      '/api/clip/broadcasts/S-1/playback-access',
      '/api/clip/broadcasts/S-1/playback-access',
    ]);
  });

  it('라이브 화면 출입증도 같은 곳에서 받아 쓸 만하면 다시 묻지 않는다(PR #332 codex)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-23T00:00:00Z'));
    const { api, posts } = await load(granted('2026-10-23T01:00:00Z'));
    expect((await api.ensureLiveAccess('L-1', 'lk-1')).kind).toBe('granted');
    expect((await api.ensureLiveAccess('L-1', 'lk-1')).kind).toBe('granted');
    expect(posts()).toHaveLength(1);
  });

  it('어느 영상 요청이 403이든 그 키의 출입증을 버린다: 라이브 화면이 받은 것도 버리고 다시 받는다(PR #332 codex)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-23T00:00:00Z'));
    const { api, posts } = await load(granted('2026-10-23T01:00:00Z'));
    await api.ensureLiveAccess('L-1', 'lk-1');
    expect(api.takePlaybackDrop('L-1')).toBe(false);

    api.reportPlaybackForbidden('lk-1');
    expect(api.takePlaybackDrop('L-1')).toBe(true);
    expect(api.takePlaybackDrop('L-1')).toBe(false);
    await api.ensureLiveAccess('L-1', 'lk-1');
    expect(posts()).toHaveLength(2);
  });

  it('받는 중인 출입증은 버리지 않되 403을 들었다고 적어 둔다: 받은 뒤 라이브 화면이 멎은 재생기를 새로 세운다(POK-337 로컬 리뷰)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-23T00:00:00Z'));
    const { api, posts } = await load(granted('2026-10-23T01:00:00Z'));
    const pending = api.ensureLiveAccess('L-1', 'lk-1');
    api.reportPlaybackForbidden('lk-1');
    await pending;
    // 받는 중이던 것을 그대로 쓴다: 곧 새 쿠키가 왔다
    await api.ensureLiveAccess('L-1', 'lk-1');
    expect(posts()).toHaveLength(1);
    expect(api.takePlaybackDrop('L-1')).toBe(true);
  });

  it('다시보기 오류는 첫 바이트를 물어 403일 때만 출입증을 버리고 다시 받는다: 틈 · 서버 오류는 그대로 둔다(PR #332 codex)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-23T00:00:00Z'));
    let getStatus = 404;
    const { api, posts } = await load((url) =>
      url.includes('/playback/k-1/get')
        ? jsonResponse(getStatus, {})
        : jsonResponse(200, { expiresAt: '2026-10-23T01:00:00Z' }),
    );
    await api.ensurePlaybackAccess('S-1', 'k-1');
    const url = 'https://media.test/playback/k-1/get?start=x&duration=1&format=mp4';

    expect(await api.recoverForbiddenRecording(url, 'k-1')).toBe(false);
    expect(posts()).toHaveLength(1);

    getStatus = 403;
    expect(await api.recoverForbiddenRecording(url, 'k-1')).toBe(true);
    expect(posts()).toHaveLength(2);
  });

  it('보관 기간이 지난 방송(410 vod_expired)은 「만료」이고 그 방송은 더 묻지 않는다(POK-335)', async () => {
    const { api, posts } = await load(() => jsonResponse(410, { error: 'vod_expired' }));
    expect(await api.ensureLiveAccess('S-old', 'k-old')).toEqual({ kind: 'expired' });
    expect(await api.ensureLiveAccess('S-old', 'k-old')).toEqual({ kind: 'expired' });
    expect(api.ensurePlaybackAccess('S-old', 'k-old')).toBeNull();
    expect(posts()).toHaveLength(1);
  });
});
