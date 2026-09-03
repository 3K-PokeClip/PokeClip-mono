import { ddayFor, type VodDday } from '@/features/broadcast/vod/vodListView';
import { formatUptime } from '@/features/player/playerMath';
import type { ClipStatus, LibraryClip, LibraryRole } from './useLibraryMockState';

// 시안 1g 보관함의 표시 규칙 — 상태가 배지·주 동작·보조 줄·칩·정렬로 어떻게 펼쳐지는지를
// 렌더와 떼어 순수 함수로 둔다(vodListView 선례). 상태 7종 × 시점 2종의 조합은 jsdom을 거치지
// 않고 표로 검사하는 편이 싸다.
//
// ⚠ 아래 Record 표들은 status 유니온이 늘면 타입으로 깨진다 — 화면이 조용히 빈 배지를 그리는
// 대신 빌드가 멈춘다(highlightCardView 선례). Partial로 풀지 말 것.

export type LibraryChip = 'all' | 'working' | 'ready' | 'rejected' | 'published';
export type LibrarySort = 'edited' | 'created' | 'expiry';

export const SORT_OPTIONS: { value: LibrarySort; label: string }[] = [
  { value: 'edited', label: '최근 편집순' },
  { value: 'created', label: '생성순' },
  { value: 'expiry', label: '만료 임박순' },
];

export function isLibrarySort(value: string): value is LibrarySort {
  return SORT_OPTIONS.some((option) => option.value === value);
}

/** 배지 톤 — DS Badge tone의 부분집합 */
export type StatusTone = 'point' | 'neutral' | 'warning' | 'danger' | 'success';

/** 시안 1g 카드·패널 배지. 발행됨과 발행됨·원본 만료는 같은 「발행됨」이다 — 만료는 안내문이 말한다 */
export const STATUS_BADGE: Record<ClipStatus, { tone: StatusTone; label: string }> = {
  editing: { tone: 'point', label: '편집 중' },
  ready: { tone: 'neutral', label: '업로드 대기' },
  pending: { tone: 'warning', label: '승인 대기' },
  rejected: { tone: 'danger', label: '반려됨' },
  published: { tone: 'success', label: '발행됨' },
  expired: { tone: 'success', label: '발행됨' },
  failed: { tone: 'danger', label: '렌더 실패' },
};

/**
 * 화면이 다루는 상태. 발행됨인데 원본 VOD 보관이 지났으면 expired로 접는다(ADR-004 60일) —
 * 만료는 저장된 상태가 아니라 시각에서 따라오는 것이라, 시드가 expired로 적어 둔 것과
 * published가 시간이 흘러 만료된 것을 같은 자리에서 본다(rowViewFor 선례).
 */
export function statusFor(clip: LibraryClip, now: Date): ClipStatus {
  if (clip.status === 'published' && ddayFor(clip.sourceExpiresAt, now).kind === 'expired') {
    return 'expired';
  }
  return clip.status;
}

// ---------- 칩 ----------

const CHIP_LABEL: Record<LibraryChip, string> = {
  all: '전체',
  working: '작업 중',
  ready: '업로드 대기',
  rejected: '반려됨',
  published: '발행됨',
};

const STREAMER_CHIPS: LibraryChip[] = ['all', 'working', 'ready', 'published'];
const EDITOR_CHIPS: LibraryChip[] = ['all', 'working', 'ready', 'rejected', 'published'];

/** 시점별 칩 행 — 반려됨 칩은 편집자(내가 고쳐야 할 것)에게만 있다(시안 1g ⑤) */
export function chipsFor(role: LibraryRole): { value: LibraryChip; label: string }[] {
  return (role === 'editor' ? EDITOR_CHIPS : STREAMER_CHIPS).map((value) => ({
    value,
    label: CHIP_LABEL[value],
  }));
}

/**
 * 편집본 하나가 속하는 칩. 칩은 전체를 분할한다 — 모든 편집본이 정확히 한 칩에 들어가
 * 칩 수의 합이 전체와 같다. 스트리머에겐 반려됨 칩이 없으므로 반려된 것도 작업 중이다.
 */
export function chipOf(status: ClipStatus, role: LibraryRole): Exclude<LibraryChip, 'all'> {
  switch (status) {
    case 'ready':
      return 'ready';
    case 'published':
    case 'expired':
      return 'published';
    case 'rejected':
      return role === 'editor' ? 'rejected' : 'working';
    case 'editing':
    case 'pending':
    case 'failed':
      return 'working';
  }
}

export function countByChip(
  clips: readonly LibraryClip[],
  role: LibraryRole,
  now: Date,
): Record<LibraryChip, number> {
  const counts: Record<LibraryChip, number> = {
    all: clips.length,
    working: 0,
    ready: 0,
    rejected: 0,
    published: 0,
  };
  for (const clip of clips) counts[chipOf(statusFor(clip, now), role)] += 1;
  return counts;
}

export function filterByChip(
  clips: readonly LibraryClip[],
  chip: LibraryChip,
  role: LibraryRole,
  now: Date,
): LibraryClip[] {
  if (chip === 'all') return [...clips];
  return clips.filter((clip) => chipOf(statusFor(clip, now), role) === chip);
}

// ---------- 검색 · 정렬 ----------

/** 제목 부분 일치 — 공백을 다듬고 대소문자를 가리지 않는다. 빈 검색어는 전체다 */
export function filterByQuery(clips: readonly LibraryClip[], query: string): LibraryClip[] {
  const needle = query.trim().toLocaleLowerCase();
  if (!needle) return [...clips];
  return clips.filter((clip) => clip.title.toLocaleLowerCase().includes(needle));
}

const byIsoDesc = (a: string, b: string) => (a < b ? 1 : a > b ? -1 : 0);

/**
 * 정렬. 만료 임박순은 원본 보관이 남은 것을 D-day 오름차순으로 앞세우고, 이미 만료됐거나
 * 기한을 모르는 것은 뒤로 보낸다(그 안에서는 최근 편집순) — 임박한 것을 찾는 정렬이지
 * 지난 것을 세는 정렬이 아니다.
 */
export function sortClips(
  clips: readonly LibraryClip[],
  sort: LibrarySort,
  now: Date,
): LibraryClip[] {
  const list = [...clips];
  switch (sort) {
    case 'edited':
      return list.sort((a, b) => byIsoDesc(a.editedAt, b.editedAt));
    case 'created':
      return list.sort((a, b) => byIsoDesc(a.createdAt, b.createdAt));
    case 'expiry':
      return list.sort((a, b) => {
        const ra = remainingMs(a, now);
        const rb = remainingMs(b, now);
        if (ra === null && rb === null) return byIsoDesc(a.editedAt, b.editedAt);
        if (ra === null) return 1;
        if (rb === null) return -1;
        return ra - rb;
      });
  }
}

function remainingMs(clip: LibraryClip, now: Date): number | null {
  if (!clip.sourceExpiresAt) return null;
  const remaining = new Date(clip.sourceExpiresAt).getTime() - now.getTime();
  return Number.isFinite(remaining) && remaining > 0 ? remaining : null;
}

// ---------- 상세 패널 ----------

export type PrimaryAction =
  | {
      kind: 'link';
      label: string;
      href: '/clips/editor' | '/clips/approvals';
      /** 승인 대기의 「이동만」은 soft — 여기서 무언가를 확정하는 버튼이 아니다(시안 1g ④) */
      variant: 'solid' | 'soft';
    }
  /** href는 clip.youtubeUrl — 없으면 링크 대신 비활성 버튼을 그린다(LinkButton 규칙) */
  | { kind: 'external'; label: '유튜브 보기' }
  | { kind: 'action'; label: string; action: 'upload' | 'retryRender' };

export type PanelNote = 'expired' | 'pending';

export interface DetailView {
  badge: { tone: StatusTone; label: string };
  primary: PrimaryAction;
  /** 보조 줄의 편집 링크 — null이면 편집 잠금(승인 대기·반려·원본 만료·편집 중) */
  edit: { label: '이어서 편집' | '새 버전으로 편집'; href: '/clips/editor' } | null;
  /** 렌더 실패는 받을 파일이 없다 */
  download: boolean;
  note: PanelNote | null;
  showRejection: boolean;
  /** 원본 만료 카드는 70%로 가라앉는다 */
  dimmed: boolean;
  /** 렌더 실패는 길이를 모른다 */
  showDuration: boolean;
}

const EDIT_CONTINUE = { label: '이어서 편집', href: '/clips/editor' } as const;
const EDIT_NEW_VERSION = { label: '새 버전으로 편집', href: '/clips/editor' } as const;

/** 시안 1g ④ 상태별 상세 패널 액션 7종. 시점이 가르는 칸은 업로드 대기·승인 대기의 라벨뿐이다 */
export function detailViewFor(status: ClipStatus, role: LibraryRole): DetailView {
  const badge = STATUS_BADGE[status];
  const base = {
    badge,
    edit: null,
    download: true,
    note: null,
    showRejection: false,
    dimmed: false,
    showDuration: true,
  } satisfies Omit<DetailView, 'primary'>;
  const editor = role === 'editor';

  switch (status) {
    case 'editing':
      return {
        ...base,
        primary: { kind: 'link', label: '이어서 편집', href: '/clips/editor', variant: 'solid' },
      };
    case 'ready':
      return {
        ...base,
        primary: { kind: 'action', label: editor ? '업로드 요청' : '업로드', action: 'upload' },
        edit: EDIT_CONTINUE,
      };
    case 'pending':
      return {
        ...base,
        primary: {
          kind: 'link',
          label: editor ? '내 요청 보기' : '승인 대기함에서 검토',
          href: '/clips/approvals',
          variant: 'soft',
        },
        note: 'pending',
      };
    case 'rejected':
      return {
        ...base,
        primary: { kind: 'link', label: '수정하기', href: '/clips/editor', variant: 'solid' },
        showRejection: true,
      };
    case 'published':
      return {
        ...base,
        primary: { kind: 'external', label: '유튜브 보기' },
        edit: EDIT_NEW_VERSION,
      };
    case 'expired':
      return {
        ...base,
        primary: { kind: 'external', label: '유튜브 보기' },
        note: 'expired',
        dimmed: true,
      };
    case 'failed':
      return {
        ...base,
        primary: { kind: 'action', label: '렌더 재시도', action: 'retryRender' },
        edit: EDIT_CONTINUE,
        download: false,
        showDuration: false,
      };
  }
}

/** 패널 안내 상자 문구 — 승인 대기는 시점마다 할 수 있는 일이 다르다 */
export function noteText(note: PanelNote, role: LibraryRole): string {
  if (note === 'expired') {
    return '원본 VOD가 만료되어 다시 편집할 수 없어요. 발행된 영상은 그대로 유지됩니다.';
  }
  return role === 'editor'
    ? '승인 대기 중에는 편집이 잠겨요. 수정이 필요하면 승인 대기함 › 내 요청에서 취소한 뒤 편집하세요.'
    : '승인 · 반려는 승인 대기함에서 처리해요. 여기서는 미리보기와 다운로드만 할 수 있어요.';
}

// ---------- 표기 ----------

/** 카드·미리보기의 길이. 렌더 실패는 길이를 모르므로 null — 「0:00」으로 지어내지 않는다 */
export function durationLabel(clip: LibraryClip, status: ClipStatus): string | null {
  if (status === 'failed' || clip.durationSec === null) return null;
  return formatUptime(clip.durationSec);
}

/** 메타의 「원본 보존」 — `원본 만료 D-58` · `원본 만료됨` · 기한을 모르면 `—` */
export function retentionLabel(dday: VodDday): string {
  switch (dday.kind) {
    case 'active':
      return `원본 만료 ${dday.label}`;
    case 'expired':
      return '원본 만료됨';
    case 'unknown':
      return '—';
  }
}

/** 카드 우상단 20u 원 안의 한 글자 — 내 것은 「나」, 남의 것은 이름 첫 글자 */
export function ownerInitial(owner: LibraryClip['owner']): string {
  if (owner.me) return '나';
  return Array.from(owner.name)[0] ?? '';
}

const DAY_MS = 24 * 60 * 60 * 1000;
const TIME_FORMAT = new Intl.DateTimeFormat('ko-KR', {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});
const DATE_FORMAT = new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' });

/** 지역 달력의 날 번호 — 「오늘」「어제」는 사람이 보는 달력 기준이다 */
function localDayIndex(date: Date): number {
  return Math.floor((date.getTime() - date.getTimezoneOffset() * 60 * 1000) / DAY_MS);
}

/** 반려 시각 → 「오늘 14:20」 · 「어제 21:32」 · 「8월 28일 21:32」 */
export function dayTimeLabel(iso: string, now: Date): string {
  const at = new Date(iso);
  const diff = localDayIndex(now) - localDayIndex(at);
  const time = TIME_FORMAT.format(at);
  if (diff === 0) return `오늘 ${time}`;
  if (diff === 1) return `어제 ${time}`;
  return `${DATE_FORMAT.format(at)} ${time}`;
}

/**
 * 카드 버튼의 접근 이름. 카드 안 배지·길이·이니셜은 aria-hidden이고 이름 하나가 순서를
 * 정한다 — 제목 · 상태 · 길이 · (남의 것이면) 편집자. 버튼 목록으로 훑는 사람에게 같은
 * 이름 여덟 개가 되지 않게 상태와 길이까지 이름에 넣는다(VodRow 선례).
 */
export function cardName(clip: LibraryClip, status: ClipStatus, duration: string | null): string {
  const parts = [clip.title.trim() || '제목 없는 편집본', STATUS_BADGE[status].label];
  if (duration) parts.push(`길이 ${duration}`);
  if (!clip.owner.me) parts.push(`편집자 ${clip.owner.name}`);
  return parts.join(' · ');
}
