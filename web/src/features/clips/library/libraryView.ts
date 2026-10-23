import { ddayFor, type VodDday } from '@/features/broadcast/vod/vodListView';
import { formatUptime } from '@/features/player/playerMath';
import {
  ClipApiError,
  liveUploadsOf,
  type LibraryEntry,
  type UploadSnapshot,
} from '@/api/clipEditor';
import {
  instantLabel,
  PRIVACY_CHANGE_NOTE,
  PRIVACY_LABEL,
  PRIVACY_VIEWERS,
  PUBLIC_NOTIFY_NOTE,
} from '@/features/clips/editor/uploadInfo';
import type { ClipStatus, LibraryClip } from './useLibraryMockState';

// 시안 1g 보관함의 표시 규칙 — 상태가 배지·주 동작·보조 줄·칩·정렬로 어떻게 펼쳐지는지를
// 렌더와 떼어 순수 함수로 둔다(vodListView 선례). 상태별 조합은 jsdom을 거치지
// 않고 표로 검사하는 편이 싸다.
//
// ⚠ 아래 Record 표들은 status 유니온이 늘면 타입으로 깨진다 — 화면이 조용히 빈 배지를 그리는
// 대신 빌드가 멈춘다(highlightCardView 선례). Partial로 풀지 말 것.

export type LibraryChip = 'all' | 'working' | 'ready' | 'published';
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

/**
 * 시안 1g 카드·패널 배지 + 진행 단계 넷(POK-291). 유튜브에 올라간 것은 「업로드됨」이다(전에는 「발행됨」, 칩 이름은
 * 그대로 둔다: 칩 이름은 2번 몫). 업로드됨과 업로드됨·원본 만료는 같은 배지다: 만료는 안내문이 말한다
 */
export const STATUS_BADGE: Record<ClipStatus, { tone: StatusTone; label: string }> = {
  editing: { tone: 'point', label: '편집 중' },
  rendering: { tone: 'point', label: '만드는 중' },
  ready: { tone: 'neutral', label: '업로드 대기' },
  uploading: { tone: 'point', label: '올리는 중' },
  checking: { tone: 'warning', label: '확인 필요' },
  uploadFailed: { tone: 'danger', label: '업로드 실패' },
  published: { tone: 'success', label: '업로드됨' },
  expired: { tone: 'success', label: '업로드됨' },
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
  published: '발행됨',
};

const CHIPS: LibraryChip[] = ['all', 'working', 'ready', 'published'];

/** 칩 행 — 넷이다(승인 게이트가 없어 반려됨 칩이 없다) */
export function chipsFor(): { value: LibraryChip; label: string }[] {
  return CHIPS.map((value) => ({
    value,
    label: CHIP_LABEL[value],
  }));
}

/**
 * 편집본 하나가 속하는 칩. 칩은 전체를 분할한다 — 모든 편집본이 정확히 한 칩에 들어가
 * 칩 수의 합이 전체와 같다.
 * 진행 단계(POK-291): 만드는 중·올리는 중은 작업 중, 업로드 실패·확인 필요는 사람 손이 필요해 업로드 대기다.
 */
export function chipOf(status: ClipStatus): Exclude<LibraryChip, 'all'> {
  switch (status) {
    case 'ready':
    case 'uploadFailed':
    case 'checking':
      return 'ready';
    case 'published':
    case 'expired':
      return 'published';
    case 'editing':
    case 'rendering':
    case 'uploading':
    case 'failed':
      return 'working';
  }
}

export function countByChip(clips: readonly LibraryClip[], now: Date): Record<LibraryChip, number> {
  const counts: Record<LibraryChip, number> = {
    all: clips.length,
    working: 0,
    ready: 0,
    published: 0,
  };
  for (const clip of clips) counts[chipOf(statusFor(clip, now))] += 1;
  return counts;
}

export function filterByChip(
  clips: readonly LibraryClip[],
  chip: LibraryChip,
  now: Date,
): LibraryClip[] {
  if (chip === 'all') return [...clips];
  return clips.filter((clip) => chipOf(statusFor(clip, now)) === chip);
}

// ---------- 검색 · 정렬 ----------

/**
 * 제목 부분 일치 — 공백을 다듬고 대소문자를 가리지 않는다. 빈 검색어는 전체다.
 *
 * 로캘 무관한 `toLowerCase`를 쓴다. `toLocaleLowerCase`는 터키어 로캘에서 `I`를 점 없는 `ı`로
 * 내려 이미 소문자인 「title」을 「I」로 검색하면 못 찾는다 — 제목은 로캘이 아니라 글자 그대로
 * 찾는 값이다.
 */
export function filterByQuery(clips: readonly LibraryClip[], query: string): LibraryClip[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [...clips];
  return clips.filter((clip) => clip.title.toLowerCase().includes(needle));
}

/**
 * ISO 시각을 파싱해 비교한다. 문자열을 사전순으로 견주면 오프셋 표기가 섞이는 순간 어긋난다 —
 * `2026-09-02T14:20:00+09:00`(=05:20Z)이 `2026-09-02T06:00:00Z`보다 뒤로 읽힌다. 목업은 지금
 * 전부 `+09:00`이지만 이 훅은 내부만 서버 값으로 갈아끼우는 것이 전제이고, 같은 파일의
 * `remainingMs`·`ddayFor`도 이미 파싱해서 견준다 — 한 파일에 두 규칙을 두지 않는다.
 */
function timeOf(iso: string): number {
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
}

const byTimeDesc = (a: string, b: string) => timeOf(b) - timeOf(a);

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
      return list.sort((a, b) => byTimeDesc(a.editedAt, b.editedAt));
    case 'created':
      return list.sort((a, b) => byTimeDesc(a.createdAt, b.createdAt));
    case 'expiry':
      return list.sort((a, b) => {
        const ra = remainingMs(a, now);
        const rb = remainingMs(b, now);
        if (ra === null && rb === null) return byTimeDesc(a.editedAt, b.editedAt);
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
      href: '/clips/editor';
      variant: 'solid';
    }
  /** href는 clip.youtubeUrl — 없으면 링크 대신 비활성 버튼을 그린다(LinkButton 규칙) */
  | { kind: 'external'; label: '유튜브 보기' }
  /** retryUpload는 실패한 업로드를 저장된 정보 그대로 다시 올린다(POK-291, 창 없이) */
  | { kind: 'action'; label: string; action: 'upload' | 'retryRender' | 'retryUpload' }
  /** 서버 일이 진행 중이라 누를 것이 없다: 만드는 중·올리는 중·확인 필요. 비활성 버튼으로 그린다 */
  | { kind: 'busy'; label: string };

export type PanelNote = 'expired' | 'checking';

export interface DetailView {
  badge: { tone: StatusTone; label: string };
  primary: PrimaryAction;
  /** 보조 줄의 편집 링크 — null이면 편집 잠금(원본 만료·편집 중) */
  edit: { label: '이어서 편집' | '새 버전으로 편집'; href: '/clips/editor' } | null;
  /** 렌더 실패는 받을 파일이 없다 */
  download: boolean;
  /**
   * 제목 편집 잠금. 진행 단계(만드는 중·올리는 중·확인 필요·업로드 실패)는 제목을 여기서 못 고치므로 잠근다 —
   * 잠갔다고 말해 놓고 고쳐지면 화면이 거짓말을 한다(ADR-044).
   * 원본 만료는 재편집만 막힐 뿐 이름은 바꿀 수 있으니 잠그지 않는다.
   */
  titleLocked: boolean;
  /**
   * 고친 제목이 저장되지 않고 이 화면에만 남는 초안인가(POK-254). 편집본에는 제목 칸이 없어 완성본에서 고친 제목은
   * 올릴 때 실리는 것이 전부다: 새로 고침하면 사라진다고 입력 칸 아래에 알린다
   */
  titleIsDraft: boolean;
  note: PanelNote | null;
  /** 렌더 실패는 길이를 모른다 */
  showDuration: boolean;
}

/**
 * 원본이 만료된 발행본은 카드가 70%로 가라앉는다(ADR-004). 흐림은 카드의 성질이라 상세 패널
 * 표(DetailView)가 아니라 여기서 정한다 — 표에 두면 아무도 읽지 않는 칸이 되고, 카드가 같은
 * 조건을 따로 적어 규칙이 두 곳으로 갈린다.
 */
export function isCardDimmed(status: ClipStatus): boolean {
  return status === 'expired';
}

/**
 * 앵커에 실어도 되는 주소만 통과시킨다. 서버가 준 문자열을 그대로 href에 넣으면 `javascript:`
 * 스킴이 우리 오리진에서 실행된다 — 채널 연동이 동의 URL을 파싱해 검증하는 것과 같은 이유다
 * (settings/channels/youtubeOAuth.ts `assertYoutubeConsentUrl`). 지금 목업 값은 안전하지만
 * 이 훅은 내부만 서버 값으로 갈아끼우는 것이 전제라, 그 교체가 이 자리를 열어 준다.
 */
export function safeExternalUrl(url: string | undefined): string | null {
  if (!url) return null;
  try {
    const { protocol } = new URL(url);
    return protocol === 'http:' || protocol === 'https:' ? url : null;
  } catch {
    return null;
  }
}

/** 상세 패널의 「이전 판으로 올린 영상」 한 줄(POK-334). href는 올림만, 그 밖에는 null(글자만) */
export interface PreviousUpload {
  id: number;
  versionLabel: string;
  text: string;
  href: string | null;
  /** 확인 필요라 사람이 결과를 적을 수 있다(POK-333) */
  checking: boolean;
}

const LIVE_UPLOAD_TEXT: Record<string, string> = {
  queued: '올리는 중',
  uploading: '올리는 중',
  checking: '확인 필요',
  uploaded: '업로드됨',
};

/**
 * 패널 위쪽(배지 · 안내 · 「유튜브 보기」)이 실제로 보이는 업로드. 서버 상태가 올리는 중 · 확인 필요 · 올림일 때만 latestClip의
 * 업로드이고, 그 밖(편집 중 · 만드는 중 · 완성 · 실패)이면 위쪽은 업로드를 말하지 않아 null이다. 올린 뒤 새 판을 저장만 한
 * 「편집 중」에서 latestClip은 옛 판 영상이지만 위쪽은 그 업로드를 안 보인다(PR #250 codex). 「유튜브 보기」와 「이전 판」
 * 목록이 이 판정 하나를 같이 쓴다: 따로 셈하면 둘이 갈려 업로드가 어디에도 안 보이는 자리가 생긴다.
 */
export function shownUploadOf(entry: LibraryEntry): UploadSnapshot | null {
  const shows =
    entry.status === 'uploading' || entry.status === 'checking' || entry.status === 'uploaded';
  return shows ? (entry.latestClip?.upload ?? null) : null;
}

/**
 * 이 편집본의 살아 있는 업로드 중 패널 위쪽이 보이는 것(shownUploadOf)을 뺀 나머지, 최근 것이 앞. 새 판 영상을 주문하거나
 * 새 판을 저장만 해도 옛 판의 업로드가 여기로 내려온다(POK-334). 서버 줄이 없으면(목업) 빈 목록이다.
 */
export function previousUploadsOf(entry: LibraryEntry | undefined): PreviousUpload[] {
  if (entry === undefined) return [];
  const shown = shownUploadOf(entry)?.id;
  return liveUploadsOf(entry)
    .filter((u) => u.id !== shown)
    .map((u) => ({
      id: u.id,
      versionLabel: `v${u.recipeVersion}`,
      text: `${u.title} · ${LIVE_UPLOAD_TEXT[u.status] ?? u.status}`,
      href:
        u.status === 'uploaded' && u.videoId
          ? safeExternalUrl(`https://youtu.be/${encodeURIComponent(u.videoId)}`)
          : null,
      checking: u.status === 'checking',
    }));
}

const EDIT_CONTINUE = { label: '이어서 편집', href: '/clips/editor' } as const;
const EDIT_NEW_VERSION = { label: '새 버전으로 편집', href: '/clips/editor' } as const;

/** 시안 1g ④ 상태별 상세 패널 액션 */
export function detailViewFor(status: ClipStatus): DetailView {
  const badge = STATUS_BADGE[status];
  const base = {
    badge,
    edit: null,
    download: true,
    titleLocked: false,
    titleIsDraft: false,
    note: null,
    showDuration: true,
  } satisfies Omit<DetailView, 'primary'>;

  switch (status) {
    case 'editing':
      return {
        ...base,
        primary: { kind: 'link', label: '이어서 편집', href: '/clips/editor', variant: 'solid' },
      };
    // 진행 단계(POK-291). 서버 일이 도는 동안은 누를 것이 없고, 제목은 업로드 정보에서 온 것이라 여기서 못 고친다
    case 'rendering':
      return {
        ...base,
        primary: { kind: 'busy', label: '영상 만드는 중' },
        edit: EDIT_CONTINUE,
        download: false,
        titleLocked: true,
      };
    case 'uploading':
      return {
        ...base,
        primary: { kind: 'busy', label: '유튜브에 올리는 중' },
        edit: EDIT_CONTINUE,
        titleLocked: true,
      };
    case 'checking':
      return {
        ...base,
        primary: { kind: 'busy', label: '업로드 확인 필요' },
        edit: EDIT_CONTINUE,
        titleLocked: true,
        note: 'checking',
      };
    case 'uploadFailed':
      // 저장된 정보(제목·설명·태그·공개 범위·썸네일)로 다시 올린다: 패널 제목을 고쳐도 실리지 않으니 잠근다
      return {
        ...base,
        primary: { kind: 'action', label: '다시 시도', action: 'retryUpload' },
        edit: EDIT_CONTINUE,
        titleLocked: true,
      };
    case 'ready':
      return {
        ...base,
        primary: { kind: 'action', label: '업로드', action: 'upload' },
        edit: EDIT_CONTINUE,
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

/**
 * 완성됐고 영상 만들기 창에서 고른 업로드 정보가 남았는데 업로드 줄이 없는가(자동 업로드가 안 붙었다: 업로드가 꺼져 있었거나
 * 서버 경합). 그러면 「업로드」는 제목만 싣는 옛 문이 아니라 다시 시도 문으로 그 판의 저장된 정보로 올린다(POK-291)
 */
export function uploadsFromIntent(entry: LibraryEntry): boolean {
  return (
    entry.status === 'rendered' && entry.uploadRequest != null && entry.latestClip?.upload == null
  );
}

/**
 * 서버 줄을 시안 규칙(detailViewFor) 위에 얹는다(POK-111). 진행 단계(만드는 중·올리는 중·확인 필요·업로드 실패)는 이제
 * 제 화면 상태가 있어 detailViewFor가 정한다(POK-291). 여기서는 서버 줄만의 제목 잠금·초안 여부를 얹는다.
 */
export function detailViewForClip(clip: LibraryClip, status: ClipStatus): DetailView {
  const view = detailViewFor(status);
  const entry = clip.entry;
  if (entry === undefined) return view;
  // 올린 영상의 제목은 유튜브에 있다: 여기서 고쳐도 저장할 곳이 없어 바뀐 척만 한다
  if (entry.status === 'uploaded') return { ...view, titleLocked: true };
  // 제목이 살아 있는 업로드에서 온 것이면(올린 뒤 새 판을 저장해 「편집 중」인 경우 포함) 고칠 곳이 없다:
  // 초안은 완성 편집본에만 남아 다음 읽기에 되돌아간다(PR #203 codex)
  const upload = entry.latestClip?.upload;
  // 저장된 정보로 올리는 길이면 패널 제목은 실리지 않는다: 고쳐도 버려지니 잠근다.
  // 편집 중·렌더 실패도 잠근다(POK-254): 편집본에는 제목 칸이 없어 고친 제목이 다음 읽기에 되돌아갔다. 그 제목은 영상 만들기
  // 창에서 정한다
  const titleLocked =
    view.titleLocked ||
    entry.status === 'editing' ||
    entry.status === 'failed' ||
    (upload != null && upload.status !== 'failed') ||
    uploadsFromIntent(entry);
  // 남는 것은 완성본뿐이다: 고친 제목은 올릴 때 실리는 초안이다
  const titleIsDraft = !titleLocked;
  return { ...view, titleLocked, titleIsDraft };
}

/** 패널 안내 상자 문구 */
export function noteText(note: PanelNote): string {
  if (note === 'checking') {
    // 공개 범위를 고를 수 있게 되어(POK-291) 「비공개 영상」이라 못박지 않는다
    return '유튜브에 올라갔는지 확인하지 못했어요. 두 번 올라가지 않게 다시 올리기를 막아 두었어요. 스트리머 채널의 유튜브 스튜디오에서 영상이 올라갔는지 확인한 뒤 아래 단추로 결과를 알려 주세요.';
  }
  return '원본 VOD가 만료되어 다시 편집할 수 없어요. 발행된 영상은 그대로 유지됩니다.';
}

// ---------- 유튜브 업로드 (POK-111) ----------

/**
 * 업로드 제목이 서버 규칙(clip 업로드 문: 앞뒤 공백 뺀 1~100 코드포인트, < > 금지)에 맞지 않는 까닭. 맞으면 null.
 * 서버도 같은 것을 400으로 거절하지만, 보내기 전에 말해야 패널에서 바로 고친다.
 */
export function uploadTitleProblem(title: string): string | null {
  const trimmed = title.trim();
  if (trimmed === '') return '유튜브 제목을 적어 주세요.';
  if (Array.from(trimmed).length > 100) return '유튜브 제목은 100자까지예요.';
  if (/[<>]/.test(trimmed)) return '유튜브 제목에는 < 와 > 를 쓸 수 없어요.';
  return null;
}

/** 업로드 주문이 거절된 사유. 모르는 사유는 서버 코드를 그대로 보인다(지어내지 않는다) */
/** 「확인 필요」 풀기(POK-333)가 거절된 사유를 사람 말로 */
export function resolveErrorMessage(e: unknown): string {
  if (e instanceof ClipApiError) {
    if (e.code === 'resolution_too_early') {
      return '업로드가 아직 이어지고 있을 수 있어요. 마지막 시도에서 30분이 지난 뒤 다시 눌러 주세요.';
    }
    if (e.code === 'upload_not_checking')
      return '이미 결과가 정해진 업로드예요. 목록을 다시 읽었어요.';
    if (e.status === 400 && e.field === 'videoId') {
      return '유튜브 영상 주소나 영상 번호를 확인해 주세요. youtu.be/… · watch?v=… · shorts/… 주소를 붙여 넣으면 돼요.';
    }
    if (e.status === 404) return '업로드를 찾을 수 없어요. 목록을 새로 고쳐 주세요.';
  }
  return uploadErrorMessage(e);
}

export function uploadErrorMessage(e: unknown): string {
  if (e instanceof ClipApiError) {
    if (e.status === 400 && e.field === 'title') return '유튜브 제목을 확인해 주세요.';
    // 출력(outputId)을 안 보내면 서버가 영상 출력 하나를 고르는데, 하나가 아니면(0개·여럿) 거절한다. 편집기는 세로 한 벌만 만든다
    if (e.status === 400 && e.field === 'outputId') {
      return '올릴 영상을 고를 수 없어요. 이 편집본은 영상 출력이 하나가 아니에요.';
    }
    if (e.code === 'clip_not_rendered') return '영상이 아직 완성되지 않았어요.';
    if (e.code === 'already_uploaded') {
      return '이 편집본의 같은 판이 이미 다른 영상으로 올라갔어요. 고친 뒤 다시 만들어 주세요.';
    }
    if (e.code === 'nothing_to_retry') return '다시 올릴 업로드가 없어요. 목록을 새로 고쳐 주세요.';
    if (e.status === 404) return '편집본을 찾을 수 없어요. 목록을 새로 고쳐 주세요.';
    if (e.code === 'upload_unavailable') {
      return '지금은 업로드를 받을 수 없어요. 잠시 뒤 다시 올려 주세요.';
    }
    if (e.code === 'authorization_unavailable') {
      return '권한 확인이 잠시 안 돼요. 잠시 뒤 다시 올려 주세요.';
    }
  }
  return e instanceof Error ? e.message : String(e);
}

/**
 * 연동 문제로 끝난 업로드 코드(업로드 일꾼이 `YOUTUBE_` + auth 사유로 적는다). 일시 실패(REFRESH_UNAVAILABLE)는
 * 일꾼이 다시 시도해 실패로 남지 않는다.
 */
const LINK_FAILURES = new Set(['YOUTUBE_NOT_LINKED', 'YOUTUBE_UNLINKED', 'YOUTUBE_BROKEN']);

/**
 * 지난 업로드가 실패한 편집본의 안내. 실패하면 서버가 편집본을 「완성」으로 돌려 다시 올릴 수 있게 하고, 사유는
 * 가장 최근 업로드에 남는다. 다시 올리는 중이거나 실패가 없으면 null.
 */
export function uploadFailureText(clip: LibraryClip): string | null {
  const entry = clip.entry;
  const upload = entry?.latestClip?.upload;
  if (entry?.status !== 'rendered' || upload?.status !== 'failed') return null;
  const code = upload.error?.code ?? 'UNKNOWN';
  if (LINK_FAILURES.has(code)) {
    return '스트리머의 유튜브 채널 연동이 없거나 끊겨 올리지 못했어요. 스트리머가 설정 › 채널 연동에서 다시 연결한 뒤 올려 주세요.';
  }
  if (code === 'SWEPT') {
    // 주문줄에서 거듭 실패해 닫혔다. 이 실패는 주소를 받기 전에만 생긴다(주소가 있으면 SWEPT_AFTER_SESSION 확인 필요): 영상은 없다
    return '올리는 중에 문제가 거듭 생겨 유튜브에 올리지 못했어요. 영상은 올라가지 않았으니 다시 올려 주세요.';
  }
  if (code === 'QUOTA_EXCEEDED') {
    return '오늘 유튜브 업로드 한도를 다 써서 올리지 못했어요. 내일 다시 올려 주세요.';
  }
  if (code === 'YOUTUBE_REJECTED') {
    const why = upload.error?.message ? `(${upload.error.message})` : '';
    // 「다시 시도」는 실패한 줄의 제목·설명·태그를 그대로 다시 보내고 보관함 제목은 잠겨 있다. 고칠 곳은 편집기 창이다
    return `유튜브가 영상을 받지 않았어요${why}. 같은 정보로 다시 시도하면 또 거절될 수 있어요. 「이어서 편집」에서 영상 만들기 창을 열어 제목·태그를 고친 뒤 다시 올려 주세요.`;
  }
  return `유튜브에 올리지 못했어요(${code}). 다시 올려 주세요.`;
}

/** 썸네일만 못 붙인 까닭(업로드 일꾼의 썸네일 오류 코드, POK-291). 모르는 코드는 그대로 보인다 */
const THUMBNAIL_REASON: Record<string, string> = {
  THUMBNAIL_FORBIDDEN: '채널 전화 인증이 필요해요.',
  THUMBNAIL_QUOTA_EXCEEDED: '오늘 유튜브 한도를 다 썼어요.',
  THUMBNAIL_RATE_LIMITED: '유튜브가 잠시 요청을 막았어요.',
  THUMBNAIL_INVALID_IMAGE: '유튜브가 이미지를 받지 않았어요. 크기와 형식을 확인해 주세요.',
  THUMBNAIL_UNAUTHORIZED: '유튜브 채널 연동을 확인해 주세요.',
  THUMBNAIL_NO_TOKEN: '유튜브 채널 연동을 확인해 주세요.',
  THUMBNAIL_NOT_REPORTED: '썸네일 결과를 받지 못했어요.',
};

/**
 * 영상은 올라갔는데 썸네일만 실패한 편집본의 안내(POK-291). 영상은 그대로 두고, 썸네일은 유튜브 스튜디오에서 직접 바꾸게
 * 한다(다시 올리면 영상이 두 번 올라간다). 그 밖이면 null.
 */
export function thumbnailFailureText(clip: LibraryClip): string | null {
  const entry = clip.entry;
  const thumbnail = entry?.latestClip?.upload?.thumbnail;
  if (entry?.status !== 'uploaded' || thumbnail?.status !== 'failed') return null;
  const code = thumbnail.errorCode ?? 'UNKNOWN';
  // 재배달이라 붙었는지 모른다: 못 붙였다고 하면 이미 붙은 썸네일을 또 바꾸게 만든다
  if (code === 'THUMBNAIL_UNCONFIRMED') {
    return '영상은 올라갔어요. 썸네일이 붙었는지 확인하지 못했어요. 스트리머 채널의 유튜브 스튜디오에서 확인해 주세요.';
  }
  const reason = THUMBNAIL_REASON[code] ?? `(${code})`;
  return `영상은 올라갔고 썸네일만 못 붙였어요. ${reason} 스트리머 채널의 유튜브 스튜디오에서 직접 바꿀 수 있어요.`;
}

/**
 * 공개 범위 안내(POK-291). 일부 공개·공개는 고른 그대로 올라가 남이 본다(uploadInfo.ts PRIVACY_VIEWERS). 편집본 상태마다
 * 그 범위로 올라가게 하는 길(자동 · 「업로드」 · 「다시 시도」 · 「렌더 재시도」)과 누가 보는지를 말한다. 「올렸어요」는 올릴 때의
 * 범위다: 스트리머가 스튜디오에서 바꾸면 모른다(services README 알려진 한계). 편집 중이면 latestClip이 옛 판이라 말하지 않는다
 * (그 판의 다시 시도 단추도 없고, 새 판은 창에서 다시 고른다). 비공개거나 고른 것이 없으면 null.
 */
export function privacyNoteText(clip: LibraryClip, now: Date): string | null {
  const entry = clip.entry;
  if (entry === undefined) return null;
  const upload = entry.latestClip?.upload;
  const privacy =
    upload != null ? upload.privacyStatus : (entry.uploadRequest?.privacyStatus ?? undefined);
  // 예약 공개(POK-381): 비공개로 올리고 그 시각에 유튜브가 공개로 바꾼다. 시각은 공개 범위와 같은 곳에서 읽는다 — 업로드 줄이
  // 생기기 전(렌더 중)엔 의도의 시각이다(PR #301 2차 리뷰). 편집 중이면 latestClip이 옛 판이라 아래 switch처럼 말하지 않는다
  // (새 판엔 예약이 없다, PR #301 리뷰)
  const publishAt =
    upload != null ? (upload.publishAt ?? null) : (entry.uploadRequest?.publishAt ?? null);
  if (privacy === 'private' && publishAt !== null && entry.status !== 'editing') {
    const at = new Date(publishAt);
    const when = instantLabel(at);
    const passed = at.getTime() <= now.getTime();
    switch (entry.status) {
      case 'failed':
        // 렌더 실패: 「렌더 재시도」가 성공하면 그 판의 정보(예약 시각 포함)로 이어서 올린다. 시각이 지났으면 바로 공개된다
        // — 렌더 실패를 빼면 이 경고가 아무 데도 안 떴다(PR #301 3차 리뷰)
        return passed
          ? `「예약」 시각(${when})이 지나 「렌더 재시도」가 끝나면 바로 공개돼요. ${PUBLIC_NOTIFY_NOTE}`
          : `「렌더 재시도」가 끝나면 올라가고 「예약」 ${when}에 공개로 바뀌어요. ${PUBLIC_NOTIFY_NOTE}`;
      case 'uploaded':
        return passed
          ? `「예약」 ${when}에 공개되도록 올렸어요. ${PRIVACY_CHANGE_NOTE}`
          : `「예약」 ${when}에 공개되도록 올렸어요. 그 전까지는 비공개예요. ${PRIVACY_CHANGE_NOTE}`;
      case 'checking':
        // 올라갔는지 모른다(채널에서 보라는 안내가 따로 뜬다): 「올라가면」으로 단정하지 않는다
        return passed
          ? `「예약」 ${when} 공개로 골랐어요. 올라갔다면 이미 공개됐어요.`
          : `「예약」 ${when} 공개로 골랐어요. 올라갔다면 그 시각에 공개로 바뀌어요.`;
      default:
        // 아직 안 올라갔다. 그 시각이 지났으면 유튜브가 올리자마자 공개한다(다시 시도 · 업로드도 저장된 시각을 그대로 쓴다)
        return passed
          ? `「예약」 시각(${when})이 지나 올라가면 바로 공개돼요. ${PUBLIC_NOTIFY_NOTE}`
          : `「예약」 올라가면 ${when}에 공개로 바뀌어요. ${PUBLIC_NOTIFY_NOTE}`;
    }
  }
  if (privacy === undefined || privacy === 'private') return null;
  const label = `「${PRIVACY_LABEL[privacy]}」`;
  const viewers = PRIVACY_VIEWERS[privacy];
  // 아직 안 올라갔다: 올라가면 누가 보나, 공개면 구독자 알림까지(창을 거치지 않는 단추도 같은 경고를 본다)
  const reach = `올라가면 ${viewers} 볼 수 있어요.${privacy === 'public' ? ` ${PUBLIC_NOTIFY_NOTE}` : ''}`;
  switch (entry.status) {
    case 'uploaded':
      return `${label}(${viewers} 볼 수 있는 범위)로 올렸어요. ${PRIVACY_CHANGE_NOTE}`;
    case 'checking':
      // 채널에서 올라갔는지 보라는 안내(noteText)가 따로 뜬다
      return `${label}로 골랐어요. 올라갔다면 ${viewers} 볼 수 있어요.`;
    case 'rendering':
    case 'uploading':
      // 렌더 성공이 그 판의 업로드 정보로 스스로 올린다
      return `${label}로 올라가요. ${reach}`;
    case 'rendered':
      // 업로드 실패면 「다시 시도」가 실패한 줄의 범위를, 자동 업로드를 건너뛰었으면 「업로드」가 그 판의 정보를 그대로 쓴다
      return upload?.status === 'failed'
        ? `「다시 시도」를 누르면 ${label}로 올라가요. ${reach}`
        : `「업로드」를 누르면 ${label}로 올라가요. ${reach}`;
    case 'failed':
      // 렌더 실패: 렌더를 다시 해 성공하면 그 판의 정보로 이어서 올린다
      return `「렌더 재시도」가 끝나면 ${label}로 올라가요. ${reach}`;
    default:
      return null;
  }
}

// ---------- 표기 ----------

/** 카드·미리보기의 길이. 렌더 실패는 길이를 모르므로 null — 「0:00」으로 지어내지 않는다 */
export function durationLabel(clip: LibraryClip, status: ClipStatus): string | null {
  // 유한한 수만 넘긴다 — formatUptime의 Math.round는 NaN·Infinity를 그대로 통과시켜
  // 「NaN:NaN」이 카드 이름에 찍힌다(vodListView.durationLabel과 같은 가드).
  if (status === 'failed' || clip.durationSec === null || !Number.isFinite(clip.durationSec)) {
    return null;
  }
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

/**
 * 화면에 그릴 제목. 빈 제목의 대체 문구를 한 곳에 둔다 — 카드가 그리는 글자와 접근 이름이
 * 각자 적으면 문구를 고칠 때 보이는 이름과 읽히는 이름이 갈린다.
 */
export function displayTitle(clip: LibraryClip): string {
  return clip.title.trim() || '제목 없는 편집본';
}

/** 카드 우상단 20u 원 안의 한 글자 — 내 것은 「나」, 남의 것은 이름 첫 글자 */
export function ownerInitial(owner: LibraryClip['owner']): string {
  if (owner.me) return '나';
  return Array.from(owner.name)[0] ?? '';
}

/**
 * 카드 버튼의 접근 이름. 카드 안 배지·길이·이니셜은 aria-hidden이고 이름 하나가 순서를
 * 정한다 — 제목 · 상태 · 길이 · (남의 것이면) 편집자. 버튼 목록으로 훑는 사람에게 같은
 * 이름 여덟 개가 되지 않게 상태와 길이까지 이름에 넣는다(VodRow 선례).
 */
export function cardName(clip: LibraryClip, status: ClipStatus, duration: string | null): string {
  const parts = [displayTitle(clip), STATUS_BADGE[status].label];
  if (duration) parts.push(`길이 ${duration}`);
  if (!clip.owner.me) parts.push(`편집자 ${clip.owner.name}`);
  return parts.join(' · ');
}
