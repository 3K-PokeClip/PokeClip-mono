import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubFetch } from '@/test/mockFetch';
import type { PlaylistSegment } from './editorPlaylist';

// 확보 조각 ③ 의 영상 첫 DTS(D3) 판독 · 기다림 고리 상한의 머리 처분(설계 prw2 7절 c10b′). ③ 은 CloudFront 서명 쿠키라
// 자격이 mediaCredentials() 이고, ② 머리 판독(omit)과 같은 Range 판독 함수를 자격만 달리해 쓴다. 쿠키 실음은 진짜 모듈이다 —
// 모듈 상태라 갈래마다 모듈을 새로 읽는다.

const TESTDATA = join(
  __dirname,
  '..',
  '..',
  '..',
  '..',
  '..',
  '..',
  'media',
  'internal',
  'playback',
  'testdata',
);
/** 포크 녹화 실물 — 영상 트랙 1(90000) · moov 와 moof 가 한 파일이라 init 과 조각 양쪽으로 쓴다(영상 tfdt 8136) */
const segment1v6a = new Uint8Array(readFileSync(join(TESTDATA, 'segment_1v6a.mp4')));

const DIR = 'https://media.example/dvr/key-1/r-1';
const ROW: Pick<PlaylistSegment, 'uri' | 'mapUri'> = {
  uri: `${DIR}/seg/0000123.m4s`,
  mapUri: `${DIR}/init/i-1.mp4`,
};

function serve(bytes: Uint8Array) {
  return stubFetch((_url, init) => {
    const range = new Headers(init?.headers).get('Range');
    if (range === null) return new Response(bytes.slice(), { status: 200 });
    const [, from, to] = /^bytes=(\d+)-(\d+)$/.exec(range)!;
    return new Response(bytes.slice(Number(from), Number(to) + 1), { status: 206 });
  });
}

async function load() {
  vi.resetModules();
  const playback = await import('@/api/mediaPlayback');
  const probe = await import('./fileHeadProbe');
  return { playback, ...probe };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('readFragmentVideoDts: 확보 조각 ③ 의 영상 첫 DTS(초)', () => {
  it('init(MAP)의 영상 트랙 timescale 로 조각 머리 tfdt 를 초로 바꾼다 — 둘 다 Range 64KB 하나로 읽는다', async () => {
    const { readFragmentVideoDts } = await load();
    const spy = serve(segment1v6a);

    await expect(readFragmentVideoDts(ROW)).resolves.toBe(8136 / 90000);

    expect(spy.mock.calls.map(([url]) => url).sort()).toEqual([ROW.mapUri, ROW.uri].sort());
    expect(spy.mock.calls.map(([, init]) => new Headers(init?.headers).get('Range'))).toEqual([
      'bytes=0-65535',
      'bytes=0-65535',
    ]);
  });

  it('출입증 쿠키를 받기 전에는 same-origin 으로 묻는다(③ 은 mediaCredentials — ② 의 omit 이 아니다)', async () => {
    const { readFragmentVideoDts } = await load();
    const spy = serve(segment1v6a);

    await readFragmentVideoDts(ROW);

    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual([
      'same-origin',
      'same-origin',
    ]);
  });

  it('출입증 쿠키를 받은 뒤에는 include 로 쿠키를 싣는다', async () => {
    const { playback, readFragmentVideoDts } = await load();
    playback.markPlaybackCookiesIssued();
    const spy = serve(segment1v6a);

    await readFragmentVideoDts(ROW);

    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual(['include', 'include']);
  });

  it('같은 판독 함수를 쓰는 ② 머리는 쿠키를 받은 뒤에도 omit 이다: 자격은 부르는 쪽이 고른다', async () => {
    const { playback, ensureHeads } = await load();
    playback.markPlaybackCookiesIssued();
    const spy = serve(segment1v6a);

    await ensureHeads('key-1', [{ seq: 300, url: 'https://seg.example/300.mp4' }], []);

    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual(['omit']);
  });

  it('init 에 영상 트랙이 없거나 조각에 영상 tfdt 가 없으면 던진다', async () => {
    const { readFragmentVideoDts } = await load();
    serve(new Uint8Array([0, 0, 0, 8, 0x66, 0x72, 0x65, 0x65]));

    await expect(readFragmentVideoDts(ROW)).rejects.toThrow();
  });

  it('쿠키를 싣는 ③ 받기는 3xx 를 따라가지 않고(redirect error) 던진다', async () => {
    const { readFragmentVideoDts } = await load();
    // 브라우저 fetch 는 redirect 'error' 면 3xx 에서 TypeError 로 거절한다 — 아니면 따라가 넘어간 곳의 본문을 준다
    const spy = stubFetch((_url, init) => {
      if (init?.redirect === 'error') throw new TypeError('Failed to fetch');
      return new Response(segment1v6a.slice(), { status: 200 });
    });

    await expect(readFragmentVideoDts(ROW)).rejects.toThrow();
    expect(spy.mock.calls.every(([, init]) => init?.redirect === 'error')).toBe(true);
  });

  it('판독 실패 사유는 ③ 판독을 ② 머리 판독으로 적지 않는다', async () => {
    const { readFragmentVideoDts } = await load();
    stubFetch(() => new Response(null, { status: 403 }));

    const error = await readFragmentVideoDts(ROW).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).not.toContain('②');
  });

  it('응답이 403 이면 던진다(그 확보는 포기 — 부르는 쪽이 정한다)', async () => {
    const { readFragmentVideoDts } = await load();
    stubFetch(() => new Response(null, { status: 403 }));

    await expect(readFragmentVideoDts(ROW)).rejects.toThrow();
  });
});

describe('failMissingHeads: 기다림 고리 상한에서 끝내 없는 머리를 failed 로 바꾼다', () => {
  it('missing 만 failed 로 바꾸고 ready · 아직 묻지 않은 seq 는 그대로 둔다', async () => {
    const { ensureHeads, failMissingHeads, headCacheFor } = await load();
    serve(segment1v6a);
    await ensureHeads('key-1', [{ seq: 1, url: 'https://seg.example/1.mp4' }], [2]);

    failMissingHeads('key-1', [1, 2, 3]);

    const heads = headCacheFor('key-1');
    expect(heads.get(1)?.state).toBe('ready');
    expect(heads.get(2)).toEqual({ state: 'failed' });
    expect(heads.get(3)).toBeUndefined();
  });

  it('바꾸면 구독자를 부른다: 창 배치 · 저장 판정이 다시 읽는다', async () => {
    const { ensureHeads, failMissingHeads, headCacheFor } = await load();
    await ensureHeads('key-1', [], [2]);
    const listener = vi.fn();
    headCacheFor('key-1').subscribe(listener);

    failMissingHeads('key-1', [2]);

    expect(listener).toHaveBeenCalled();
  });
});
