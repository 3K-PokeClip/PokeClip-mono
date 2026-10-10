import { ddayFor, type VodDday } from '@/features/broadcast/vod/vodListView';
import { formatUptime } from '@/features/player/playerMath';
import { ClipApiError, type LibraryEntry } from '@/api/clipEditor';
import { PRIVACY_LABEL, PRIVACY_NOTE } from '@/features/clips/editor/uploadInfo';
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
  pending: { tone: 'warning', label: '승인 대기' },
  rejected: { tone: 'danger', label: '반려됨' },
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
 * 진행 단계(POK-291): 만드는 중·올리는 중은 작업 중, 업로드 실패·확인 필요는 사람 손이 필요해 업로드 대기다.
 */
export function chipOf(status: ClipStatus, role: LibraryRole): Exclude<LibraryChip, 'all'> {
  switch (status) {
    case 'ready':
    case 'uploadFailed':
    case 'checking':
      return 'ready';
    case 'published':
    case 'expired':
      return 'published';
    case 'rejected':
      return role === 'editor' ? 'rejected' : 'working';
    case 'editing':
    case 'rendering':
    case 'uploading':
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
      href: '/clips/editor' | '/clips/approvals';
      /** 승인 대기의 「이동만」은 soft — 여기서 무언가를 확정하는 버튼이 아니다(시안 1g ④) */
      variant: 'solid' | 'soft';
    }
  /** href는 clip.youtubeUrl — 없으면 링크 대신 비활성 버튼을 그린다(LinkButton 규칙) */
  | { kind: 'external'; label: '유튜브 보기' }
  /** retryUpload는 실패한 업로드를 저장된 정보 그대로 다시 올린다(POK-291, 창 없이) */
  | { kind: 'action'; label: string; action: 'upload' | 'retryRender' | 'retryUpload' }
  /** 서버 일이 진행 중이라 누를 것이 없다: 만드는 중·올리는 중·확인 필요. 비활성 버튼으로 그린다 */
  | { kind: 'busy'; label: string };

export type PanelNote = 'expired' | 'pending' | 'checking';

export interface DetailView {
  badge: { tone: StatusTone; label: string };
  primary: PrimaryAction;
  /** 보조 줄의 편집 링크 — null이면 편집 잠금(승인 대기·반려·원본 만료·편집 중) */
  edit: { label: '이어서 편집' | '새 버전으로 편집'; href: '/clips/editor' } | null;
  /** 렌더 실패는 받을 파일이 없다 */
  download: boolean;
  /**
   * 제목 편집 잠금. 승인 대기는 안내문으로 「미리보기와 다운로드만」·「편집이 잠겨요」라고
   * 말하므로 제목도 잠근다 — 잠갔다고 말해 놓고 고쳐지면 화면이 거짓말을 한다(ADR-044).
   * 원본 만료·반려됨은 재편집만 막힐 뿐 이름은 바꿀 수 있으니 잠그지 않는다.
   */
  titleLocked: boolean;
  note: PanelNote | null;
  showRejection: boolean;
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

const EDIT_CONTINUE = { label: '이어서 편집', href: '/clips/editor' } as const;
const EDIT_NEW_VERSION = { label: '새 버전으로 편집', href: '/clips/editor' } as const;

/** 시안 1g ④ 상태별 상세 패널 액션 7종. 시점이 가르는 칸은 업로드 대기·승인 대기의 라벨뿐이다 */
export function detailViewFor(status: ClipStatus, role: LibraryRole): DetailView {
  const badge = STATUS_BADGE[status];
  const base = {
    badge,
    edit: null,
    download: true,
    titleLocked: false,
    note: null,
    showRejection: false,
    showDuration: true,
  } satisfies Omit<DetailView, 'primary'>;
  const editor = role === 'editor';

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
        titleLocked: true,
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
 * 제 화면 상태가 있어 detailViewFor가 정한다(POK-291). 서버 편집본은 승인 단계가 없어(편집자도 바로 올린다)
 * 「업로드 요청」이라 쓰면 거짓이 된다. 목업 줄은 시안 규칙 그대로 둔다.
 */
export function detailViewForClip(
  clip: LibraryClip,
  status: ClipStatus,
  role: LibraryRole,
): DetailView {
  const view = detailViewFor(status, role);
  const entry = clip.entry;
  if (entry === undefined) return view;
  // 올린 영상의 제목은 유튜브에 있다: 여기서 고쳐도 저장할 곳이 없어 바뀐 척만 한다
  if (entry.status === 'uploaded') return { ...view, titleLocked: true };
  // 제목이 살아 있는 업로드에서 온 것이면(올린 뒤 새 판을 저장해 「편집 중」인 경우 포함) 고칠 곳이 없다:
  // 초안은 완성 편집본에만 남아 다음 읽기에 되돌아간다(PR #203 codex)
  const upload = entry.latestClip?.upload;
  // 저장된 정보로 올리는 길이면 패널 제목은 실리지 않는다: 고쳐도 버려지니 잠근다
  const titleLocked =
    view.titleLocked || (upload != null && upload.status !== 'failed') || uploadsFromIntent(entry);
  return view.primary.kind === 'action' && view.primary.action === 'upload'
    ? { ...view, titleLocked, primary: { ...view.primary, label: '업로드' } }
    : { ...view, titleLocked };
}

/** 패널 안내 상자 문구 — 승인 대기는 시점마다 할 수 있는 일이 다르다 */
export function noteText(note: PanelNote, role: LibraryRole): string {
  if (note === 'checking') {
    // 공개 범위를 고를 수 있게 되어(POK-291) 「비공개 영상」이라 못박지 않는다
    return '유튜브에 올라갔는지 확인하지 못했어요. 두 번 올라가지 않게 다시 올리기를 막아 두었어요. 스트리머 채널의 유튜브 스튜디오에서 영상이 올라갔는지 확인해 주세요.';
  }
  if (note === 'expired') {
    return '원본 VOD가 만료되어 다시 편집할 수 없어요. 발행된 영상은 그대로 유지됩니다.';
  }
  return role === 'editor'
    ? '승인 대기 중에는 편집이 잠겨요. 수정이 필요하면 승인 대기함 › 내 요청에서 취소한 뒤 편집하세요.'
    : '승인 · 반려는 승인 대기함에서 처리해요. 여기서는 미리보기와 다운로드만 할 수 있어요.';
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
    return '영상은 올라갔어요. 썸네일이 붙었는지 확인하지 못했어요. 유튜브 스튜디오에서 확인해 주세요.';
  }
  const reason = THUMBNAIL_REASON[code] ?? `(${code})`;
  return `영상은 올라갔고 썸네일만 못 붙였어요. ${reason} 유튜브 스튜디오에서 직접 바꿀 수 있어요.`;
}

/**
 * 공개 범위 안내(POK-291). 일부 공개·공개는 고른 그대로 올라가 남이 볼 수 있다(2026-10-11 실측, 감사 전 잠금이 안 걸렸다):
 * 「업로드됨」 줄이 누구에게 보이는지와 바꾸는 곳을 말한다. 비공개거나 고른 것이 없으면 null.
 */
export function privacyNoteText(clip: LibraryClip): string | null {
  const entry = clip.entry;
  if (entry === undefined) return null;
  const upload = entry.latestClip?.upload;
  const privacy =
    upload != null ? upload.privacyStatus : (entry.uploadRequest?.privacyStatus ?? undefined);
  if (privacy === undefined || privacy === 'private') return null;
  return `공개 범위는 「${PRIVACY_LABEL[privacy]}」로 골랐어요. ${PRIVACY_NOTE}`;
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

/**
 * 반려 시각 → 「오늘 14:20」 · 「어제 21:32」 · 「8월 28일 21:32」.
 *
 * 읽을 수 없는 값은 `—`로 떨어뜨린다 — `Intl.DateTimeFormat.format`은 Invalid Date에
 * RangeError를 던지므로, 걸러 두지 않으면 서버가 빈 문자열 하나만 보내도 그 카드를 여는 순간
 * 보관함 전체가 에러 바운더리로 떨어진다(같은 파일 timeOf·remainingMs·durationLabel의 가드).
 */
export function dayTimeLabel(iso: string, now: Date): string {
  const at = new Date(iso);
  if (!Number.isFinite(at.getTime())) return '—';
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
  const parts = [displayTitle(clip), STATUS_BADGE[status].label];
  if (duration) parts.push(`길이 ${duration}`);
  if (!clip.owner.me) parts.push(`편집자 ${clip.owner.name}`);
  return parts.join(' · ');
}
