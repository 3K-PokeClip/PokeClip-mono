import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { parseSpanStartMs } from './mediaPlayback';

describe('parseSpanStartMs — 녹화 시작을 ms로 올린다(POK-253)', () => {
  it('ms 아래가 있으면 올린다 — 내리면 두 번째 구간 요청이 틈 안으로 들어가 404다(실측)', () => {
    expect(parseSpanStartMs('2026-09-30T01:58:16.372191Z')).toBe(
      Date.parse('2026-09-30T01:58:16.373Z'),
    );
  });

  it('ms 아래가 0이거나 없으면 그대로다', () => {
    expect(parseSpanStartMs('2026-09-30T01:58:16.372000Z')).toBe(
      Date.parse('2026-09-30T01:58:16.372Z'),
    );
    expect(parseSpanStartMs('2026-09-30T01:58:16.372Z')).toBe(
      Date.parse('2026-09-30T01:58:16.372Z'),
    );
    expect(parseSpanStartMs('2026-09-30T01:58:16Z')).toBe(Date.parse('2026-09-30T01:58:16Z'));
  });
});

describe('lookupRecordingSpans: 녹화 없음과 물을 수 없음을 가른다(리뷰 6라운드)', () => {
  // 재생 서버가 없거나 조회가 실패해도 빈 목록으로 접어 화면이 「녹화 영상을 찾지 못했어요」로 굳었다
  let fetchSpy: ReturnType<typeof stubFetch>;
  async function load(base: string, answer: () => Promise<Response>) {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', base);
    fetchSpy = stubFetch(answer);
    return import('./mediaPlayback');
  }

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('재생 서버 주소가 없으면 묻지 않고 null이다: 녹화가 없다는 뜻이 아니다', async () => {
    // 물으면 빈 목록이 오게 둔다: 실패로 두면 조회 함수가 그 실패를 null로 삼켜, 묻지 않는지를 재지 못했다(리뷰 7라운드)
    const m = await load('', () => Promise.resolve(jsonResponse(200, [])));
    expect(await m.lookupRecordingSpans('vod-1')).toBeNull();
    expect(await m.fetchRecordingSpans('vod-1')).toEqual([]);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('녹화 폴더가 없다는 400과 404는 녹화 없음(빈 배열)이다', async () => {
    const bad = await load('http://pb', () => Promise.resolve(new Response('{}', { status: 400 })));
    expect(await bad.lookupRecordingSpans('vod-1')).toEqual([]);
    const missing = await load('http://pb', () =>
      Promise.resolve(new Response('{}', { status: 404 })),
    );
    expect(await missing.lookupRecordingSpans('vod-1')).toEqual([]);
  });

  it('서버 오류와 네트워크 오류는 null이다: 목록·편집기 쪽은 전처럼 빈 배열이다', async () => {
    const down = await load('http://pb', () => Promise.resolve(new Response('', { status: 503 })));
    expect(await down.lookupRecordingSpans('vod-1')).toBeNull();
    const offline = await load('http://pb', () => Promise.reject(new TypeError('network')));
    expect(await offline.lookupRecordingSpans('vod-1')).toBeNull();
    expect(await offline.fetchRecordingSpans('vod-1')).toEqual([]);
  });

  it('녹화가 있으면 구간을 준다', async () => {
    const m = await load('http://pb', () =>
      Promise.resolve(
        new Response(JSON.stringify([{ start: '2026-10-08T05:41:16.285Z', duration: 10 }]), {
          status: 200,
        }),
      ),
    );
    expect(await m.lookupRecordingSpans('vod-1')).toEqual([
      { startMs: Date.parse('2026-10-08T05:41:16.285Z'), durationSeconds: 10 },
    ]);
  });
});
