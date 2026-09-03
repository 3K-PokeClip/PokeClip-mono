import { describe, expect, it } from 'vitest';
import {
  STATUS_BADGE,
  cardName,
  chipOf,
  chipsFor,
  countByChip,
  dayTimeLabel,
  detailViewFor,
  durationLabel,
  filterByChip,
  filterByQuery,
  noteText,
  retentionLabel,
  sortClips,
  statusFor,
} from './libraryView';
import type { ClipStatus, LibraryClip, LibraryRole } from './useLibraryMockState';

const NOW = new Date('2026-09-02T20:00:00+09:00');
const DAY_MS = 24 * 60 * 60 * 1000;

function expiresIn(days: number): string {
  return new Date(NOW.getTime() + days * DAY_MS).toISOString();
}

function clip(overrides: Partial<LibraryClip> & { id: string }): LibraryClip {
  return {
    title: '편집본',
    status: 'editing',
    durationSec: 82,
    owner: { name: '게임하는너구리', me: true },
    sourceLabel: '8월 31일 라이브',
    sourceExpiresAt: expiresIn(58),
    templateLabel: '기본 쇼츠',
    subtitleLabel: '자동 자막 12줄',
    createdAt: '2026-08-31T22:40:00+09:00',
    editedAt: '2026-09-02T14:20:00+09:00',
    ...overrides,
  };
}

const STATUSES: ClipStatus[] = [
  'editing',
  'ready',
  'pending',
  'rejected',
  'published',
  'expired',
  'failed',
];
const ROLES: LibraryRole[] = ['streamer', 'editor'];

/** 상태 7종이 하나씩 든 목록 — 칩 분할이 전체를 덮는지 셀 때 쓴다 */
const ONE_OF_EACH: LibraryClip[] = STATUSES.map((status) =>
  clip({
    id: status,
    status,
    sourceExpiresAt: status === 'expired' ? expiresIn(-1) : expiresIn(30),
  }),
);

describe('libraryView — STATUS_BADGE', () => {
  it('7개 상태의 배지 톤·라벨이 시안과 같다 — 발행됨·원본 만료는 같은 「발행됨」이다', () => {
    expect(STATUS_BADGE.editing).toEqual({ tone: 'point', label: '편집 중' });
    expect(STATUS_BADGE.ready).toEqual({ tone: 'neutral', label: '업로드 대기' });
    expect(STATUS_BADGE.pending).toEqual({ tone: 'warning', label: '승인 대기' });
    expect(STATUS_BADGE.rejected).toEqual({ tone: 'danger', label: '반려됨' });
    expect(STATUS_BADGE.published).toEqual({ tone: 'success', label: '발행됨' });
    expect(STATUS_BADGE.expired).toEqual(STATUS_BADGE.published);
    expect(STATUS_BADGE.failed).toEqual({ tone: 'danger', label: '렌더 실패' });
  });
});

describe('libraryView — statusFor', () => {
  it('발행됨인데 원본 보관이 지났으면 expired로 접는다', () => {
    expect(
      statusFor(clip({ id: 'a', status: 'published', sourceExpiresAt: expiresIn(-1) }), NOW),
    ).toBe('expired');
  });

  it('원본이 남았으면 그대로 발행됨이다', () => {
    expect(
      statusFor(clip({ id: 'a', status: 'published', sourceExpiresAt: expiresIn(3) }), NOW),
    ).toBe('published');
  });

  it('발행되지 않은 편집본은 원본이 지나도 상태를 바꾸지 않는다 — 만료 안내는 발행됨의 것이다', () => {
    expect(
      statusFor(clip({ id: 'a', status: 'editing', sourceExpiresAt: expiresIn(-1) }), NOW),
    ).toBe('editing');
  });
});

describe('libraryView — 칩', () => {
  it('스트리머 칩은 넷, 편집자 칩은 반려됨이 더해 다섯이다', () => {
    expect(chipsFor('streamer').map((c) => c.label)).toEqual([
      '전체',
      '작업 중',
      '업로드 대기',
      '발행됨',
    ]);
    expect(chipsFor('editor').map((c) => c.label)).toEqual([
      '전체',
      '작업 중',
      '업로드 대기',
      '반려됨',
      '발행됨',
    ]);
  });

  it('스트리머는 반려됨·승인 대기를 작업 중에 넣고, 편집자는 반려됨을 따로 센다', () => {
    expect(chipOf('rejected', 'streamer')).toBe('working');
    expect(chipOf('rejected', 'editor')).toBe('rejected');
    expect(chipOf('pending', 'streamer')).toBe('working');
    expect(chipOf('pending', 'editor')).toBe('working');
    expect(chipOf('expired', 'streamer')).toBe('published');
    expect(chipOf('failed', 'editor')).toBe('working');
    expect(chipOf('ready', 'editor')).toBe('ready');
  });

  it.each(ROLES)(
    '%s 시점에서 전체를 뺀 칩 수의 합이 전체와 같다 — 칩은 전체를 분할한다',
    (role) => {
      const counts = countByChip(ONE_OF_EACH, role, NOW);
      expect(counts.all).toBe(7);
      expect(counts.working + counts.ready + counts.rejected + counts.published).toBe(7);
      // 시점에 없는 칩은 0이어야 카드가 사라지지 않는다
      if (role === 'streamer') expect(counts.rejected).toBe(0);
    },
  );

  it('칩으로 거른다 — 전체는 전부, 업로드 대기는 ready만', () => {
    expect(filterByChip(ONE_OF_EACH, 'all', 'streamer', NOW)).toHaveLength(7);
    expect(filterByChip(ONE_OF_EACH, 'ready', 'streamer', NOW).map((c) => c.id)).toEqual(['ready']);
    expect(filterByChip(ONE_OF_EACH, 'working', 'streamer', NOW).map((c) => c.id)).toEqual([
      'editing',
      'pending',
      'rejected',
      'failed',
    ]);
  });
});

describe('libraryView — 검색', () => {
  const list = [
    clip({ id: 'a', title: '새벽 랭크 · 승급 확정' }),
    clip({ id: 'b', title: 'Boss 막타' }),
  ];

  it('공백을 다듬고 대소문자를 가리지 않는다', () => {
    expect(filterByQuery(list, '  랭크 ').map((c) => c.id)).toEqual(['a']);
    expect(filterByQuery(list, 'BOSS').map((c) => c.id)).toEqual(['b']);
  });

  it('빈 검색어는 전체다', () => {
    expect(filterByQuery(list, '')).toHaveLength(2);
    expect(filterByQuery(list, '   ')).toHaveLength(2);
  });
});

describe('libraryView — 정렬', () => {
  const list = [
    clip({
      id: 'old',
      editedAt: '2026-08-01T10:00:00+09:00',
      createdAt: '2026-07-01T10:00:00+09:00',
      sourceExpiresAt: expiresIn(5),
    }),
    clip({
      id: 'new',
      editedAt: '2026-09-02T18:00:00+09:00',
      createdAt: '2026-09-02T10:00:00+09:00',
      sourceExpiresAt: expiresIn(40),
    }),
    clip({
      id: 'gone',
      editedAt: '2026-08-20T10:00:00+09:00',
      createdAt: '2026-08-10T10:00:00+09:00',
      sourceExpiresAt: expiresIn(-2),
    }),
    clip({
      id: 'unknown',
      editedAt: '2026-08-25T10:00:00+09:00',
      createdAt: '2026-08-15T10:00:00+09:00',
      sourceExpiresAt: null,
    }),
  ];

  it('최근 편집순은 editedAt 내림차순', () => {
    expect(sortClips(list, 'edited', NOW).map((c) => c.id)).toEqual([
      'new',
      'unknown',
      'gone',
      'old',
    ]);
  });

  it('생성순은 createdAt 내림차순', () => {
    expect(sortClips(list, 'created', NOW).map((c) => c.id)).toEqual([
      'new',
      'unknown',
      'gone',
      'old',
    ]);
  });

  it('만료 임박순은 D-day 오름차순이고 만료됐거나 모르는 것은 뒤로 간다', () => {
    expect(sortClips(list, 'expiry', NOW).map((c) => c.id)).toEqual([
      'old',
      'new',
      'unknown',
      'gone',
    ]);
  });

  it('원본을 바꾸지 않는다', () => {
    const before = list.map((c) => c.id);
    sortClips(list, 'expiry', NOW);
    expect(list.map((c) => c.id)).toEqual(before);
  });
});

describe('libraryView — detailViewFor', () => {
  it.each<[ClipStatus, LibraryRole, string, string | null, boolean]>([
    ['editing', 'streamer', '이어서 편집', null, true],
    ['editing', 'editor', '이어서 편집', null, true],
    ['ready', 'streamer', '업로드', '이어서 편집', true],
    ['ready', 'editor', '업로드 요청', '이어서 편집', true],
    ['pending', 'streamer', '승인 대기함에서 검토', null, true],
    ['pending', 'editor', '내 요청 보기', null, true],
    ['rejected', 'streamer', '수정하기', null, true],
    ['rejected', 'editor', '수정하기', null, true],
    ['published', 'streamer', '유튜브 보기', '새 버전으로 편집', true],
    ['published', 'editor', '유튜브 보기', '새 버전으로 편집', true],
    ['expired', 'streamer', '유튜브 보기', null, true],
    ['expired', 'editor', '유튜브 보기', null, true],
    ['failed', 'streamer', '렌더 재시도', '이어서 편집', false],
    ['failed', 'editor', '렌더 재시도', '이어서 편집', false],
  ])(
    '%s · %s → 주 동작 「%s」 · 편집 %s · 다운로드 %s',
    (status, role, primary, edit, download) => {
      const view = detailViewFor(status, role);
      expect(view.primary.label).toBe(primary);
      expect(view.edit?.label ?? null).toBe(edit);
      expect(view.download).toBe(download);
      expect(view.badge).toEqual(STATUS_BADGE[status]);
    },
  );

  it('승인 대기만 soft 링크이고 나머지 링크는 solid다 — 승인 대기함으로 이동만 한다', () => {
    const pending = detailViewFor('pending', 'streamer').primary;
    expect(pending).toEqual({
      kind: 'link',
      label: '승인 대기함에서 검토',
      href: '/clips/approvals',
      variant: 'soft',
    });
    expect(detailViewFor('editing', 'streamer').primary).toMatchObject({
      kind: 'link',
      href: '/clips/editor',
      variant: 'solid',
    });
    expect(detailViewFor('rejected', 'editor').primary).toMatchObject({
      kind: 'link',
      href: '/clips/editor',
      variant: 'solid',
    });
  });

  it('업로드·렌더 재시도만 동작 버튼이고 유튜브 보기만 외부 링크다', () => {
    expect(detailViewFor('ready', 'streamer').primary).toMatchObject({
      kind: 'action',
      action: 'upload',
    });
    expect(detailViewFor('failed', 'editor').primary).toMatchObject({
      kind: 'action',
      action: 'retryRender',
    });
    expect(detailViewFor('published', 'streamer').primary.kind).toBe('external');
    expect(detailViewFor('expired', 'streamer').primary.kind).toBe('external');
  });

  it('원본 만료는 가라앉고 안내가 붙는다 · 승인 대기는 안내만 · 반려됨은 사유를 연다', () => {
    expect(detailViewFor('expired', 'streamer')).toMatchObject({ dimmed: true, note: 'expired' });
    expect(detailViewFor('pending', 'editor')).toMatchObject({ dimmed: false, note: 'pending' });
    expect(detailViewFor('rejected', 'streamer').showRejection).toBe(true);
    expect(detailViewFor('published', 'streamer')).toMatchObject({
      dimmed: false,
      note: null,
      showRejection: false,
    });
  });

  it('렌더 실패는 길이를 말하지 않는다', () => {
    expect(detailViewFor('failed', 'streamer').showDuration).toBe(false);
    expect(detailViewFor('ready', 'streamer').showDuration).toBe(true);
  });
});

describe('libraryView — 표기', () => {
  it('안내문은 시점마다 다르다 — 승인 대기는 스트리머와 편집자가 할 수 있는 일이 다르다', () => {
    expect(noteText('expired', 'streamer')).toBe(
      '원본 VOD가 만료되어 다시 편집할 수 없어요. 발행된 영상은 그대로 유지됩니다.',
    );
    expect(noteText('pending', 'streamer')).toMatch(/^승인 · 반려는 승인 대기함에서 처리해요/);
    expect(noteText('pending', 'editor')).toMatch(/^승인 대기 중에는 편집이 잠겨요/);
  });

  it('길이는 분:초이고, 렌더 실패나 모르는 길이는 null이다', () => {
    expect(durationLabel(clip({ id: 'a', durationSec: 82 }), 'editing')).toBe('1:22');
    expect(durationLabel(clip({ id: 'a', durationSec: 44 }), 'pending')).toBe('0:44');
    expect(durationLabel(clip({ id: 'a', durationSec: 82 }), 'failed')).toBeNull();
    expect(durationLabel(clip({ id: 'a', durationSec: null }), 'ready')).toBeNull();
  });

  it('원본 보존은 D-day·만료됨·미상으로 말한다', () => {
    expect(retentionLabel({ kind: 'active', label: 'D-58', urgent: false })).toBe('원본 만료 D-58');
    expect(retentionLabel({ kind: 'expired' })).toBe('원본 만료됨');
    expect(retentionLabel({ kind: 'unknown' })).toBe('—');
  });

  it('반려 시각은 오늘·어제·그 밖의 날짜로 읽힌다', () => {
    expect(dayTimeLabel('2026-09-02T14:20:00+09:00', NOW)).toBe('오늘 14:20');
    expect(dayTimeLabel('2026-09-01T21:32:00+09:00', NOW)).toBe('어제 21:32');
    expect(dayTimeLabel('2026-08-28T09:05:00+09:00', NOW)).toBe('8월 28일 09:05');
  });

  it('카드 이름은 제목 · 상태 · 길이 순이고, 남의 편집본이면 편집자 이름이 붙는다', () => {
    const mine = clip({ id: 'a', title: '보스 막타 · 역전 순간' });
    expect(cardName(mine, 'editing', '1:22')).toBe('보스 막타 · 역전 순간 · 편집 중 · 길이 1:22');
    expect(cardName(mine, 'failed', null)).toBe('보스 막타 · 역전 순간 · 렌더 실패');

    const theirs = clip({ id: 'b', title: '도네 반응', owner: { name: '감자대장', me: false } });
    expect(cardName(theirs, 'pending', '0:44')).toBe(
      '도네 반응 · 승인 대기 · 길이 0:44 · 편집자 감자대장',
    );
  });

  it('제목이 비면 「제목 없는 편집본」으로 읽힌다', () => {
    expect(cardName(clip({ id: 'a', title: '   ' }), 'ready', null)).toBe(
      '제목 없는 편집본 · 업로드 대기',
    );
  });
});
