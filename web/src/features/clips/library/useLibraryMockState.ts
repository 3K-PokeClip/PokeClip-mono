'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useToast } from '@/ui';
import {
  fetchLibraryAll,
  fetchMeLoose,
  requestFileAccess,
  requestRender,
  requestUpload,
  ClipApiError,
  type LibraryEntry,
  type UploadSnapshot,
} from '@/api/clipEditor';
import {
  countByChip,
  filterByChip,
  filterByQuery,
  sortClips,
  statusFor,
  uploadErrorMessage,
  uploadTitleProblem,
  type LibraryChip,
  type LibrarySort,
} from './libraryView';

// 시안 1g 보관함의 상태 (POK-251 — 실제 값).
//
// 목록은 clip `GET /api/clip/library`(POK-243)에서 온다 — 내가 볼 수 있는 방송들의 편집본 전부와
// 편집본마다의 상태(editing·rendering·rendered·failed + 업로드 셋)·가장 최근 영상. 화면 타입 LibraryClip은
// 시안이 그리는 칸이라 서버 줄을 여기서 옮긴다(toLibraryClip). 서버가 아직 안 주는 칸은 지어내지
// 않는다 — 제목은 업로드 제목이 없으면 「편집본 #번호」, 만든 사람은 회원 번호.
//
// 화면 상태 7종 중 서버가 주는 것은 넷이다(편집 중·업로드 대기·발행됨·실패). 승인 대기·반려는 승인 게이트가 없어
// 쓰지 않는다. 「만드는 중」·「올리는 중」·「확인 필요」는 화면 상태에 없어 보조 줄과 패널 주 동작이 말한다(POK-111).
//
// 동작은 줄마다 갈린다: 서버에서 온 줄(`entry`가 있다)은 실제 문으로 가고, 시험·스토리북이 주입한 목업 줄은
// 상태 전이만 흉내 낸다(예전 목업 그대로 — 화면 흐름을 시험이 계속 잴 수 있게).
//
// 시점(role)은 훅 값이다 — 시안의 스트리머/편집자 토글은 핸드오프용이라 제품 UI에 두지 않는다.

/** 화면 모드(ADR-032) — 계정 속성이 아니다 */
export type LibraryRole = 'streamer' | 'editor';

/** 시안 1g ④의 7종. expired는 발행됨 중 원본 VOD 보관이 지난 것이다(ADR-004 60일) */
export type ClipStatus =
  'editing' | 'ready' | 'pending' | 'rejected' | 'published' | 'expired' | 'failed';

export interface LibraryClip {
  id: string;
  title: string;
  status: ClipStatus;
  /** 렌더 실패면 null — 길이를 모른다 */
  durationSec: number | null;
  /** 카드 우상단 이니셜과 접근 이름의 「편집자 ○○」 재료 */
  owner: { name: string; me: boolean };
  /** 「8월 31일 라이브」 — 원본 방송 표기 */
  sourceLabel: string;
  /** 원본 VOD 보관 만료 ISO(종료 + 60일). 지났으면 ddayFor가 expired를 준다 */
  sourceExpiresAt: string | null;
  templateLabel: string;
  subtitleLabel: string;
  /** ISO — 생성순 */
  createdAt: string;
  /** ISO — 최근 편집순 */
  editedAt: string;
  /** 반려됨만 — 사유와 반려 시각 */
  rejection?: { reason: string; at: string };
  /** 발행됨·원본 만료만. 목업 업로드로 발행된 것은 갈 곳이 없어 비워 둔다 */
  youtubeUrl?: string;
  /** 편집기로 다시 여는 주소 — 저장된 편집본을 ?recipe= 로 연다. 시험 주입에는 없다 */
  editHref?: string;
  /** 서버 줄 그대로 — 상세 패널이 영상 상태·파일 키를 보여 줄 때 쓴다. 시험 주입에는 없다 */
  entry?: LibraryEntry;
}

function sourceLabelOf(entry: LibraryEntry): string {
  const at = entry.broadcast.startedAt ?? entry.createdAt;
  const d = new Date(at);
  return Number.isNaN(d.getTime())
    ? `방송 ${entry.streamId}`
    : `${d.getMonth() + 1}월 ${d.getDate()}일 ${entry.broadcast.status === 'live' ? '라이브' : '방송'}`;
}

function subtitleLabelOf(entry: LibraryEntry): string {
  const clip = entry.latestClip;
  switch (entry.status) {
    case 'editing':
      return clip === null
        ? '아직 영상 안 만듦'
        : `v${clip.recipeVersion} 영상 있음 · 지금은 v${entry.recipeVersion}`;
    case 'rendering':
      return clip?.status === 'rendering'
        ? `영상 만드는 중 ${clip.progress?.percent ?? 0}%`
        : '영상 주문됨 · 차례 기다리는 중';
    case 'rendered':
      return `완성 · 파일 ${clip?.outputs?.length ?? 0}개`;
    case 'failed':
      return `렌더 실패 · ${clip?.error?.code ?? '?'}`;
    case 'uploading':
      return '유튜브에 올리는 중';
    case 'checking':
      return '유튜브에 올라갔는지 확인이 필요해요';
    case 'uploaded':
      return '유튜브에 올림';
  }
}

function youtubeUrlOf(entry: LibraryEntry): string | undefined {
  const videoId = entry.latestClip?.upload?.videoId;
  return entry.status === 'uploaded' && videoId ? `https://youtu.be/${videoId}` : undefined;
}

/**
 * 서버 줄 → 화면 칸. 상태 일곱을 화면 상태로 접는다: rendering 은 화면에 칸이 없어 editing(보조 줄이 말한다),
 * 올리는 중·확인 중은 완성(ready) 위에 보조 줄로, 올림은 발행됨이다.
 */
const SCREEN_STATUS: Record<LibraryEntry['status'], ClipStatus> = {
  editing: 'editing',
  rendering: 'editing',
  rendered: 'ready',
  failed: 'failed',
  uploading: 'ready',
  checking: 'ready',
  uploaded: 'published',
};

export function toLibraryClip(entry: LibraryEntry, meId: string | null): LibraryClip {
  const status: ClipStatus = SCREEN_STATUS[entry.status];
  const mine = meId !== null && String(entry.creatorId) === meId;
  return {
    id: String(entry.recipeId),
    // 제목 칸이 서버에 있는 곳은 유튜브 업로드뿐이다(POK-220) — 올린 적이 있으면 그 제목을 보인다
    title: entry.latestClip?.upload?.title ?? `편집본 #${entry.recipeId}`,
    status,
    durationSec: entry.cut ? Math.round((entry.cut.outAtMs - entry.cut.inAtMs) / 1000) : null,
    owner: { name: mine ? '나' : `편집자 ${entry.creatorId}`, me: mine },
    sourceLabel: sourceLabelOf(entry),
    sourceExpiresAt: entry.broadcast.vodExpiresAt,
    templateLabel: '세로 쇼츠 1벌',
    subtitleLabel: subtitleLabelOf(entry),
    createdAt: entry.createdAt,
    editedAt: entry.updatedAt,
    youtubeUrl: youtubeUrlOf(entry),
    editHref: `/clips/editor/studio?recipe=${entry.recipeId}`,
    entry,
  };
}

/** 테스트 주입 — 빈 상태·시점·경계 케이스를 화면 밖에서 만든다 (VodListOptions 선례) */
export interface LibraryOptions {
  role?: LibraryRole;
  clips?: LibraryClip[];
  /** 처음부터 열어 둘 편집본 — 기본은 미선택 */
  selectedId?: string | null;
  /** D-day·「어제」 계산의 기준 시각 — 시험이 얼린다. 없으면 지금 */
  now?: Date;
}

export interface LibraryMockState {
  /** 모든 D-day·「어제」 계산의 기준 시각 */
  now: Date;
  /** 첫 응답 전 true — 빈 목록을 「없다」로 그리지 않기 위해 */
  loading: boolean;
  /** 목록을 못 읽었을 때의 사유 */
  error: string | null;
  refresh: () => void;
  role: LibraryRole;
  /** 검색 → 칩 → 정렬을 거친 목록 */
  clips: LibraryClip[];
  /** 필터와 무관한 전체 수 — 「아직 없다」와 「조건에 없다」를 가른다 */
  totalCount: number;
  /** 칩별 수 — 검색어와 무관하게 전체에서 센다(칩은 재고이고 검색은 그 위의 돋보기다) */
  counts: Record<LibraryChip, number>;
  /** 스트리머 배너의 「승인 대기 N건」 */
  pendingCount: number;
  chip: LibraryChip;
  setChip: (chip: LibraryChip) => void;
  query: string;
  setQuery: (query: string) => void;
  sort: LibrarySort;
  setSort: (sort: LibrarySort) => void;
  selectedId: string | null;
  selectedClip: LibraryClip | null;
  /** 같은 id를 다시 주면 해제 — 선택한 썸네일을 다시 누르면 패널이 닫힌다(시안 1g) */
  select: (id: string) => void;
  deselect: () => void;
  /** 제목 인라인 편집 — 입력마다 저장한다(시안 1g ③) */
  renameClip: (id: string, title: string) => void;
  /**
   * 서버 줄: 패널의 제목으로 유튜브 업로드를 주문한다(POK-111, 스트리머 채널에 비공개). 목업 줄: 업로드 대기 →
   * 발행됨(스트리머) / 승인 대기(편집자). 그 밖의 상태는 무시
   */
  upload: (id: string) => void;
  /** 업로드 주문을 보내고 답을 기다리는 편집본 — 두 번 눌러 두 번 보내지 않게 패널이 단추를 잠근다 */
  sendingIds: ReadonlySet<string>;
  /** 렌더 실패 → 업로드 대기. 결과를 토스트로 흉내 내지 않는다 */
  retryRender: (id: string) => void;
  /** 서버 줄: 완성 영상 파일을 내려받는다(60분짜리 서명 주소). 목업 줄은 받을 파일이 없어 「준비 중」만 알린다 */
  download: (id: string) => void;
  /** 미리보기로 틀 완성 영상 주소. 영상이 없거나 못 받으면 null(사유는 토스트) */
  previewUrl: (id: string) => Promise<string | null>;
  /** 목록에서 뺀다. 선택 중이면 해제 — 확인은 화면(ConfirmDialog)이 먼저 받는다 */
  remove: (id: string) => void;
}

export function useLibraryMockState(options: LibraryOptions = {}): LibraryMockState {
  const { toast } = useToast();
  const role = options.role ?? 'streamer';
  const [clips, setClips] = useState<LibraryClip[]>(() => options.clips ?? []);
  const [loading, setLoading] = useState(options.clips === undefined);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((t) => t + 1), []);
  // 「지금」은 마운트 뒤 클라이언트 시계 — 서버 렌더와 어긋나지 않게 초기값은 고정.
  const [clock, setClock] = useState(() => new Date(0));
  const now = options.now ?? clock;
  const [chip, setChip] = useState<LibraryChip>('all');
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<LibrarySort>('edited');
  const [selectedId, setSelectedId] = useState<string | null>(options.selectedId ?? null);
  const [sendingIds, setSendingIds] = useState<ReadonlySet<string>>(() => new Set());
  // 상태 갱신은 다음 렌더에야 보여서 같은 틱의 두 번째 클릭을 못 막는다 — 보내는 중 표시는 ref가 정본이다
  const sending = useRef(new Set<string>());
  // 패널에서 고친 제목(업로드 제목의 초안). 다시 읽기(10초 폴링)가 서버 줄로 덮어도 남아야 한다
  const titleDrafts = useRef(new Map<string, string>());
  const meIdRef = useRef<string | null>(null);
  // 목록 읽기 세대. 업로드 답을 반영할 때 올려서, 그 전에 떠난 읽기가 늦게 와 「올리는 중」을 옛 줄로 덮지 못하게 한다
  const listGeneration = useRef(0);

  useEffect(() => {
    setClock(new Date());
    if (options.clips !== undefined) return undefined;
    let alive = true;
    const generation = listGeneration.current;
    (async () => {
      try {
        const [entries, me] = await Promise.all([fetchLibraryAll(), fetchMeLoose()]);
        if (!alive || generation !== listGeneration.current) return;
        const meId = me === null ? null : String(me.id);
        meIdRef.current = meId;
        setClips(
          entries.map((entry) => withDraft(toLibraryClip(entry, meId), titleDrafts.current)),
        );
        setError(null);
      } catch (e) {
        if (!alive) return;
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [options.clips, tick]);

  // 만드는 중·올리는 중인 편집본이 있으면 10초마다 다시 읽는다 — 일꾼의 보고가 상태를 바꾼다.
  // 확인 중(checking)은 사람이 채널을 봐야 풀리므로 기다리지 않는다
  const anyInProgress = clips.some(
    (clip) => clip.entry?.status === 'rendering' || clip.entry?.status === 'uploading',
  );
  useEffect(() => {
    if (!anyInProgress) return undefined;
    const t = window.setInterval(refresh, 10_000);
    return () => window.clearInterval(t);
  }, [anyInProgress, refresh]);

  const select = useCallback((id: string) => {
    setSelectedId((prev) => (prev === id ? null : id));
  }, []);
  const deselect = useCallback(() => setSelectedId(null), []);

  // 편집본에는 제목 칸이 없다(계약6). 여기서 고친 제목은 업로드 제목의 초안이고, 올리면 업로드 줄(POK-220)에 남는다.
  // 올리기 전에 새로고침하면 돌아온다.
  const renameClip = useCallback((id: string, title: string) => {
    titleDrafts.current.set(id, title);
    setClips((prev) => prev.map((clip) => (clip.id === id ? { ...clip, title } : clip)));
  }, []);

  const patch = useCallback((id: string, update: (clip: LibraryClip) => LibraryClip) => {
    setClips((prev) => prev.map((clip) => (clip.id === id ? update(clip) : clip)));
  }, []);

  const setSendingFlag = useCallback((id: string, on: boolean) => {
    if (on) sending.current.add(id);
    else sending.current.delete(id);
    setSendingIds(new Set(sending.current));
  }, []);

  // 서버 줄은 가장 최근 영상을 패널 제목으로 올린다. 서버가 같은 영상·출력의 살아 있는 업로드를 돌려주므로(200)
  // 겹쳐 눌러도 두 번 올라가지 않지만, 보내는 중에는 단추를 잠가 헛요청도 안 보낸다.
  // 목업 줄은 상태 전이만 흉내 낸다 — 성공 토스트는 결과를 지어내는 일이라 띄우지 않는다.
  const upload = useCallback(
    (id: string) => {
      const clip = clips.find((c) => c.id === id);
      const entry = clip?.entry;
      if (clip !== undefined && entry !== undefined) {
        const clipId = entry.latestClip?.id;
        if (entry.status !== 'rendered' || clipId === undefined) return;
        const problem = uploadTitleProblem(clip.title);
        if (problem !== null) {
          toast({ tone: 'error', title: '제목을 고쳐 주세요', description: problem });
          return;
        }
        if (sending.current.has(id)) return;
        setSendingFlag(id, true);
        requestUpload(entry.streamId, clipId, { title: clip.title.trim() })
          .then(({ created, upload: snap }) => {
            titleDrafts.current.delete(id);
            listGeneration.current += 1;
            // 다음 읽기(폴링)를 기다리지 않고 받은 업로드로 바로 옮긴다 — 그사이 단추가 다시 「업로드」로 돌아오지 않게
            setClips((prev) =>
              prev.map((c) =>
                c.id === id && c.entry !== undefined
                  ? toLibraryClip(withUpload(c.entry, snap), meIdRef.current)
                  : c,
              ),
            );
            toast(
              created
                ? {
                    tone: 'success',
                    title: '유튜브 업로드를 시작했어요',
                    description: '스트리머 채널에 비공개로 올라가요. 끝나면 여기 상태가 바뀌어요.',
                  }
                : {
                    tone: 'info',
                    title: '이미 올린 업로드가 있어요',
                    description: `같은 영상이 「${snap.title}」 제목으로 먼저 주문돼 있어요.`,
                  },
            );
          })
          .catch((e: unknown) => {
            toast({ tone: 'error', title: '업로드 주문 실패', description: uploadErrorMessage(e) });
          })
          .finally(() => setSendingFlag(id, false));
        return;
      }
      patch(id, (clip) =>
        clip.status === 'ready'
          ? { ...clip, status: role === 'editor' ? 'pending' : 'published' }
          : clip,
      );
    },
    [clips, patch, role, setSendingFlag, toast],
  );

  // 렌더 재시도 = 같은 편집본을 다시 주문한다(POK-125: 끝난 영상은 자리를 비워 다시 주문할 수 있다).
  const retryRender = useCallback(
    (id: string) => {
      const entry = clips.find((c) => c.id === id)?.entry;
      if (entry === undefined) {
        patch(id, (clip) => (clip.status === 'failed' ? { ...clip, status: 'ready' } : clip));
        return;
      }
      requestRender(entry.streamId, entry.recipeId)
        .then((snap) => {
          toast({ tone: 'success', title: `영상 #${snap.id} 다시 주문됨` });
          refresh();
        })
        .catch((e: unknown) => {
          const message =
            e instanceof ClipApiError && e.code === 'source_not_ready'
              ? '영상 조각이 아직 다 안 올라왔어요'
              : e instanceof Error
                ? e.message
                : String(e);
          toast({ tone: 'error', title: '다시 주문 실패', description: message });
        });
    },
    [clips, patch, refresh, toast],
  );

  // 완성 영상의 서명 주소를 그때그때 받는다(60분이라 목록에 싣지 않는다). 영상이 없는 편집본은 받는 척하지 않는다.
  const videoUrlOf = useCallback(
    async (id: string, action: '내려받기' | '미리보기'): Promise<string | null> => {
      const clip = clips.find((c) => c.id === id);
      const latest = clip?.entry?.latestClip;
      if (clip?.entry === undefined || latest?.status !== 'rendered') {
        toast({
          tone: 'info',
          title: '아직 완성된 영상이 없어요',
          description: '영상을 만들고 나면 받을 수 있어요.',
        });
        return null;
      }
      try {
        const access = await requestFileAccess(clip.entry.streamId, latest.id);
        const video = access.files.find((f) => f.kind === 'video');
        if (video === undefined) throw new Error('영상 파일이 없어요');
        return video.url;
      } catch (e) {
        const message =
          e instanceof ClipApiError && e.code === 'clip_not_rendered'
            ? '영상이 아직 완성되지 않았어요.'
            : e instanceof Error
              ? e.message
              : String(e);
        toast({ tone: 'error', title: `${action} 실패`, description: message });
        return null;
      }
    },
    [clips, toast],
  );

  // 목업 줄은 받을 파일이 없다 — 받는 척하고 멈춰 있느니 준비 중이라고 말한다(ADR-044의 「거짓말 금지」).
  // 서버 주소는 내려받기로 저장돼(Content-Disposition: attachment) 링크를 눌러 주기만 하면 된다.
  const download = useCallback(
    (id: string) => {
      if (clips.find((c) => c.id === id)?.entry === undefined) {
        toast({
          tone: 'info',
          title: '준비 중인 기능이에요',
          description: '편집본 내려받기는 아직 준비 중이에요. 준비되면 알려드릴게요.',
        });
        return;
      }
      void videoUrlOf(id, '내려받기').then((url) => {
        if (url === null) return;
        const a = document.createElement('a');
        a.href = url;
        a.rel = 'noopener';
        document.body.appendChild(a);
        a.click();
        a.remove();
      });
    },
    [clips, toast, videoUrlOf],
  );

  const previewUrl = useCallback((id: string) => videoUrlOf(id, '미리보기'), [videoUrlOf]);

  // 편집본은 영구 보존이라 지우는 문이 없다(POK-124). 서버 줄은 지우지 않는다 — 화면에서만 빼면 다음 읽기에
  // 되살아나 「지웠다」가 거짓이 된다(PR #200 codex). 상세 패널이 삭제 단추를 잠근다. 목업 줄만 흉내 낸다
  const remove = useCallback(
    (id: string) => {
      if (clips.find((c) => c.id === id)?.entry !== undefined) return;
      setClips((prev) => prev.filter((clip) => clip.id !== id));
      setSelectedId((prev) => (prev === id ? null : prev));
    },
    [clips],
  );

  const counts = useMemo(() => countByChip(clips, role, now), [clips, role, now]);
  const visible = useMemo(
    () => sortClips(filterByChip(filterByQuery(clips, query), chip, role, now), sort, now),
    [clips, query, chip, role, sort, now],
  );
  const pendingCount = useMemo(
    () => clips.filter((clip) => statusFor(clip, now) === 'pending').length,
    [clips, now],
  );
  const selectedClip = useMemo(
    () => clips.find((clip) => clip.id === selectedId) ?? null,
    [clips, selectedId],
  );

  return {
    now,
    loading,
    error,
    refresh,
    role,
    clips: visible,
    totalCount: clips.length,
    counts,
    pendingCount,
    chip,
    setChip,
    query,
    setQuery,
    sort,
    setSort,
    selectedId,
    selectedClip,
    select,
    deselect,
    renameClip,
    upload,
    sendingIds,
    retryRender,
    download,
    previewUrl,
    remove,
  };
}

/**
 * 고친 제목(초안)을 다시 읽은 줄에 얹는다. 올릴 수 있는(완성) 편집본에만 — 그 밖의 상태는 제목이 잠겨 있고, 다른 기기에서
 * 올렸으면 서버의 업로드 제목이 정본이다(초안을 얹으면 유튜브에 없는 제목이 보인다).
 */
function withDraft(clip: LibraryClip, drafts: Map<string, string>): LibraryClip {
  const draft = drafts.get(clip.id);
  if (draft !== undefined && clip.entry?.status !== 'rendered') {
    drafts.delete(clip.id);
    return clip;
  }
  return draft === undefined ? clip : { ...clip, title: draft };
}

/** 업로드 주문의 답을 서버 줄에 얹는다 — 보관함 상태는 clip이 업로드 상태로 정하는 규칙(POK-220)을 그대로 따른다 */
function withUpload(entry: LibraryEntry, upload: UploadSnapshot): LibraryEntry {
  const status: LibraryEntry['status'] =
    upload.status === 'uploaded'
      ? 'uploaded'
      : upload.status === 'checking'
        ? 'checking'
        : upload.status === 'failed'
          ? 'rendered'
          : 'uploading';
  return {
    ...entry,
    status,
    latestClip: entry.latestClip === null ? null : { ...entry.latestClip, upload },
  };
}
