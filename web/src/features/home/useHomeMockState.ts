'use client';

import { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '@/api/client';
import { broadcastTitle } from '@/api/clipEditor';
import {
  fetchAllBroadcasts,
  fetchAllJumpCards,
  fetchLibraryAll,
  type LibraryEntry,
} from '@/api/clipEditor';

// 홈 대시보드 상태 — clip·auth 창구 배선(POK-251).
// 목업 값은 전부 걷어냈다. 백엔드가 있는 것만 실값으로 채우고, 없는 칸은 null/빈 배열로
// 내려 화면이 「백엔드 미구현」을 그리게 한다. 훅·타입 이름은 화면이 그대로 쓰도록 유지한다.
//
// 실값 출처 (전부 same-origin 프록시, 로그인 세션의 apiFetch):
//   GET /api/clip/broadcasts?state=live|past       → 라이브 밴드 · 지난 방송 · 만료 임박
//   GET /api/clip/broadcasts/{id}/jump-cards       → 카드 수 · 최근 하이라이트 카드
//   GET /api/clip/broadcasts/{id}/broadcast-info   → 라이브 제목 (latest=null이면 streamId)
//   GET /api/auth/me                               → 인사말의 이름
//   GET /api/clip/library                          → 이어서 편집 · 발행 현황 · 클립 완료 수(POK-111)

/** 이어서 편집 배너 — 편집본 저장 API가 없어 항상 null. 닫기는 이번 세션에서만 유효 */
export interface ResumeDraft {
  title: string;
  meta: string;
  /** 편집기로 여는 주소 — 저장된 편집본(?recipe=) */
  href: string;
}

/** 방송 중일 때만 노출되는 라이브 밴드 — 오프라인이면 섹션 전체 미노출 */
export interface LiveNow {
  streamId: string;
  title: string;
  platform: string;
  startedNote: string;
  /** 방송 경과 표기 — LIVE 배지 옆에 그대로 붙는다 */
  uptimeLabel: string;
  /** 시청자 수 — 백엔드가 없으면 null */
  viewers: string | null;
  detectedCards: number;
  /** 이 방송에서 영상까지 만든 편집본 수. 보관함을 못 읽었으면 null */
  completedClips: number | null;
  /** 최신 방송 화면 사진(POK-277). 아직 없으면 null */
  thumbnailUrl?: string | null;
}

export type VodBadge = { kind: 'preparing' } | { kind: 'dday'; label: string };

export interface HomeVod {
  id: string;
  title: string;
  meta: string;
  href: string;
  badge?: VodBadge;
  duration?: string;
  /** 가장 크게 터진 장면 사진(POK-277). 없으면 자리표시 */
  thumbnailUrl?: string | null;
}

/** checking = 유튜브에 올라갔는지 모른다(사람이 채널을 봐야 한다, POK-220) */
export type PublishStatus = 'uploading' | 'checking' | 'scheduled' | 'published';

export interface PublishRow {
  id: string;
  title: string;
  status: PublishStatus;
  /** uploading일 때만 존재하는 진행률(%) */
  progress?: number;
  /** scheduled·published의 우측 보조 텍스트 (예약 시각·조회수) */
  note?: string;
}

export interface ExpiringVod {
  id: string;
  dday: string;
  /** D-3 이하 — 붉은 배지로 급함을 표시 */
  urgent: boolean;
  title: string;
}

/** 최근 감지된 하이라이트 카드 — 라이브·지난 방송을 합쳐 createdAt 내림차순 */
export interface RecentCard {
  id: string;
  streamId: string;
  source: 'auto' | 'hotkey';
  /** 방송 시작 기준 위치 (h:mm:ss) */
  positionLabel: string;
  score: number | null;
  createdAt: string;
  /** 라이브면 livenow?stream=, 지난 방송이면 vod/<id> */
  href: string;
  isLive: boolean;
}

export interface HomeMockState {
  userName: string | null;
  greeting: string;
  /** 가장 최근에 고친 「편집 중」 편집본. 없으면 배너를 안 그린다 */
  resumeDraft: ResumeDraft | null;
  resumeDismissed: boolean;
  dismissResume: () => void;
  live: LiveNow | null;
  vods: HomeVod[];
  /** 발행 현황 줄. 보관함을 아직 못 읽었으면 null — 빈 배열(「올린 영상 없음」)과 가른다 */
  publishRows: PublishRow[] | null;
  /** 보관함을 한 번도 못 읽었고 마지막 시도가 실패했다 */
  publishUnavailable: boolean;
  expiringVods: ExpiringVod[];
  recentCards: RecentCard[];
  /** 첫 응답이 오기 전 true — 빈 목록을 「없음」으로 그리지 않기 위해 */
  loading: boolean;
}

// ── 와이어 형식 ──
interface WireBroadcast {
  streamId: string;
  status: 'live' | 'ended' | 'vod_ready';
  relation: 'OWNER' | 'EDITOR';
  startedAt: string | null;
  endedAt: string | null;
  vodExpiresAt: string | null;
  /** 사진 주소(POK-277). 옛 서버면 빠져 온다 */
  thumbnailUrl?: string | null;
  /** 치지직 방송 제목(POK-259). 없거나 옛 서버면 비어 온다 */
  title?: string | null;
}
interface WireList {
  broadcasts: WireBroadcast[];
  nextCursor: string | null;
}
interface WireCard {
  id: number | string;
  streamId: string;
  source: 'auto' | 'hotkey';
  streamTimestampMs: number;
  window: { startMs: number; endMs: number };
  score: number | null;
  evidence: unknown;
  claimedBy: string | null;
  hidden: boolean;
  eventSeq: number;
  createdAt: string;
}
interface WireInfo {
  latest: {
    title: string | null;
    tags: string[];
    category: string | null;
    viewers?: number | null;
  } | null;
  series: unknown[];
}
interface WireMe {
  id: number | string;
  email: string;
  name: string | null;
  profileImageUrl: string | null;
}

const POLL_MS = 10_000;
const PAST_LIMIT = 8;
const RECENT_LIMIT = 6;
const EXPIRING_WINDOW_DAYS = 7;

async function getJson<T>(path: string): Promise<T | null> {
  try {
    const r = await apiFetch(path);
    return (await r.json()) as T;
  } catch {
    return null;
  }
}

function msToClock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

function clockLabel(iso: string): string {
  const d = new Date(iso);
  const h = d.getHours();
  const ampm = h < 12 ? '오전' : '오후';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${ampm} ${h12}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function dateLabel(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}월 ${d.getDate()}일`;
}

/** 만료까지 남은 일수 (올림). 지났으면 0 */
function daysLeft(iso: string, now: number): number {
  return Math.max(0, Math.ceil((Date.parse(iso) - now) / 86_400_000));
}

function greetingFor(hour: number): string {
  if (hour < 5) return '늦은 밤이에요';
  if (hour < 12) return '좋은 아침이에요';
  if (hour < 18) return '좋은 오후예요';
  return '좋은 저녁이에요';
}

interface Loaded {
  userName: string | null;
  live: LiveNow | null;
  vods: HomeVod[];
  expiringVods: ExpiringVod[];
  recentCards: RecentCard[];
}

/**
 * 홈 한 벌. 방송 목록을 못 읽으면 null — 부른 쪽이 지난 화면을 그대로 둔다.
 * 빈 값으로 덮으면 clip이 잠깐 재시작해도 라이브 띠와 지난 방송이 사라진다(PR #200 codex).
 */
async function loadHome(now: number): Promise<Loaded | null> {
  const [me, liveList, pastAll] = await Promise.all([
    getJson<WireMe>('/api/auth/me'),
    getJson<WireList>('/api/clip/broadcasts?state=live&limit=1'),
    // 지난 방송은 끝까지 읽는다 — 곧 만료될 방송은 가장 오래된 것이라 목록 끝에 있다(POK-251 리뷰 2라운드).
    // 한 쪽만 읽으면 방송이 많은 채널일수록 「만료 임박」이 비어 보인다
    fetchAllBroadcasts('past').catch(() => null),
  ]);
  if (liveList === null || pastAll === null) return null;

  const liveBroadcast = liveList?.broadcasts[0] ?? null;
  const past = pastAll.slice(0, PAST_LIMIT);

  // 카드는 방송마다 한 번씩 — 라이브 + 지난 방송 상위 N개
  const targets = [...(liveBroadcast ? [liveBroadcast] : []), ...past];
  const cardsByStream = new Map<string, WireCard[]>();
  await Promise.all(
    targets.map(async (b) => {
      // 끝까지 넘긴다 — 기본 50장이고 오래된 것부터 와서, 첫 장만 읽으면 카드 수가 50에서 잘리고
      // 최근 카드가 통째로 빠진다(PR #200 codex)
      const cards = await fetchAllJumpCards<WireCard>(b.streamId).catch(() => [] as WireCard[]);
      cardsByStream.set(
        b.streamId,
        cards.filter((c) => !c.hidden),
      );
    }),
  );

  let live: LiveNow | null = null;
  if (liveBroadcast) {
    const info = await getJson<WireInfo>(
      `/api/clip/broadcasts/${encodeURIComponent(liveBroadcast.streamId)}/broadcast-info`,
    );
    // 방송 정보 창구의 최신 관측이 먼저다(목록보다 늦게 바뀐 제목을 먼저 본다). 그 관측에 제목이 비면 목록 제목, 그것도 없으면 번호
    const title = info?.latest?.title?.trim() || broadcastTitle(liveBroadcast);
    const startedAt = liveBroadcast.startedAt;
    live = {
      streamId: liveBroadcast.streamId,
      title,
      platform: '치지직',
      startedNote: startedAt ? `${clockLabel(startedAt)} 시작` : '시작 시각 정보 없음',
      uptimeLabel: startedAt ? msToClock(now - Date.parse(startedAt)) : '--:--:--',
      // 수집기가 1분마다 채우는 시청자 수. 아직 없으면 null(「준비 중」)
      viewers:
        typeof info?.latest?.viewers === 'number'
          ? info.latest.viewers.toLocaleString('ko-KR')
          : null,
      detectedCards: cardsByStream.get(liveBroadcast.streamId)?.length ?? 0,
      completedClips: null,
      thumbnailUrl: liveBroadcast.thumbnailUrl ?? null,
    };
  }

  const vods: HomeVod[] = past.map((b) => {
    const cardCount = cardsByStream.get(b.streamId)?.length ?? 0;
    const metaParts = [
      b.startedAt ? dateLabel(b.startedAt) : '날짜 정보 없음',
      `카드 ${cardCount}개`,
    ];
    let badge: VodBadge | undefined;
    if (b.status === 'ended') badge = { kind: 'preparing' };
    else if (b.vodExpiresAt && Date.parse(b.vodExpiresAt) > now) {
      const d = daysLeft(b.vodExpiresAt, now);
      if (d <= EXPIRING_WINDOW_DAYS) {
        badge = { kind: 'dday', label: `D-${d}` };
        metaParts.push('곧 만료');
      }
    }
    const duration =
      b.startedAt && b.endedAt
        ? msToClock(Date.parse(b.endedAt) - Date.parse(b.startedAt))
        : undefined;
    return {
      id: b.streamId,
      title: broadcastTitle(b),
      meta: metaParts.join(' · '),
      href: `/broadcast/vod/${encodeURIComponent(b.streamId)}`,
      badge,
      duration,
      thumbnailUrl: b.thumbnailUrl ?? null,
    };
  });

  // 이미 만료된 방송은 뺀다 — 기한이 지난 방송도 목록에 남으므로 빼지 않으면 D-0으로 영영 쌓인다(PR #200 claude)
  const expiringVods: ExpiringVod[] = pastAll
    .flatMap((b) =>
      b.vodExpiresAt && Date.parse(b.vodExpiresAt) > now
        ? [{ b, d: daysLeft(b.vodExpiresAt, now) }]
        : [],
    )
    .filter(({ d }) => d <= EXPIRING_WINDOW_DAYS)
    .sort((x, y) => x.d - y.d)
    .map(({ b, d }) => {
      const cards = cardsByStream.get(b.streamId);
      return {
        id: b.streamId,
        dday: `D-${d}`,
        urgent: d <= 3,
        title: cards ? `${broadcastTitle(b)} · 카드 ${cards.length}개` : broadcastTitle(b),
      };
    });

  const recentCards: RecentCard[] = [];
  for (const [streamId, cards] of cardsByStream) {
    const isLive = liveBroadcast?.streamId === streamId;
    for (const c of cards) {
      recentCards.push({
        id: `${streamId}:${c.id}`,
        streamId,
        source: c.source,
        positionLabel: msToClock(c.streamTimestampMs),
        score: c.score,
        createdAt: c.createdAt,
        href: isLive
          ? `/broadcast/livenow?stream=${encodeURIComponent(streamId)}`
          : `/broadcast/vod/${encodeURIComponent(streamId)}`,
        isLive,
      });
    }
  }
  recentCards.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));

  return {
    userName: me?.name?.trim() || me?.email || null,
    live,
    vods,
    expiringVods,
    recentCards: recentCards.slice(0, RECENT_LIMIT),
  };
}

const EMPTY: Loaded = { userName: null, live: null, vods: [], expiringVods: [], recentCards: [] };

export function useHomeMockState(): HomeMockState {
  // 편집본 저장 백엔드가 없다 — 배너는 「백엔드 미구현」 안내로만 선다. 닫으면 세션 동안 사라진다.
  const [resumeDismissed, setResumeDismissed] = useState(false);
  const dismissResume = useCallback(() => setResumeDismissed(true), []);

  const [data, setData] = useState<Loaded>(EMPTY);
  // 보관함(POK-243) — 이어서 편집 · 발행 현황 · 클립 완료 수가 여기서 나온다. 못 읽으면 null(배너는 안내만, 수는 「준비 중」)
  const [library, setLibrary] = useState<LibraryEntry[] | null>(null);
  const [libraryFailed, setLibraryFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  // 인사말은 마운트 뒤 클라이언트 시계로 정한다 — 서버 렌더와 어긋나지 않게 초기값은 고정.
  const [greeting, setGreeting] = useState('안녕하세요');

  useEffect(() => {
    setGreeting(greetingFor(new Date().getHours()));
    let stopped = false;
    // 읽기가 10초보다 오래 걸리면 다음 주기와 겹친다. 더 늦게 떠난 읽기가 이미 반영됐으면 먼저 떠나 늦게 온 답은 버린다
    // (옛 「올리는 중」이 새 「발행됨」을 덮지 않게, PR #203 codex). 가장 최근 것만 받으면 늘 겹칠 때 아무것도 안 그려진다
    let started = 0;
    let applied = 0;
    const tick = async () => {
      const mine = ++started;
      // 업로드가 끝나면 발행 현황이 바뀌어야 해서 방송과 같은 주기로 다시 읽는다. 못 읽으면 지난 값을 둔다
      const [loaded, entries] = await Promise.all([
        loadHome(Date.now()),
        fetchLibraryAll().catch(() => null),
      ]);
      if (stopped || mine < applied) return;
      applied = mine;
      if (entries !== null) setLibrary(entries);
      setLibraryFailed(entries === null);
      // 못 읽었으면 지난 화면을 그대로 둔다. 첫 읽기부터 실패면 다음 주기까지 「불러오는 중」
      if (loaded === null) return;
      setData(loaded);
      setLoading(false);
    };
    void tick();
    const t = window.setInterval(() => void tick(), POLL_MS);
    return () => {
      stopped = true;
      window.clearInterval(t);
    };
  }, []);

  const live =
    data.live === null
      ? null
      : { ...data.live, completedClips: completedClipsOf(library, data.live.streamId) };

  return {
    userName: data.userName,
    greeting,
    resumeDraft: library === null ? null : resumeDraftOf(library),
    resumeDismissed,
    dismissResume,
    live,
    vods: data.vods,
    publishRows: library === null ? null : publishRowsOf(library),
    // 한 번 읽은 뒤의 실패는 지난 줄을 그대로 둔다(방송 목록과 같다) — 안내는 아직 아무것도 모를 때만
    publishUnavailable: library === null && libraryFailed,
    expiringVods: data.expiringVods,
    recentCards: data.recentCards,
    loading,
  };
}

/** 이어서 편집 = 가장 최근에 고친 「편집 중」 편집본 하나 */
function resumeDraftOf(entries: readonly LibraryEntry[]): ResumeDraft | null {
  const editing = entries
    .filter((e) => e.status === 'editing')
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))[0];
  return editing === undefined
    ? null
    : {
        title: `편집본 #${editing.recipeId}`,
        meta: `방송 ${editing.streamId} · v${editing.recipeVersion} · ${new Date(editing.updatedAt).toLocaleString('ko-KR')} 고침`,
        href: `/clips/editor/studio?recipe=${editing.recipeId}`,
      };
}

const PUBLISH_LIMIT = 5;

/**
 * 발행 현황 = 편집본마다 가장 최근 영상의 업로드를 최근 순으로(올리는 중·확인 필요·올림). 실패는 빼고(보관함 패널이 사유를
 * 말한다) 예약 발행은 아직 없다. 🔴 편집본 상태가 아니라 <b>업로드 상태</b>로 가른다: 올린 뒤 새 판을 저장하면 편집본은
 * 「편집 중」이 되지만 올린 영상은 유튜브에 그대로 있다. 새 판 영상을 만들면 목록 줄이 그 영상을 가리켜 옛 업로드는 안 보인다
 * (보관함 목록이 편집본마다 가장 최근 영상 하나만 준다).
 */
function publishRowsOf(entries: readonly LibraryEntry[]): PublishRow[] {
  const rows: (PublishRow & { at: number })[] = [];
  for (const e of entries) {
    const upload = e.latestClip?.upload;
    if (upload == null) continue;
    const status: PublishStatus | null =
      upload.status === 'queued' || upload.status === 'uploading'
        ? 'uploading'
        : upload.status === 'checking'
          ? 'checking'
          : upload.status === 'uploaded'
            ? 'published'
            : null;
    if (status === null) continue;
    rows.push({
      id: String(upload.id),
      title: upload.title,
      status,
      at: Date.parse(upload.updatedAt),
    });
  }
  return rows
    .sort((a, b) => b.at - a.at)
    .slice(0, PUBLISH_LIMIT)
    .map(({ at: _at, ...row }) => row);
}

/**
 * 라이브 방송에서 영상까지 만든 편집본 수. 편집본 상태가 아니라 <b>가장 최근 영상이 완성인가</b>로 센다: 영상을 만든 뒤 새 판을
 * 저장만 하면 편집본은 「편집 중」이지만 만든 영상은 그대로 있다(발행 현황과 같은 기준). 새 판을 다시 만드는 중이면 목록 줄이
 * 그 영상을 가리켜 세지 않는다(보관함 목록은 편집본마다 영상 하나만 준다).
 */
function completedClipsOf(
  entries: readonly LibraryEntry[] | null,
  streamId: string,
): number | null {
  if (entries === null) return null;
  return entries.filter((e) => e.streamId === streamId && e.latestClip?.status === 'rendered')
    .length;
}
