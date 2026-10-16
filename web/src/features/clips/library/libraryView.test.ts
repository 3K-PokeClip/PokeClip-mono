import { describe, expect, it } from 'vitest';
import {
  STATUS_BADGE,
  cardName,
  chipOf,
  chipsFor,
  countByChip,
  detailViewFor,
  displayTitle,
  durationLabel,
  filterByChip,
  filterByQuery,
  isCardDimmed,
  noteText,
  retentionLabel,
  safeExternalUrl,
  sortClips,
  statusFor,
} from './libraryView';
import type { ClipStatus, LibraryClip } from './useLibraryMockState';

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

const STATUSES: ClipStatus[] = ['editing', 'ready', 'published', 'expired', 'failed'];

/** 시안 상태 다섯이 하나씩 든 목록 — 칩 분할이 전체를 덮는지 셀 때 쓴다 */
const ONE_OF_EACH: LibraryClip[] = STATUSES.map((status) =>
  clip({
    id: status,
    status,
    sourceExpiresAt: status === 'expired' ? expiresIn(-1) : expiresIn(30),
  }),
);

describe('libraryView — STATUS_BADGE', () => {
  it('시안 상태의 배지 톤·라벨: 유튜브에 올라간 것은 「업로드됨」이고 원본 만료도 같은 배지다(POK-291)', () => {
    expect(STATUS_BADGE.editing).toEqual({ tone: 'point', label: '편집 중' });
    expect(STATUS_BADGE.ready).toEqual({ tone: 'neutral', label: '업로드 대기' });
    expect(STATUS_BADGE.published).toEqual({ tone: 'success', label: '업로드됨' });
    expect(STATUS_BADGE.expired).toEqual(STATUS_BADGE.published);
    expect(STATUS_BADGE.failed).toEqual({ tone: 'danger', label: '렌더 실패' });
  });

  it('진행 단계 넷의 배지: 만드는 중 · 올리는 중 · 확인 필요 · 업로드 실패(POK-291)', () => {
    expect(STATUS_BADGE.rendering.label).toBe('만드는 중');
    expect(STATUS_BADGE.uploading.label).toBe('올리는 중');
    expect(STATUS_BADGE.checking).toEqual({ tone: 'warning', label: '확인 필요' });
    expect(STATUS_BADGE.uploadFailed).toEqual({ tone: 'danger', label: '업로드 실패' });
  });
});

describe('libraryView: 진행 단계의 칩과 패널(POK-291)', () => {
  it('만드는 중·올리는 중은 작업 중, 손이 필요한 업로드 실패·확인 필요는 업로드 대기 칩이다', () => {
    expect(chipOf('rendering')).toBe('working');
    expect(chipOf('uploading')).toBe('working');
    expect(chipOf('uploadFailed')).toBe('ready');
    expect(chipOf('checking')).toBe('ready');
  });

  it('서버 일이 도는 동안은 누를 것이 없고 제목이 잠긴다: 이어서 편집은 열어 둔다', () => {
    expect(detailViewFor('rendering')).toMatchObject({
      primary: { kind: 'busy', label: '영상 만드는 중' },
      titleLocked: true,
      download: false,
      edit: { label: '이어서 편집' },
    });
    expect(detailViewFor('uploading')).toMatchObject({
      primary: { kind: 'busy', label: '유튜브에 올리는 중' },
      titleLocked: true,
    });
    expect(detailViewFor('checking')).toMatchObject({
      primary: { kind: 'busy', label: '업로드 확인 필요' },
      note: 'checking',
    });
  });

  it('업로드 실패의 주 동작은 「다시 시도」다: 저장된 정보로 올리니 제목은 잠근다', () => {
    expect(detailViewFor('uploadFailed')).toMatchObject({
      primary: { kind: 'action', label: '다시 시도', action: 'retryUpload' },
      titleLocked: true,
      download: true,
    });
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
  it('칩은 넷이다 — 승인 게이트가 없어 반려됨 칩이 없다', () => {
    expect(chipsFor().map((c) => c.label)).toEqual(['전체', '작업 중', '업로드 대기', '발행됨']);
  });

  it('상태마다 한 칩에 든다: 원본 만료는 발행됨, 렌더 실패는 작업 중', () => {
    expect(chipOf('expired')).toBe('published');
    expect(chipOf('failed')).toBe('working');
    expect(chipOf('ready')).toBe('ready');
  });

  it('전체를 뺀 칩 수의 합이 전체와 같다 — 칩은 전체를 분할한다', () => {
    const counts = countByChip(ONE_OF_EACH, NOW);
    expect(counts.all).toBe(5);
    expect(counts.working + counts.ready + counts.published).toBe(5);
  });

  it('칩으로 거른다 — 전체는 전부, 업로드 대기는 ready만', () => {
    expect(filterByChip(ONE_OF_EACH, 'all', NOW)).toHaveLength(5);
    expect(filterByChip(ONE_OF_EACH, 'ready', NOW).map((c) => c.id)).toEqual(['ready']);
    expect(filterByChip(ONE_OF_EACH, 'working', NOW).map((c) => c.id)).toEqual([
      'editing',
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

  it('오프셋 표기가 섞여도 실제 시각 순으로 정렬한다 — 사전순 비교면 뒤집힌다', () => {
    const mixed = [
      clip({ id: 'kst', editedAt: '2026-09-02T14:20:00+09:00' }), // = 05:20Z
      clip({ id: 'utc', editedAt: '2026-09-02T06:00:00Z' }), // 40분 더 늦다
    ];
    expect(sortClips(mixed, 'edited', NOW).map((c) => c.id)).toEqual(['utc', 'kst']);
  });

  it('원본을 바꾸지 않는다', () => {
    const before = list.map((c) => c.id);
    sortClips(list, 'expiry', NOW);
    expect(list.map((c) => c.id)).toEqual(before);
  });
});

describe('libraryView — detailViewFor', () => {
  it.each<[ClipStatus, string, string | null, boolean]>([
    ['editing', '이어서 편집', null, true],
    ['ready', '업로드', '이어서 편집', true],
    ['published', '유튜브 보기', '새 버전으로 편집', true],
    ['expired', '유튜브 보기', null, true],
    ['failed', '렌더 재시도', '이어서 편집', false],
  ])('%s → 주 동작 「%s」 · 편집 %s · 다운로드 %s', (status, primary, edit, download) => {
    const view = detailViewFor(status);
    expect(view.primary.label).toBe(primary);
    expect(view.edit?.label ?? null).toBe(edit);
    expect(view.download).toBe(download);
    expect(view.badge).toEqual(STATUS_BADGE[status]);
  });

  it('링크 주 동작은 편집 중의 「이어서 편집」 하나이고 편집기로 간다', () => {
    expect(detailViewFor('editing').primary).toMatchObject({
      kind: 'link',
      href: '/clips/editor',
      variant: 'solid',
    });
  });

  it('업로드·렌더 재시도만 동작 버튼이고 유튜브 보기만 외부 링크다', () => {
    expect(detailViewFor('ready').primary).toMatchObject({
      kind: 'action',
      action: 'upload',
    });
    expect(detailViewFor('failed').primary).toMatchObject({
      kind: 'action',
      action: 'retryRender',
    });
    expect(detailViewFor('published').primary.kind).toBe('external');
    expect(detailViewFor('expired').primary.kind).toBe('external');
  });

  it('원본 만료는 안내가 붙고 발행됨은 안내가 없다', () => {
    expect(detailViewFor('expired')).toMatchObject({ note: 'expired' });
    expect(detailViewFor('published')).toMatchObject({ note: null });
  });

  it('시안 상태 다섯은 제목을 잠그지 않는다 — 잠그는 것은 진행 단계뿐이다', () => {
    for (const status of STATUSES) {
      expect(detailViewFor(status).titleLocked).toBe(false);
    }
  });

  it('렌더 실패는 길이를 말하지 않는다', () => {
    expect(detailViewFor('failed').showDuration).toBe(false);
    expect(detailViewFor('ready').showDuration).toBe(true);
  });
});

describe('libraryView — isCardDimmed · safeExternalUrl', () => {
  it('원본이 만료된 것만 카드를 가라앉힌다 — 카드와 표가 같은 값을 본다', () => {
    expect(isCardDimmed('expired')).toBe(true);
    for (const status of STATUSES.filter((s) => s !== 'expired')) {
      expect(isCardDimmed(status)).toBe(false);
    }
  });

  it('http(s)만 앵커에 실린다 — 나머지는 null로 떨어져 링크를 그리지 않는다', () => {
    expect(safeExternalUrl('https://www.youtube.com/shorts/abc')).toBe(
      'https://www.youtube.com/shorts/abc',
    );
    expect(safeExternalUrl('http://example.test/a')).toBe('http://example.test/a');
    expect(safeExternalUrl('javascript:alert(1)')).toBeNull();
    expect(safeExternalUrl('data:text/html,<script>')).toBeNull();
    expect(safeExternalUrl('그냥 문자열')).toBeNull();
    expect(safeExternalUrl(undefined)).toBeNull();
  });
});

describe('libraryView — 표기', () => {
  it('안내문은 안내 종류마다 하나다', () => {
    expect(noteText('expired')).toBe(
      '원본 VOD가 만료되어 다시 편집할 수 없어요. 발행된 영상은 그대로 유지됩니다.',
    );
    expect(noteText('checking')).toMatch(/^유튜브에 올라갔는지 확인하지 못했어요/);
  });

  it('길이는 분:초이고, 렌더 실패나 모르는 길이는 null이다', () => {
    expect(durationLabel(clip({ id: 'a', durationSec: 82 }), 'editing')).toBe('1:22');
    expect(durationLabel(clip({ id: 'a', durationSec: 44 }), 'ready')).toBe('0:44');
    expect(durationLabel(clip({ id: 'a', durationSec: 82 }), 'failed')).toBeNull();
    expect(durationLabel(clip({ id: 'a', durationSec: null }), 'ready')).toBeNull();
  });

  it('길이가 유한하지 않으면 말하지 않는다 — 「NaN:NaN」을 찍느니 비운다', () => {
    expect(durationLabel(clip({ id: 'a', durationSec: Number.NaN }), 'ready')).toBeNull();
    expect(
      durationLabel(clip({ id: 'a', durationSec: Number.POSITIVE_INFINITY }), 'ready'),
    ).toBeNull();
  });

  it('원본 보존은 D-day·만료됨·미상으로 말한다', () => {
    expect(retentionLabel({ kind: 'active', label: 'D-58', urgent: false })).toBe('원본 만료 D-58');
    expect(retentionLabel({ kind: 'expired' })).toBe('원본 만료됨');
    expect(retentionLabel({ kind: 'unknown' })).toBe('—');
  });

  it('빈 제목의 대체 문구는 한 곳에서 나온다 — 카드 글자와 접근 이름이 같다', () => {
    const blank = clip({ id: 'a', title: '   ' });
    expect(displayTitle(blank)).toBe('제목 없는 편집본');
    expect(cardName(blank, 'ready', null)).toContain(displayTitle(blank));
    expect(displayTitle(clip({ id: 'b', title: '  보스 막타  ' }))).toBe('보스 막타');
  });

  it('카드 이름은 제목 · 상태 · 길이 순이고, 남의 편집본이면 편집자 이름이 붙는다', () => {
    const mine = clip({ id: 'a', title: '보스 막타 · 역전 순간' });
    expect(cardName(mine, 'editing', '1:22')).toBe('보스 막타 · 역전 순간 · 편집 중 · 길이 1:22');
    expect(cardName(mine, 'failed', null)).toBe('보스 막타 · 역전 순간 · 렌더 실패');

    const theirs = clip({ id: 'b', title: '도네 반응', owner: { name: '감자대장', me: false } });
    expect(cardName(theirs, 'ready', '0:44')).toBe(
      '도네 반응 · 업로드 대기 · 길이 0:44 · 편집자 감자대장',
    );
  });

  it('제목이 비면 「제목 없는 편집본」으로 읽힌다', () => {
    expect(cardName(clip({ id: 'a', title: '   ' }), 'ready', null)).toBe(
      '제목 없는 편집본 · 업로드 대기',
    );
  });
});
