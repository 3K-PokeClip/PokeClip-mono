'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useToast } from '@/ui';
import {
  fetchLibraryAll,
  fetchMeLoose,
  requestRender,
  ClipApiError,
  type LibraryEntry,
} from '@/api/clipEditor';
import {
  countByChip,
  filterByChip,
  filterByQuery,
  sortClips,
  statusFor,
  type LibraryChip,
  type LibrarySort,
} from './libraryView';

// 시안 1g 보관함의 상태 (POK-251 — 실제 값).
//
// 목록은 clip `GET /api/clip/library`(POK-243)에서 온다 — 내가 볼 수 있는 방송들의 편집본 전부와
// 편집본마다의 상태(editing·rendering·rendered·failed)·가장 최근 영상. 화면 타입 LibraryClip은
// 시안이 그리는 칸이라 서버 줄을 여기서 옮긴다(toLibraryClip). 서버가 아직 안 주는 칸은 지어내지
// 않는다 — 제목은 「편집본 #번호」, 만든 사람은 회원 번호, 유튜브 주소는 없음.
//
// 화면 상태 7종 중 서버가 주는 것은 넷이다. 승인 대기·반려·발행은 아직 화면에 잇지 않았다(승인 게이트 없음 결정,
// 업로드 연결은 다음 카드). 「만드는 중」은 화면 상태에 없어 편집 중 배지 + 보조 줄로 보여 준다.
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
    title: `편집본 #${entry.recipeId}`,
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
  /** 업로드 대기 → 발행됨(스트리머) / 승인 대기(편집자). 그 밖의 상태는 무시 */
  upload: (id: string) => void;
  /** 렌더 실패 → 업로드 대기. 결과를 토스트로 흉내 내지 않는다 */
  retryRender: (id: string) => void;
  /** 받을 파일이 아직 없다 — 「준비 중」만 알린다 */
  download: (id: string) => void;
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

  useEffect(() => {
    setClock(new Date());
    if (options.clips !== undefined) return undefined;
    let alive = true;
    (async () => {
      try {
        const [entries, me] = await Promise.all([fetchLibraryAll(), fetchMeLoose()]);
        if (!alive) return;
        const meId = me === null ? null : String(me.id);
        setClips(entries.map((entry) => toLibraryClip(entry, meId)));
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

  // 제목은 서버에 칸이 없다(계약6) — 화면에서만 바뀌고 새로고침하면 돌아온다. 업로드 메타(POK-220)가 자리다.
  const renameClip = useCallback((id: string, title: string) => {
    setClips((prev) => prev.map((clip) => (clip.id === id ? { ...clip, title } : clip)));
  }, []);

  const patch = useCallback((id: string, update: (clip: LibraryClip) => LibraryClip) => {
    setClips((prev) => prev.map((clip) => (clip.id === id ? update(clip) : clip)));
  }, []);

  // 서버 줄은 아직 업로드에 잇지 않았다(다음 카드) — 준비 중이라고 말한다.
  // 목업 줄은 상태 전이만 흉내 낸다 — 성공 토스트는 결과를 지어내는 일이라 띄우지 않는다.
  const upload = useCallback(
    (id: string) => {
      if (clips.find((c) => c.id === id)?.entry !== undefined) {
        toast({
          tone: 'info',
          title: '준비 중인 기능이에요',
          description: '유튜브 업로드는 곧 열려요.',
        });
        return;
      }
      patch(id, (clip) =>
        clip.status === 'ready'
          ? { ...clip, status: role === 'editor' ? 'pending' : 'published' }
          : clip,
      );
    },
    [clips, patch, role, toast],
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

  // 받기를 아직 화면에 잇지 않았다(다음 카드) — 받는 척하고 멈춰 있느니 준비 중이라고 말한다(ADR-044의 「거짓말 금지」).
  // id를 받고도 쓰지 않는 것은 일부러다 — 이 자리가 「어느 편집본을 받는가」를 채워야 할 곳임을 시그니처로 남긴다.
  const download = useCallback(
    (_id: string) => {
      toast({
        tone: 'info',
        title: '준비 중인 기능이에요',
        description: '편집본 내려받기는 아직 준비 중이에요. 준비되면 알려드릴게요.',
      });
    },
    [toast],
  );

  // 편집본은 영구 보존이라 지우는 문이 없다(POK-124). 화면에서만 감추고, 서버 줄이면 그렇다고 말한다.
  const remove = useCallback(
    (id: string) => {
      const fromServer = clips.find((c) => c.id === id)?.entry !== undefined;
      setClips((prev) => prev.filter((clip) => clip.id !== id));
      setSelectedId((prev) => (prev === id ? null : prev));
      if (fromServer)
        toast({
          tone: 'info',
          title: '목록에서 감췄어요',
          description: '편집본은 지워지지 않고 보관돼요.',
        });
    },
    [clips, toast],
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
    retryRender,
    download,
    remove,
  };
}
