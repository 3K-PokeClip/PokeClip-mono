import { describe, expect, it } from 'vitest';
import type {
  BroadcastRow,
  BroadcastTimeline,
  ClipSnapshot,
  LibraryEntry,
  LibraryStatus,
  UploadSnapshot,
} from '@/api/clipEditor';
import { instantLabel } from '@/features/clips/editor/uploadInfo';
import {
  broadcastLengthLabel,
  clipSourceOf,
  instantText,
  noteText,
  sceneAtMs,
  sceneLabel,
  sourceLinkOf,
  stepViewForClip,
  uploadFailureText,
} from './libraryView';
import { MOCK_CLIPS } from './libraryFixture';
import { toLibraryClip } from './useLibraryMockState';

// 상세 패널의 진행 카드(POK-510)와 원본 방송 카드(POK-511) 판정

const NOW = new Date('2026-10-30T14:30:00+09:00');
const STARTED = '2026-10-17T19:52:00+09:00';
const ENDED = '2026-10-17T22:06:00+09:00';
/** 편집 구간 1:23:45 ~ 1:24:30 */
const CUT = { inAtMs: 5_025_000, outAtMs: 5_070_000 };

function video(over: Partial<ClipSnapshot> = {}): ClipSnapshot {
  return {
    id: 5,
    streamId: 's1',
    recipeId: 12,
    recipeVersion: 2,
    requestedBy: '9',
    status: 'rendered',
    progress: null,
    outputs: [{ outputId: 'o1', kind: 'video', s3Key: 'k' }],
    error: null,
    createdAt: '2026-10-30T14:21:00+09:00',
    updatedAt: '2026-10-30T14:23:00+09:00',
    upload: null,
    ...over,
  };
}

function upload(over: Partial<UploadSnapshot>): UploadSnapshot {
  return {
    id: 7,
    clipId: 5,
    outputId: 'o1',
    title: '보스 막타',
    status: 'uploading',
    videoId: null,
    error: null,
    requestedBy: '9',
    createdAt: '2026-10-30T14:23:00+09:00',
    updatedAt: '2026-10-30T14:23:00+09:00',
    ...over,
  };
}

function entry(
  status: LibraryStatus,
  latestClip: ClipSnapshot | null = video(),
  over: Partial<LibraryEntry> = {},
): LibraryEntry {
  return {
    recipeId: 12,
    streamId: 's1',
    creatorId: '9',
    recipeVersion: 2,
    cut: CUT,
    status,
    broadcast: {
      status: 'ended',
      startedAt: STARTED,
      endedAt: ENDED,
      vodExpiresAt: '2026-12-16T22:06:00+09:00',
    },
    latestClip,
    createdAt: '2026-10-30T14:05:00+09:00',
    updatedAt: '2026-10-30T14:20:00+09:00',
    ...over,
  };
}

function row(over: Partial<BroadcastRow> = {}): BroadcastRow {
  return {
    streamId: 's1',
    status: 'ended',
    relation: 'OWNER',
    startedAt: STARTED,
    endedAt: ENDED,
    vodExpiresAt: '2026-12-16T22:06:00+09:00',
    title: '방송 끝 제목',
    category: '방송 끝 카테고리',
    platform: 'CHZZK',
    ...over,
  };
}

type Segment = BroadcastTimeline['segments'][number];

function segment(
  platform: string,
  from: string,
  until: string | null,
  title: string | null,
  category: string | null,
  categoryImageUrl: string | null = null,
): Segment {
  return { platform, from, until, title, category, categoryImageUrl, tags: [] };
}

/** 20:30에 「저스트 채팅」 → 「리그 오브 레전드」로 바꾼 치지직 방송 */
const TIMELINE: BroadcastTimeline = {
  segments: [
    segment(
      'CHZZK',
      STARTED,
      '2026-10-17T20:30:00+09:00',
      '인사 방송',
      '저스트 채팅',
      'https://p/talk.png',
    ),
    segment(
      'CHZZK',
      '2026-10-17T20:30:00+09:00',
      null,
      '[롤] 다이아 승급전',
      '리그 오브 레전드',
      'https://p/lol.png',
    ),
  ],
  truncated: false,
};

describe('stepViewForClip — 진행 카드는 서버 영상의 지금 판에만 선다(POK-510)', () => {
  it('목업 줄 · 영상이 없는 줄 · 편집 중(옛 판 영상)은 카드가 없다', () => {
    expect(stepViewForClip(MOCK_CLIPS[0]!, NOW)).toBeNull();
    expect(stepViewForClip(toLibraryClip(entry('rendered', null), '9'), NOW)).toBeNull();
    expect(stepViewForClip(toLibraryClip(entry('editing'), '9'), NOW)).toBeNull();
  });

  it('만드는 중이면 「영상 만들기」가 지금 단계이고 판 이름이 붙는다 — 편집기 작은 창과 같은 판정이다', () => {
    const step = stepViewForClip(
      toLibraryClip(
        entry(
          'rendering',
          video({
            status: 'rendering',
            progress: { percent: 42, stage: null, attempt: 1, jobId: 'j' },
          }),
        ),
        '9',
      ),
      NOW,
    )!;
    expect(step.versionLabel).toBe('편집본 v2');
    expect(step.view.steps.map((s) => s.state)).toEqual(['done', 'now', 'todo']);
    expect(step.view.steps[0]!.note).toBe('14:21');
    expect(step.view.steps[1]!.note).toBe('42%');
    expect(step.view.progress).toEqual({ value: 42 });
    expect(step.detail).toBe('영상을 만들고 있어요.');
  });

  it('확인 필요 · 업로드 실패는 이 화면에서 할 일이라 설명을 보관함 안내로 바꾼다', () => {
    const checking = toLibraryClip(
      entry('checking', video({ upload: upload({ status: 'checking' }) })),
      '9',
    );
    const checkingStep = stepViewForClip(checking, NOW)!;
    expect(checkingStep.view.steps[2]!.state).toBe('warning');
    expect(checkingStep.detail).toBe(noteText('checking'));

    const failed = toLibraryClip(
      entry(
        'rendered',
        video({
          upload: upload({ status: 'failed', error: { code: 'QUOTA_EXCEEDED', message: null } }),
        }),
      ),
      '9',
    );
    const failedStep = stepViewForClip(failed, NOW)!;
    expect(failedStep.view.steps[2]!.state).toBe('danger');
    expect(failedStep.detail).toBe(uploadFailureText(failed));
    // 편집기 말(「영상 만들기」를 다시 눌러)이 아니다
    expect(failedStep.detail).not.toMatch(/영상 만들기」를 다시/);
  });

  it('올라갔으면 세 단계가 다 끝나고 올린 시각이 붙는다', () => {
    const step = stepViewForClip(
      toLibraryClip(
        entry(
          'uploaded',
          video({
            upload: upload({
              status: 'uploaded',
              videoId: 'abc',
              updatedAt: '2026-10-30T14:26:00+09:00',
            }),
          }),
        ),
        '9',
      ),
      NOW,
    )!;
    expect(step.view.steps.map((s) => s.state)).toEqual(['done', 'done', 'done']);
    expect(step.view.steps[2]!.note).toBe('14:26');
    expect(step.view.progress).toBeNull();
  });
});

describe('clipSourceOf — 원본 방송의 제목 · 카테고리는 그 장면 때의 값이다(POK-511)', () => {
  it('장면 시각(기준점 + 구간 시작)의 구간에서 제목 · 카테고리 · 포스터를 고른다', () => {
    // 기준점 19:52:30 + 1:23:45 = 21:16:15 → 바꾼 뒤 구간
    const source = clipSourceOf(
      entry('rendered'),
      row({ timelineOriginAt: '2026-10-17T19:52:30+09:00' }),
      TIMELINE,
    );
    expect(source).toEqual({
      title: '[롤] 다이아 승급전',
      category: '리그 오브 레전드',
      categoryImageUrl: 'https://p/lol.png',
      platform: '치지직',
    });
  });

  it('카테고리를 바꾸기 전 장면은 바꾸기 전 카테고리다 — 방송 줄의 마지막 값이 아니다', () => {
    const early = entry('rendered', video(), { cut: { inAtMs: 600_000, outAtMs: 630_000 } });
    const source = clipSourceOf(early, row(), TIMELINE);
    expect(source.title).toBe('인사 방송');
    expect(source.category).toBe('저스트 채팅');
    expect(source.categoryImageUrl).toBe('https://p/talk.png');
  });

  it('그 시각 구간이 없으면 방송 줄의 값으로 물러서고 포스터는 비운다', () => {
    const truncated: BroadcastTimeline = { segments: TIMELINE.segments.slice(1), truncated: true };
    const early = entry('rendered', video(), { cut: { inAtMs: 600_000, outAtMs: 630_000 } });
    expect(clipSourceOf(early, row(), truncated)).toEqual({
      title: '방송 끝 제목',
      category: '방송 끝 카테고리',
      categoryImageUrl: null,
      platform: '치지직',
    });
  });

  it('대표 플랫폼 구간이 없으면 글자는 다른 플랫폼 것을 쓰고 포스터는 쓰지 않는다(POK-467 규칙)', () => {
    const soopOnly: BroadcastTimeline = {
      segments: [segment('SOOP', STARTED, null, 'SOOP 제목', '스타크래프트', 'https://p/sc.png')],
      truncated: false,
    };
    expect(clipSourceOf(entry('rendered'), row(), soopOnly)).toEqual({
      title: 'SOOP 제목',
      category: '스타크래프트',
      categoryImageUrl: null,
      platform: 'SOOP',
    });
  });

  it('그 시각 구간의 제목 · 카테고리가 비어 있으면 빈 그대로다 — 나중 값을 앞 장면에 붙이지 않는다(PR #462 2차 리뷰)', () => {
    const blank: BroadcastTimeline = {
      segments: [segment('CHZZK', STARTED, null, null, null)],
      truncated: false,
    };
    expect(clipSourceOf(entry('rendered'), row(), blank)).toEqual({
      title: null,
      category: null,
      categoryImageUrl: null,
      platform: '치지직',
    });
  });

  it('대표 플랫폼을 모르면 글자와 포스터를 같은 플랫폼 구간에서 고른다(PR #462 2차 리뷰)', () => {
    const simulcast: BroadcastTimeline = {
      segments: [
        segment('SOOP', STARTED, null, 'SOOP 제목', '스타크래프트', 'https://p/sc.png'),
        segment('CHZZK', STARTED, null, '치지직 제목', '리그 오브 레전드', 'https://p/lol.png'),
      ],
      truncated: false,
    };
    expect(clipSourceOf(entry('rendered'), null, simulcast)).toEqual({
      title: 'SOOP 제목',
      category: '스타크래프트',
      categoryImageUrl: 'https://p/sc.png',
      platform: 'SOOP',
    });
  });

  it('아무것도 못 읽었으면 지어내지 않는다', () => {
    expect(clipSourceOf(entry('rendered'), null, undefined)).toEqual({
      title: null,
      category: null,
      categoryImageUrl: null,
      platform: null,
    });
    // 빈 제목은 없는 것이다
    expect(
      clipSourceOf(entry('rendered'), row({ title: '  ', category: null }), undefined).title,
    ).toBeNull();
  });
});

describe('sceneAtMs — 편집 구간 시작의 실제 시각', () => {
  it('방송 줄의 기준점, 없으면 보관함 줄의 시작 시각에 구간 시작을 더한다', () => {
    const base = Date.parse(STARTED);
    expect(
      sceneAtMs(entry('rendered'), row({ timelineOriginAt: '2026-10-17T19:53:00+09:00' })),
    ).toBe(Date.parse('2026-10-17T19:53:00+09:00') + CUT.inAtMs);
    expect(sceneAtMs(entry('rendered'), null)).toBe(base + CUT.inAtMs);
  });

  it('구간이 없거나 시각을 모르면 null', () => {
    expect(sceneAtMs(entry('rendered', video(), { cut: null }), row())).toBeNull();
    const noStart = entry('rendered', video(), {
      broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
    });
    expect(sceneAtMs(noStart, null)).toBeNull();
    expect(sceneAtMs(noStart, row({ startedAt: null, timelineOriginAt: null }))).toBeNull();
  });
});

describe('원본 방송 카드 표기', () => {
  it('방송 길이 — 시간 · 분, 한 시간 안이면 분만, 방송 중이면 「방송 중」, 모르면 null', () => {
    const b = entry('rendered').broadcast;
    expect(broadcastLengthLabel(b)).toBe('2시간 14분');
    expect(broadcastLengthLabel({ ...b, endedAt: '2026-10-17T20:40:00+09:00' })).toBe('48분');
    expect(broadcastLengthLabel({ ...b, status: 'live', endedAt: null })).toBe('방송 중');
    expect(broadcastLengthLabel({ ...b, endedAt: null })).toBeNull();
    expect(broadcastLengthLabel({ ...b, endedAt: 'x' })).toBeNull();
  });

  it('장면 위치 — 방송 기준점에서 잰 시계', () => {
    expect(sceneLabel(CUT)).toBe('1:23:45 ~ 1:24:30');
    expect(sceneLabel({ inAtMs: 65_000, outAtMs: 95_000 })).toBe('1:05 ~ 1:35');
  });

  it('원본 방송으로 가는 길 — 방송 중이면 라이브, 끝났으면 지난 방송, 원본이 만료됐으면 없다', () => {
    expect(sourceLinkOf(entry('rendered'), NOW)).toEqual({
      href: '/broadcast/vod/s1',
      label: '지난 방송 열기',
    });
    const live = entry('rendered', video(), {
      streamId: 'a/b',
      broadcast: { status: 'live', startedAt: STARTED, endedAt: null, vodExpiresAt: null },
    });
    expect(sourceLinkOf(live, NOW)).toEqual({
      href: '/broadcast/livenow?stream=a%2Fb',
      label: '라이브 열기',
    });
    const expired = entry('rendered', video(), {
      broadcast: {
        status: 'ended',
        startedAt: STARTED,
        endedAt: ENDED,
        vodExpiresAt: '2026-10-01T00:00:00Z',
      },
    });
    expect(sourceLinkOf(expired, NOW)).toBeNull();
  });

  it('시각 한 칸 — 오늘이면 「오늘 시:분」, 아니면 날짜까지. 방송일은 오늘이어도 날짜를 쓴다', () => {
    expect(instantText('2026-10-30T14:20:00+09:00', NOW)).toBe('오늘 14:20');
    expect(instantText(STARTED, NOW)).toBe(instantLabel(new Date(STARTED)));
    expect(instantText('2026-10-30T09:05:00+09:00', NOW, false)).toBe(
      instantLabel(new Date('2026-10-30T09:05:00+09:00')),
    );
    expect(instantText(null, NOW)).toBeNull();
    expect(instantText('x', NOW)).toBeNull();
  });
});
