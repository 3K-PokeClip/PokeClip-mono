import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  EDITOR_SOURCE_SCHEMA,
  editorSourceUrl,
  initialRangeForSource,
  parseAudioPeaks,
  parseEditorSource,
} from './editorSource';
import { MAX_RANGE_SECONDS, MIN_RANGE_SECONDS } from './timelineMath';

const BASE_URL = 'http://localhost:8080/live/editor-sample/source.json';

// gen-editor-source.sh 가 실제로 내는 모양 (값만 줄였다)
function validSource() {
  return {
    schema: EDITOR_SOURCE_SCHEMA,
    streamId: 'editor-sample',
    label: '로컬 샘플',
    generatedAtMs: 1788465614073,
    sourceStartAtMs: 1788176806750,
    sourceStartAtIso: '2026-08-31T11:46:46.750Z',
    durationSeconds: 600,
    width: 1920,
    height: 1080,
    fps: 60,
    playlist: 'index.m3u8',
    videoPlaylist: 'video.m3u8',
    filmstrip: {
      sheets: ['thumbs_001.jpg', 'thumbs_002.jpg'],
      columns: 10,
      rows: 10,
      tileWidth: 160,
      tileHeight: 90,
      intervalSeconds: 2,
      count: 150,
      lastSheetCount: 50,
      mimeType: 'image/jpeg',
    },
    audioTracks: [
      {
        trackId: 0,
        kind: 'mix',
        label: '오디오 · 최종 믹스',
        playlist: 'audio0.m3u8',
        channels: 2,
        sampleRate: 48000,
        peaks: { file: 'peaks_0.json', binMs: 100, count: 6000, scale: 'abs16', maxPeak: 0.771 },
      },
    ],
  };
}

describe('parseEditorSource', () => {
  it('사이드카를 읽고 상대 경로를 절대 URL로 푼다', () => {
    const source = parseEditorSource(validSource(), BASE_URL);

    expect(source.streamId).toBe('editor-sample');
    expect(source.durationSeconds).toBe(600);
    expect(source.playlistUrl).toBe('http://localhost:8080/live/editor-sample/index.m3u8');
    expect(source.filmstrip.sheetUrls[0]).toBe(
      'http://localhost:8080/live/editor-sample/thumbs_001.jpg',
    );
    expect(source.audioTracks[0]?.peaksUrl).toBe(
      'http://localhost:8080/live/editor-sample/peaks_0.json',
    );
  });

  it('모르는 판은 거부한다 — 필드가 조용히 비는 것보다 낫다', () => {
    expect(() => parseEditorSource({ ...validSource(), schema: 'something/2' }, BASE_URL)).toThrow(
      /모르는 판/,
    );
  });

  it('객체가 아니면 거부한다', () => {
    expect(() => parseEditorSource(null, BASE_URL)).toThrow(/객체가 아닙니다/);
    expect(() => parseEditorSource([], BASE_URL)).toThrow(/객체가 아닙니다/);
  });

  it('필드가 빠지면 어디가 빠졌는지 말한다', () => {
    const broken = validSource() as Record<string, unknown>;
    delete broken.durationSeconds;
    expect(() => parseEditorSource(broken, BASE_URL)).toThrow(/durationSeconds/);
  });

  it('유한하지 않은 수를 거부한다 — 좌표 계산이 통째로 무너진다', () => {
    const broken = validSource();
    broken.width = Number.NaN;
    expect(() => parseEditorSource(broken, BASE_URL)).toThrow(/width/);
  });

  it('길이가 0 이하면 거부한다', () => {
    const broken = validSource();
    broken.durationSeconds = 0;
    expect(() => parseEditorSource(broken, BASE_URL)).toThrow(/durationSeconds/);
  });

  it('시트나 오디오 트랙이 비면 거부한다', () => {
    const noSheets = validSource();
    noSheets.filmstrip.sheets = [];
    expect(() => parseEditorSource(noSheets, BASE_URL)).toThrow(/sheets/);

    const noAudio = validSource();
    noAudio.audioTracks = [];
    expect(() => parseEditorSource(noAudio, BASE_URL)).toThrow(/audioTracks/);
  });

  it('하위 폴더에 있는 사이드카도 자기 자리를 기준으로 푼다', () => {
    const source = parseEditorSource(validSource(), 'https://cdn.example.com/a/b/c/source.json');
    expect(source.playlistUrl).toBe('https://cdn.example.com/a/b/c/index.m3u8');
  });
});

describe('parseAudioPeaks', () => {
  const valid = {
    binMs: 100,
    sampleRate: 8000,
    count: 3,
    scale: 'abs16',
    maxPeak: 0.8,
    peaks: [0.1, 0.5, 0.8],
  };

  it('파형을 읽는다', () => {
    expect(parseAudioPeaks(valid).peaks).toEqual([0.1, 0.5, 0.8]);
  });

  it('개수가 배열 길이와 다르면 거부한다 — 잘린 파일을 조용히 그리지 않는다', () => {
    expect(() => parseAudioPeaks({ ...valid, count: 5 })).toThrow(/개수가 안 맞습니다/);
  });

  it('빈 파형을 거부한다', () => {
    expect(() => parseAudioPeaks({ ...valid, peaks: [], count: 0 })).toThrow(/비어 있습니다/);
  });
});

describe('initialRangeForSource', () => {
  it('앞머리를 조금 지나 시안 길이(12.4초)로 잡는다', () => {
    const range = initialRangeForSource(600);
    expect(range.startSeconds).toBe(60);
    expect(range.endSeconds - range.startSeconds).toBeCloseTo(12.4, 5);
  });

  it('짧은 소스는 소스 안에 들어오게 당긴다', () => {
    const range = initialRangeForSource(30);
    // 30초의 10% = 3초
    expect(range.startSeconds).toBe(3);
    expect(range.endSeconds).toBeLessThanOrEqual(30);
  });

  it('구간 하한(5초)보다 짧은 소스에서도 하한을 지킨다', () => {
    const range = initialRangeForSource(4);
    expect(range.endSeconds - range.startSeconds).toBe(MIN_RANGE_SECONDS);
    expect(range.startSeconds).toBe(0);
  });

  it('상한(3분)을 넘지 않는다', () => {
    const range = initialRangeForSource(7200);
    expect(range.endSeconds - range.startSeconds).toBeLessThanOrEqual(MAX_RANGE_SECONDS);
  });
});

describe('editorSourceUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('env가 비어 있으면 null이다 — 목업으로 간다', () => {
    vi.stubEnv('NEXT_PUBLIC_EDITOR_SOURCE_URL', '');
    expect(editorSourceUrl()).toBeNull();
  });

  it('env가 있으면 그 값을 준다', () => {
    vi.stubEnv('NEXT_PUBLIC_EDITOR_SOURCE_URL', BASE_URL);
    expect(editorSourceUrl()).toBe(BASE_URL);
  });
});
