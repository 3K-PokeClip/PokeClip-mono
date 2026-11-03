import Hls, {
  ChunkMetadata,
  Events,
  LoadStats,
  M3U8Parser,
  PlaylistLevelType,
  type MediaFragment,
  type HlsConfig,
  type LevelDetails,
  type LoaderCallbacks,
  type LoaderContext,
  type Part,
} from 'hls.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HLS_REWIND_CONFIG } from './hlsConfig';
import { trackVideoStarts, type VideoStarts } from './videoStarts';

// 조각마다 영상 첫 표본의 재생 위치(v_k)와 구성 시차(c_k)를 진짜 hls.js 1.7.3(dist) 사건으로 적는다(설계 r7 3.3 ·
// 4.1 · 10절 c3). 사건은 진짜 Hls 인스턴스에 trigger 로 흘려 넣고, 조각 · 파트는 진짜 M3U8Parser 가 만든 객체를
// 쓴다. 사건 칸의 꼴은 stream-controller 가 내는 그대로다: init 붙임은 frag 가 MAP 조각(sn 'initSegment' ·
// cc 는 MAP 줄의 cc)이고 part 는 null(_bufferInitSegment), 조각 붙임은 frag 가 미디어 조각 · LL 파트면 part 가
// 그 Part(bufferFragmentData), INIT_PTS_FOUND 는 id 가 'main' · frag 가 미디어 조각(_handleTransmuxComplete).
// hls.js 판을 올려 칸 이름이 바뀌면 여기서 먼저 깨진다.

const URL = 'https://m/dvr/k/a/index.m3u8';
const VIDEO_TRACK = 1;
const AUDIO_TRACK = 2;
const VIDEO_TS = 90000;
const AUDIO_TS = 48000;
/** 재포장 도장 기점(3600초) — 소리 트랙 눈금으로 적은 initPTS */
const ORIGIN_S = 3600;

const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

// --- 합성 바이트(fmp4Boxes.test.ts 와 같은 틀) ---

function concat(...parts: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function u32(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value);
  return out;
}

function ascii(text: string): Uint8Array {
  return Uint8Array.from(text, (ch) => ch.charCodeAt(0));
}

function box(type: string, ...payload: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const body = concat(...payload);
  return concat(u32(body.length + 8), ascii(type), body);
}

function fullBox(type: string, flags: number, ...payload: Uint8Array[]): Uint8Array {
  return box(type, u32(flags), ...payload);
}

function trak(trackId: number, handler: string, timescale: number): Uint8Array {
  return box(
    'trak',
    fullBox('tkhd', 3, u32(0), u32(0), u32(trackId), u32(0)),
    box(
      'mdia',
      fullBox('mdhd', 0, u32(0), u32(0), u32(timescale), u32(0)),
      fullBox('hdlr', 0, u32(0), ascii(handler), u32(0), u32(0), u32(0)),
    ),
  );
}

/** 영상 · 소리 두 트랙 init(muxed — hls.js 는 type 'audiovideo' 로 붙인다) */
const AV_INIT = concat(
  box('ftyp', ascii('isom'), u32(0)),
  box('moov', trak(VIDEO_TRACK, 'vide', VIDEO_TS), trak(AUDIO_TRACK, 'soun', AUDIO_TS)),
);
/** 소리 트랙만 있는 init */
const AUDIO_ONLY_INIT = concat(
  box('ftyp', ascii('isom'), u32(0)),
  box('moov', trak(AUDIO_TRACK, 'soun', AUDIO_TS)),
);

function traf(trackId: number, decodeTicks: number, ctoTicks: number): Uint8Array {
  return box(
    'traf',
    fullBox('tfhd', 0x020000, u32(trackId)),
    fullBox('tfdt', 0, u32(decodeTicks)),
    // trun 0x800 — 첫 표본 cto 칸 하나
    fullBox('trun', 0x800, u32(1), u32(ctoTicks)),
  );
}

/** 영상 · 소리 traf 를 다 담은 조각(moof + 빈 mdat). 소리는 영상보다 20ms 앞선다 */
function avFragment(videoStartS: number, ctoTicks: number): Uint8Array<ArrayBuffer> {
  return concat(
    box(
      'moof',
      fullBox('mfhd', 0, u32(1)),
      traf(VIDEO_TRACK, Math.round(videoStartS * VIDEO_TS), ctoTicks),
      traf(AUDIO_TRACK, Math.round((videoStartS - 0.02) * AUDIO_TS), 0),
    ),
    box('mdat'),
  );
}

// --- 진짜 파서가 만든 조각 · 파트 ---

/**
 * 순번 100(cc 0) · DISCONTINUITY 뒤 101(cc 1) · 102(cc 1, LL 파트 둘). MAP 은 맨 앞 하나뿐이라 101 · 102 도 cc 0 의
 * MAP 조각을 init 으로 쓴다
 */
const PLAYLIST = [
  '#EXTM3U',
  '#EXT-X-VERSION:9',
  '#EXT-X-TARGETDURATION:4',
  '#EXT-X-PART-INF:PART-TARGET=2.0',
  '#EXT-X-MEDIA-SEQUENCE:100',
  '#EXT-X-MAP:URI="init.mp4"',
  '#EXT-X-PROGRAM-DATE-TIME:2026-11-03T05:00:00.000Z',
  '#EXTINF:4.000,',
  'seg100.mp4',
  '#EXT-X-DISCONTINUITY',
  '#EXT-X-PROGRAM-DATE-TIME:2026-11-03T05:00:04.000Z',
  '#EXTINF:4.000,',
  'seg101.mp4',
  '#EXT-X-PART:DURATION=2.0,URI="seg102.0.mp4",INDEPENDENT=YES',
  '#EXT-X-PART:DURATION=2.0,URI="seg102.1.mp4"',
  '#EXT-X-PROGRAM-DATE-TIME:2026-11-03T05:00:08.000Z',
  '#EXTINF:4.000,',
  'seg102.mp4',
].join('\n');

function parse(text: string): LevelDetails {
  return M3U8Parser.parseLevelPlaylist(text, URL, 0, PlaylistLevelType.MAIN, 0, null);
}

const players: Hls[] = [];
afterEach(() => {
  for (const hls of players.splice(0)) hls.destroy();
});

interface Rig {
  hls: Hls;
  starts: VideoStarts;
  details: LevelDetails;
  /** 순번 → 진짜 조각 */
  frag: (sn: number) => MediaFragment;
}

function rig(): Rig {
  const hls = new Hls({ debug: SILENT });
  players.push(hls);
  const details = parse(PLAYLIST);
  const frag = (sn: number) => {
    const found = details.fragments.find((candidate) => candidate.sn === sn);
    if (!found) throw new Error(`조각 ${sn} 없음`);
    return found;
  };
  return { hls, starts: trackVideoStarts(hls, Events), details, frag };
}

interface AppendOptions {
  part?: Part | null;
  parent?: PlaylistLevelType;
}

/** stream-controller._bufferInitSegment 꼴 — frag 는 MAP 조각(frag.initSegment ?? frag) */
function appendInit(
  hls: Hls,
  media: MediaFragment,
  data: Uint8Array<ArrayBuffer>,
  parent = PlaylistLevelType.MAIN,
): void {
  const mapFragment = media.initSegment ?? media;
  hls.trigger(Events.BUFFER_APPENDING, {
    type: parent === PlaylistLevelType.MAIN ? 'audiovideo' : 'audio',
    data,
    frag: mapFragment,
    part: null,
    chunkMeta: new ChunkMetadata(0, Number(media.sn), 0),
    parent,
  });
}

/** base-stream-controller.bufferFragmentData 꼴 — frag 는 미디어 조각 · LL 파트면 part */
function appendMedia(
  hls: Hls,
  media: MediaFragment,
  data: Uint8Array<ArrayBuffer>,
  { part = null, parent = PlaylistLevelType.MAIN }: AppendOptions = {},
): void {
  hls.trigger(Events.BUFFER_APPENDING, {
    type: parent === PlaylistLevelType.MAIN ? 'audiovideo' : 'audio',
    data,
    frag: media,
    part,
    chunkMeta: new ChunkMetadata(0, Number(media.sn), 0, data.length, part?.index ?? -1),
    parent,
  });
}

/** stream-controller._handleTransmuxComplete 꼴 — 소리가 먼저라 소리 트랙으로 잡힌 initPTS */
function foundInitPts(
  hls: Hls,
  media: MediaFragment,
  originS = ORIGIN_S,
  id = PlaylistLevelType.MAIN,
): void {
  hls.trigger(Events.INIT_PTS_FOUND, {
    timestampOffsets: [],
    id,
    frag: media,
    initPTS: Math.round(originS * AUDIO_TS),
    timescale: AUDIO_TS,
    trackId: AUDIO_TRACK,
  });
}

describe('trackVideoStarts — 진짜 hls.js 사건으로 조각마다 영상 시작을 적는다', () => {
  it('소리 기준 initPTS 로 v_k 를 셈하고, 구성 시차는 v_k 에 더하지 않고 c_k 로 따로 준다', () => {
    const { hls, starts, frag } = rig();
    const seg = frag(100);

    appendInit(hls, seg, AV_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 0.04, 6000));
    foundInitPts(hls, seg);

    const start = starts.startOf(seg);
    expect(start?.videoStartS).toBeCloseTo(0.04, 6);
    expect(start?.firstCtoS).toBeCloseTo(6000 / VIDEO_TS, 6);
  });

  it('그 cc 의 initPTS 를 모르면 null 이다 — MAP 을 다시 받지 않은 DISCONTINUITY 뒤 조각도 알게 되면 값이 난다', () => {
    const { hls, starts, frag } = rig();
    const first = frag(100);
    const afterCut = frag(101);
    // 101 은 cc 1 이지만 init 붙임의 frag 는 cc 0 의 MAP 조각이다(hls.js 는 DISCONTINUITY 에서 init 을 다시 붙인다)
    expect(afterCut.cc).toBe(1);
    expect(afterCut.initSegment?.cc).toBe(0);

    appendInit(hls, first, AV_INIT);
    appendMedia(hls, first, avFragment(ORIGIN_S, 0));
    foundInitPts(hls, first);
    appendInit(hls, afterCut, AV_INIT);
    appendMedia(hls, afterCut, avFragment(ORIGIN_S + 100.5, 3000));

    expect(starts.startOf(first)?.videoStartS).toBeCloseTo(0, 6);
    expect(starts.startOf(afterCut)).toBeNull();

    foundInitPts(hls, afterCut, ORIGIN_S + 100);
    expect(starts.startOf(afterCut)?.videoStartS).toBeCloseTo(0.5, 6);
    expect(starts.startOf(afterCut)?.firstCtoS).toBeCloseTo(3000 / VIDEO_TS, 6);
  });

  it('영상 트랙이 없는 init 뒤 조각은 null 이다 — 앞 init 에 영상이 있었어도 새 init 을 따른다', () => {
    const { hls, starts, frag } = rig();
    const seg = frag(100);
    foundInitPts(hls, seg);

    appendInit(hls, seg, AUDIO_ONLY_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0));
    expect(starts.startOf(seg)).toBeNull();

    appendInit(hls, seg, AV_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0));
    expect(starts.startOf(seg)?.videoStartS).toBeCloseTo(1, 6);

    appendInit(hls, seg, AUDIO_ONLY_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 2, 0));
    expect(starts.startOf(seg)).toBeNull();
  });

  it('잘린 init · 잘린 moof 는 null 이다 — 같은 조각을 다시 붙여 읽지 못하면 앞 기록도 버린다', () => {
    const { hls, starts, frag } = rig();
    const seg = frag(100);
    foundInitPts(hls, seg);

    appendInit(hls, seg, AV_INIT.slice(0, AV_INIT.length - 6));
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0));
    expect(starts.startOf(seg)).toBeNull();

    appendInit(hls, seg, AV_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0));
    expect(starts.startOf(seg)?.videoStartS).toBeCloseTo(1, 6);

    const whole = avFragment(ORIGIN_S + 2, 0);
    // 첫 traf 의 tfdt 가운데에서 자른다(moof 머리 8 · mfhd 16 · traf 머리 8 · tfhd 16 · tfdt 머리 8 뒤)
    appendMedia(hls, seg, whole.slice(0, 8 + 16 + 8 + 16 + 10));
    expect(starts.startOf(seg)).toBeNull();
  });

  it('parent 가 audio 인 붙임과 id 가 audio 인 INIT_PTS_FOUND 는 무시한다', () => {
    const { hls, starts, frag } = rig();
    const seg = frag(100);

    foundInitPts(hls, seg, ORIGIN_S + 50, PlaylistLevelType.AUDIO);
    appendInit(hls, seg, AV_INIT, PlaylistLevelType.AUDIO);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0), { parent: PlaylistLevelType.AUDIO });
    expect(starts.startOf(seg)).toBeNull();

    foundInitPts(hls, seg);
    appendInit(hls, seg, AV_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0));
    appendMedia(hls, seg, avFragment(ORIGIN_S + 9, 0), { parent: PlaylistLevelType.AUDIO });
    foundInitPts(hls, seg, ORIGIN_S + 50, PlaylistLevelType.AUDIO);
    expect(starts.startOf(seg)?.videoStartS).toBeCloseTo(1, 6);
  });

  it('LL 파트는 첫 파트(index 0)의 moof 만 그 조각의 시작으로 적는다', () => {
    const { hls, starts, details, frag } = rig();
    const seg = frag(102);
    const parts = (details.partList ?? []).filter((part) => part.fragment === seg);
    // 진짜 파서는 파트 번호를 조각마다 0 부터 센다
    expect(parts.map((part) => part.index)).toEqual([0, 1]);
    const [head, tail] = parts as [Part, Part];
    foundInitPts(hls, seg, ORIGIN_S + 8);
    appendInit(hls, seg, AV_INIT);

    appendMedia(hls, seg, avFragment(ORIGIN_S + 10, 0), { part: tail });
    expect(starts.startOf(seg)).toBeNull();

    appendMedia(hls, seg, avFragment(ORIGIN_S + 8.1, 0), { part: head });
    appendMedia(hls, seg, avFragment(ORIGIN_S + 10, 0), { part: tail });
    expect(starts.startOf(seg)?.videoStartS).toBeCloseTo(0.1, 6);
  });

  it('dispose 는 단 처리기 셋을 떼고 기록을 비운다 — 뒤 사건은 적지 않는다', () => {
    const { hls, starts, frag } = rig();
    const seg = frag(100);
    const watched = [Events.BUFFER_APPENDING, Events.INIT_PTS_FOUND, Events.LEVEL_UPDATED] as const;
    const counts = () => watched.map((event) => hls.listenerCount(event));
    const attached = counts();
    foundInitPts(hls, seg);
    appendInit(hls, seg, AV_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0));
    expect(starts.startOf(seg)).not.toBeNull();

    starts.dispose();
    expect(counts()).toEqual(attached.map((count) => count - 1));
    expect(starts.startOf(seg)).toBeNull();
    foundInitPts(hls, seg);
    appendInit(hls, seg, AV_INIT);
    appendMedia(hls, seg, avFragment(ORIGIN_S + 1, 0));
    expect(starts.startOf(seg)).toBeNull();
  });
});

// 목록에서 빠진 순번 정리 — 진짜 Hls 의 목록 갱신 경로(playlist-loader → level-controller → LEVEL_UPDATED).
// 매체는 붙이지 않는다(조각은 청하지 않는다). fragmentTiming.hlsjs.test.ts 의 미닫이 갱신과 같은 틀이다.
// 흉내 사건으로는 돌릴 수 없다 — interstitials-controller 의 LEVEL_UPDATED 처리기가 hls.levels 를 읽는다.
describe('trackVideoStarts — 진짜 Hls 의 LEVEL_UPDATED 로 목록에서 빠진 기록을 버린다', () => {
  /** 가짜 loader 의 load 인자 — 설정(가운데)은 쓰지 않는다 */
  type LoadArgs = [LoaderContext, unknown, LoaderCallbacks<LoaderContext>];
  const TD = 4;
  const PDT0 = Date.parse('2026-11-03T05:00:00.000Z');

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  /** 순번 from 부터 세 조각인 라이브 목록(ENDLIST 없음 · LL 파트 없음) */
  function livePlaylist(from: number): string {
    const lines = ['#EXTM3U', '#EXT-X-VERSION:9', `#EXT-X-TARGETDURATION:${TD}`];
    lines.push(`#EXT-X-MEDIA-SEQUENCE:${from}`, '#EXT-X-MAP:URI="init.mp4"');
    for (let sn = from; sn < from + 3; sn++) {
      const pdt = new Date(PDT0 + (sn - 100) * TD * 1000).toISOString();
      lines.push(`#EXT-X-PROGRAM-DATE-TIME:${pdt}`, `#EXTINF:${TD}.000,`, `seg${sn}.mp4`);
    }
    return lines.join('\n');
  }

  /** 받을 때마다 lists 의 다음 글자를 주는(끝이면 마지막 것을 되풀이) 가짜 loader 를 단 재생기 */
  function livePlayer(lists: readonly string[]): Hls {
    let served = 0;
    class FakeLoader {
      stats = new LoadStats();
      load(...[context, , callbacks]: LoadArgs) {
        if (context.url !== URL) {
          return;
        }
        const data = lists[Math.min(served++, lists.length - 1)]!;
        queueMicrotask(() => {
          // 받은 시각을 찍는다 — 0 이면 다음 받기 예약이 늘 과거라 hls.js 가 쉬지 않고 다시 받는다
          const now = performance.now();
          this.stats.loading = { start: now, first: now, end: now };
          callbacks.onSuccess({ url: URL, data }, this.stats, context, null);
        });
      }
      abort() {}
      destroy() {}
    }
    const hls = new Hls({
      ...HLS_REWIND_CONFIG,
      autoStartLoad: false,
      loader: FakeLoader as unknown as HlsConfig['loader'],
      debug: SILENT,
    });
    players.push(hls);
    hls.on(Events.MANIFEST_PARSED, () => hls.startLoad(0));
    return hls;
  }

  it('미닫이 갱신에서 startSN 앞 순번의 기록만 버린다', async () => {
    const hls = livePlayer([livePlaylist(100), livePlaylist(101)]);
    const starts = trackVideoStarts(hls, Events);
    const updates: LevelDetails[] = [];
    hls.on(Events.LEVEL_UPDATED, (...[, data]) => {
      updates.push(data.details);
    });
    hls.loadSource(URL);
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);
    expect(updates).toHaveLength(1);
    const [gone, kept] = updates[0]!.fragments as [MediaFragment, MediaFragment];
    foundInitPts(hls, gone);
    appendInit(hls, gone, AV_INIT);
    appendMedia(hls, gone, avFragment(ORIGIN_S, 0));
    appendMedia(hls, kept, avFragment(ORIGIN_S + TD, 0));
    expect(starts.startOf(gone)).not.toBeNull();

    await vi.advanceTimersByTimeAsync(TD * 1000);

    expect(updates.length).toBeGreaterThanOrEqual(2);
    expect(updates.at(-1)!.startSN).toBe(101);
    expect(starts.startOf(gone)).toBeNull();
    expect(starts.startOf(kept)?.videoStartS).toBeCloseTo(TD, 6);
  });
});
