'use client';

import { useEffect, useMemo } from 'react';
import { queryOptions, useQuery } from '@tanstack/react-query';
import { meQueryOptions } from '@/api/auth';
import { apiFetch } from '@/api/client';
import { chzzkLinkQueryOptions, type ChzzkLinkState } from '@/api/chzzkLink';
import { soopLinkQueryOptions, type SoopLinkState } from '@/api/soopLink';
import { streamKeyStatusQueryOptions } from '@/api/streamKeys';
import { youtubeLinkQueryOptions, type YoutubeLinkState } from '@/api/youtubeLink';
import { broadcastTitle } from '@/api/clipEditor';
import { infoWaitOver } from '@/features/broadcast/infoWait';
import { onAirLabel, primaryInfo, type PlatformLatest } from '@/features/broadcast/platformView';
import { ddayFor, isJustEnded } from '@/features/broadcast/vod/vodListView';
import {
  fetchAllBroadcasts,
  fetchAllJumpCards,
  libraryQueryOptions,
  liveUploadsOf,
  type BroadcastRow,
  type LibraryEntry,
} from '@/api/clipEditor';
import { runLimited } from '@/lib/runLimited';
import { useOnboardingStore } from '@/stores/onboarding';
import { useChannelStreamerId } from '@/features/channel/ChannelScope';

// 홈 대시보드 상태 — clip·auth 창구 배선(POK-251), 화면 개편(POK-378).
// 백엔드가 있는 것만 실값으로 채운다. 맨 위 큰 칸의 숫자는 새 API 없이 이미 읽는 목록을 날짜로 세기만 한다 —
// 「무엇을 먼저 할지」 고르는 추천 로직은 두지 않는다(POK-378에서 「할 일 목록」 안을 기각한 이유).
//
// 실값 출처 (전부 same-origin 프록시, 로그인 세션의 apiFetch):
//   GET /api/clip/broadcasts?state=live|past       → 라이브 밴드 · 지난 방송 · 이번 주/지난주 방송 수 · 마지막 방송
//   GET /api/clip/broadcasts/{id}/jump-cards       → 카드 수(지난 방송 격자 · 이번 주/지난주 찾은 카드)
//   GET /api/clip/broadcasts/{id}/broadcast-info   → 라이브 제목 (latest=null이면 streamId)
//   GET /api/auth/me                               → 큰 칸 인사의 이름
//   GET /api/clip/library                          → 라이브 클립 완료 수 · 이번 주/지난주 만든 클립·유튜브 발행
//   GET /api/chzzk-link · /api/soop-link · /api/youtube-link · /api/stream-keys → 준비 단계 · 연결 상태(1분마다)

export interface LiveNow {
  streamId: string;
  /**
   * 방송 제목. 방송 정보 관측의 제목, 비면 목록 제목이다. 둘 다 없으면 null(아직 모름): 방송 번호를 제목 자리에 쓰지 않는다(POK-449,
   * OBS를 켠 직후 실방송에서 `live-…` 번호가 제목처럼 떴다)
   */
  title: string | null;
  /**
   * 켜진 플랫폼 표시 이름(「치지직 · SOOP」). 수집기가 실제로 본 플랫폼에서만 고르고, 아직 아무것도 못 봤으면 null이다. 목록의 플랫폼
   * 칸(값이 없으면 치지직)으로 채우지 않는다: SOOP 방송을 켠 직후 「치지직」이 떴다(POK-449)
   */
  platform: string | null;
  /** 이 방송 대시보드를 지금 연 편집자 수(POK-398, 스트리머 본인과 나는 뺌). 0이거나 모르면(null·없음) 띠에 안 적는다 */
  editorsOnline?: number | null;
  startedNote: string;
  /** 방송 경과 표기 — LIVE 배지 옆에 그대로 붙는다 */
  uptimeLabel: string;
  /** 시청자 수 — 수집기가 아직 못 쟀으면 null */
  viewers: string | null;
  /**
   * 방송 시작에서 2분(INFO_WAIT_MS, features/broadcast/infoWait)이 지났다. 그 뒤에도 비어 있는 칸은 회색 막대 대신 「아직 받지 못했어요」로 적는다: 수집기가 이 방송을
   * 못 찾는 동안 막대가 끝없이 번쩍이면 곧 온다고 읽힌다(POK-449)
   */
  infoLate: boolean;
  detectedCards: number;
  /** 이 방송에서 영상까지 만든 편집본 수. 보관함을 못 읽었으면 null */
  completedClips: number | null;
  /** 최신 방송 화면 사진(POK-277). 아직 없으면 null */
  thumbnailUrl?: string | null;
}

export type VodBadge =
  { kind: 'preparing' } | { kind: 'dday'; label: string } | { kind: 'expired' };

export interface HomeVod {
  id: string;
  title: string;
  meta: string;
  /** 보관이 끝난 방송은 열 것이 없어 null(지난 방송 목록과 같다, POK-254) */
  href: string | null;
  badge?: VodBadge;
  duration?: string;
  /** 가장 크게 터진 장면 사진(POK-277). 없으면 자리표시 */
  thumbnailUrl?: string | null;
}

/** 한 주(월요일 0시부터, 로컬 시각)의 숫자 넷. 보관함을 못 읽었으면 클립·발행은 null(「–」로 그린다) */
export interface WeekStats {
  broadcasts: number;
  cards: number;
  clips: number | null;
  published: number | null;
}

/** 연동 상태 — 각 칸은 못 읽었으면 null(「확인 중」, 버튼을 내밀지 않는다) */
export interface HomeLinks {
  chzzk: ChzzkLinkState | null;
  soop: SoopLinkState | null;
  youtube: YoutubeLinkState | null;
  /** 연동 코드를 한 번이라도 발급했는가(스트림키 존재) */
  streamKeyIssued: boolean | null;
}

export interface HomeMockState {
  userName: string | null;
  live: LiveNow | null;
  vods: HomeVod[];
  /** 방송 기록이 하나라도 있다(라이브 포함) — 없으면 준비 단계 큰 칸을 그린다 */
  hasHistory: boolean;
  week: WeekStats;
  lastWeek: WeekStats;
  /** 가장 최근에 끝난 방송 — 이번 주가 빈 주일 때 큰 칸이 말한다 */
  lastBroadcast: { date: string; ago: string } | null;
  links: HomeLinks;
  /** 첫 응답이 오기 전 true — 그동안 준비 단계(신규 계정 화면)를 잘못 보이지 않는다 */
  loading: boolean;
}

// ── 와이어 형식 ── 방송 목록은 api/clipEditor의 BroadcastRow(fetchAllBroadcasts)를 그대로 쓴다
interface WireInfo {
  latest: {
    title: string | null;
    tags: string[];
    category: string | null;
    viewers?: number | null;
    observedAt?: string | null;
  } | null;
  /** 플랫폼마다 최신 값(POK-397). 옛 서버면 빠져 온다 */
  platforms?: PlatformLatest[];
  series: { observedAt: string; platform?: string | null }[];
}
const POLL_MS = 10_000;
/** 이 안에 다시 들어오거나 탭으로 돌아오면 받아 둔 값을 그대로 쓴다 */
const REVISIT_FRESH_MS = 20_000;
/** 연동 상태는 자주 바뀌지 않는다 — 방송 목록(10초)보다 드물게 읽는다 */
const LINKS_POLL_MS = 60_000;
/** 끝난 방송의 카드 수를 기억하는 시간 — 끝난 지 오래된 방송은 카드가 거의 안 바뀐다 */
const CARD_CACHE_MS = 5 * 60_000;
/** 지난 방송 목록을 다시 읽는 간격 — 쪽을 끝까지 넘겨 무겁고, 방송이 끝날 때는 바로 다시 읽는다 */
const PAST_LIST_MS = 60_000;
/** 보관함을 다시 읽는 간격 — 홈은 주 단위 숫자만 쓴다. 만드는 중을 지켜보는 화면은 보관함이다 */
const LIBRARY_POLL_MS = 60_000;
/** 방송마다 카드를 읽는 동시 수 */
const CARD_READ_PARALLEL = 6;
const PAST_LIMIT = 8;
const EXPIRING_WINDOW_DAYS = 7;
const DAY_MS = 86_400_000;

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T | null> {
  try {
    const r = await apiFetch(path, signal ? { signal } : undefined);
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
  return Math.max(0, Math.ceil((Date.parse(iso) - now) / DAY_MS));
}

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

/** 이번 주(월요일 0시, 로컬)와 지난주의 시작 시각(ms). 지난주 = [lastWeekStart, weekStart) */
export function weekStarts(now: Date): { lastWeekStart: number; weekStart: number } {
  const monday = startOfDay(now);
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
  const lastMonday = new Date(monday);
  lastMonday.setDate(monday.getDate() - 7);
  return { lastWeekStart: lastMonday.getTime(), weekStart: monday.getTime() };
}

/** 달력 날짜로 센 「며칠 전」 — 어제 밤 방송이 아침에 「0일 전」으로 보이지 않게 시각이 아니라 날짜로 뺀다 */
export function daysAgoLabel(iso: string, now: Date): string {
  const days = Math.round(
    (startOfDay(now).getTime() - startOfDay(new Date(iso)).getTime()) / DAY_MS,
  );
  if (days <= 0) return '오늘';
  if (days === 1) return '어제';
  return `${days}일 전`;
}

function inRange(iso: string | null | undefined, from: number, to: number): boolean {
  if (!iso) return false;
  const t = Date.parse(iso);
  return t >= from && t < to;
}

interface BroadcastWeek {
  broadcasts: number;
  cards: number;
}

interface Loaded {
  live: LiveNow | null;
  vods: HomeVod[];
  hasHistory: boolean;
  week: BroadcastWeek;
  lastWeek: BroadcastWeek;
  lastBroadcast: { date: string; ago: string } | null;
}

/** 주기 사이에 이어 가는 값. 쿼리 데이터에 함께 두어 홈을 떠났다 돌아와도 남는다 */
interface HomeMemory {
  pastAll: BroadcastRow[];
  pastReadAt: number;
  /** 앞 주기의 라이브 방송들. 그중 끝난 방송이 있으면 지난 방송 목록을 바로 다시 읽는다 */
  liveIds: string[];
  /** 방송별 카드 수와 잰 시각 */
  cardCounts: Record<string, { count: number; at: number }>;
}

type HomeData = Loaded & { memory: HomeMemory };

/**
 * 홈 한 벌. 방송 목록을 못 읽으면 던진다: react-query가 지난 화면을 그대로 두고 다음 주기에 다시 읽는다.
 * 빈 값으로 덮으면 clip이 잠깐 재시작해도 라이브 띠와 지난 방송이 사라진다(PR #200 codex).
 * signal은 홈이 내려가면(채널 전환 등) 남은 단계를 끊는다: 끊지 않으면 옛 채널 카드 요청이 이어져 채널 전환 화면이 그것까지
 * 기다린다(PR #342 2차 리뷰)
 */
async function loadHome(
  now: number,
  previous: HomeMemory | undefined,
  streamerId: number | null,
  signal: AbortSignal,
): Promise<HomeData> {
  const readPast = () => fetchAllBroadcasts('past', streamerId, signal);
  const pastDue = previous === undefined || now - previous.pastReadAt >= PAST_LIST_MS;
  const [liveAll, pastRead] = await Promise.all([
    // 라이브도 끝까지 — 여러 스트리머의 편집자면 동시에 여럿이 방송 중이다. 밴드는 첫 방송만, 이번 주 숫자는 전부 센다
    fetchAllBroadcasts('live', streamerId, signal),
    // 지난 방송은 끝까지 읽는다 — 지난주 방송 수를 세려면 첫 쪽만으로는 모자랄 수 있다
    pastDue ? readPast() : null,
  ]);
  const liveIds = new Set(liveAll.map((b) => b.streamId));
  // 방송이 끝나면 바로 지난 방송에 올린다: 다음 읽기까지 기다리면 이번 주 숫자에서 그 방송이 잠깐 빠진다
  const liveEnded = previous?.liveIds.some((id) => !liveIds.has(id)) ?? false;
  const pastAll =
    pastRead ?? (liveEnded || previous === undefined ? await readPast() : previous.pastAll);
  const pastReadAt = pastRead !== null || liveEnded ? now : (previous?.pastReadAt ?? now);

  const liveBroadcast = liveAll[0] ?? null;
  const past = pastAll.slice(0, PAST_LIMIT);
  const { lastWeekStart, weekStart } = weekStarts(new Date(now));
  // 두 목록은 동시에 읽는다 — 그 사이에 끝난 방송은 양쪽에 다 실려 올 수 있어 한 번만 센다(리뷰 #299 2차)
  const all = [...liveAll, ...pastAll.filter((b) => !liveIds.has(b.streamId))];

  // 카드는 방송마다 한 번씩 — 라이브와 방금 끝난 방송(카드가 늦게 뜬다)은 매번, 그 밖의 방송(지난 방송 격자 · 이번 주 · 지난주)은
  // 기억해 둔 값이 CARD_CACHE_MS 안이면 그대로 쓴다(리뷰 #299: 10초마다 수십 개 방송을 다시 읽던 부하)
  const fresh = new Set([
    ...liveIds,
    ...pastAll.filter((b) => isJustEnded(b.endedAt, new Date(now))).map((b) => b.streamId),
  ]);
  const counted = new Set([
    ...fresh,
    ...past.map((b) => b.streamId),
    ...pastAll.filter((b) => inRange(b.startedAt, lastWeekStart, Infinity)).map((b) => b.streamId),
  ]);
  const cardCounts: HomeMemory['cardCounts'] = {};
  await runLimited([...counted], CARD_READ_PARALLEL, async (streamId) => {
    if (signal.aborted) return;
    const known = previous?.cardCounts[streamId];
    if (known && !fresh.has(streamId) && now - known.at < CARD_CACHE_MS) {
      cardCounts[streamId] = known;
      return;
    }
    // 끝까지 넘긴다 — 기본 50장이고 오래된 것부터 와서, 첫 장만 읽으면 카드 수가 50에서 잘리고
    // 최근 카드가 통째로 빠진다(PR #200 codex)
    // 옛 서버가 hidden을 실어 보내도 거르지 않는다: 숨기기는 POK-393에서 없앴다
    const cards = await fetchAllJumpCards(streamId, signal).catch(() => null);
    if (cards !== null) cardCounts[streamId] = { count: cards.length, at: now };
    // 못 읽었으면 기억해 둔 값을(기한이 지났어도) 쓰고 시각은 그대로 둔다 — 다음 주기에 다시 읽는다.
    // 빈 값을 기억하면 5분 동안 카드 수가 실제보다 적게 보인다(리뷰 #299 2차)
    else if (known) cardCounts[streamId] = known;
  });
  const cardCountOf = (streamId: string) => cardCounts[streamId]?.count ?? 0;

  // 주는 방송 시작 시각으로 가른다 — 일요일 밤에 켜 월요일까지 간 방송은 지난주 방송이다.
  // 단 지금 하고 있는 회차는 이번 주에도 센다 — 방송 중인데 큰 칸이 「방송 0번」이라 하지 않게
  const weekOf = (from: number, to: number, withLive = false): BroadcastWeek => {
    const rows = all.filter(
      (b) => inRange(b.startedAt, from, to) || (withLive && liveIds.has(b.streamId)),
    );
    return {
      broadcasts: rows.length,
      cards: rows.reduce((n, b) => n + cardCountOf(b.streamId), 0),
    };
  };

  if (signal.aborted) throw signal.reason;
  let live: LiveNow | null = null;
  if (liveBroadcast) {
    const info = await getJson<WireInfo>(
      `/api/clip/broadcasts/${encodeURIComponent(liveBroadcast.streamId)}/broadcast-info`,
      signal,
    );
    // 방송 정보 창구의 최신 관측이 먼저다(목록보다 늦게 바뀐 제목을 먼저 본다). 그 관측에 제목이 비면 목록 제목, 그것도 없으면
    // null(띠가 회색 막대로 비운다, POK-449). 관측은 대표 플랫폼 하나의 것이다: 둘 다 켤 수 있어 가장 새 관측을 그대로 쓰면 다른
    // 플랫폼 값이 섞인다(POK-397)
    const latest = info === null ? null : primaryInfo(info, now).latest;
    const title = latest?.title?.trim() || liveBroadcast.title?.trim() || null;
    const startedAt = liveBroadcast.startedAt;
    live = {
      streamId: liveBroadcast.streamId,
      title,
      // 켜진 플랫폼 태그(「치지직 · SOOP」). 방송 정보가 플랫폼마다 값을 주면 그것으로, 아니면 목록의 묶음이다(POK-397). 둘 다
      // 없으면 비운다: 목록의 플랫폼 칸은 수집기가 보기 전에도 치지직이라 SOOP 방송에 「치지직」이 떴다(POK-449)
      platform: onAirLabel(info?.platforms ?? liveBroadcast.platforms, now),
      editorsOnline: liveBroadcast.editorsOnline ?? null,
      startedNote: startedAt ? `${clockLabel(startedAt)} 시작` : '시작 시각 정보 없음',
      uptimeLabel: startedAt ? msToClock(now - Date.parse(startedAt)) : '--:--:--',
      // 수집기가 1분마다 채우는 시청자 수. 아직 없으면 null(「준비 중」)
      viewers: typeof latest?.viewers === 'number' ? latest.viewers.toLocaleString('ko-KR') : null,
      // 시작 시각을 모르는 방송(자리표시)은 기다릴 기준이 없어 처음부터 글자로 적는다: 막대가 끝없이 번쩍이지 않게(로컬 리뷰 1라운드)
      infoLate: startedAt ? infoWaitOver(Date.parse(startedAt), now) : true,
      detectedCards: cardCountOf(liveBroadcast.streamId),
      completedClips: null,
      thumbnailUrl: liveBroadcast.thumbnailUrl ?? null,
    };
  }

  const vods: HomeVod[] = past.map((b) => {
    const cardCount = cardCountOf(b.streamId);
    const metaParts = [
      b.startedAt ? dateLabel(b.startedAt) : '날짜 정보 없음',
      `카드 ${cardCount}개`,
    ];
    let badge: VodBadge | undefined;
    // 지난 방송 목록과 같은 규칙: 만료가 먼저고, 「준비 중」은 끝난 지 한 시간 안만이다(POK-254). 그 뒤에는 녹화가 없어도
    // 지난 방송 화면에서 카드·채팅을 볼 수 있어 그대로 연다
    const expired = ddayFor(b.vodExpiresAt, new Date(now)).kind === 'expired';
    if (expired) badge = { kind: 'expired' };
    else if (b.status === 'ended') {
      if (isJustEnded(b.endedAt, new Date(now))) badge = { kind: 'preparing' };
    } else if (b.vodExpiresAt && Date.parse(b.vodExpiresAt) > now) {
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
      href: expired ? null : `/broadcast/vod/${encodeURIComponent(b.streamId)}`,
      badge,
      duration,
      thumbnailUrl: b.thumbnailUrl ?? null,
    };
  });

  // 목록 순서를 믿지 않고 시작 시각으로 가장 최근 것을 고른다
  const latestStart = pastAll
    .map((b) => b.startedAt)
    .filter((t): t is string => t !== null)
    .sort((a, b) => Date.parse(b) - Date.parse(a))[0];

  return {
    live,
    vods,
    hasHistory: liveBroadcast !== null || pastAll.length > 0,
    week: weekOf(weekStart, Infinity, true),
    lastWeek: weekOf(lastWeekStart, weekStart),
    lastBroadcast: latestStart
      ? { date: dateLabel(latestStart), ago: daysAgoLabel(latestStart, new Date(now)) }
      : null,
    memory: { pastAll, pastReadAt, liveIds: [...liveIds], cardCounts },
  };
}

const EMPTY_WEEK: BroadcastWeek = { broadcasts: 0, cards: 0 };
const EMPTY: Loaded = {
  live: null,
  vods: [],
  hasHistory: false,
  week: EMPTY_WEEK,
  lastWeek: EMPTY_WEEK,
  lastBroadcast: null,
};

export function homeQueryOptions(streamerId: number | null) {
  const queryKey = ['home', streamerId] as const;
  return queryOptions({
    queryKey,
    queryFn: ({ client, signal }) =>
      loadHome(Date.now(), client.getQueryData<HomeData>(queryKey)?.memory, streamerId, signal),
    // 받아 둔 화면을 바로 보여 주고 뒤에서 다시 읽는다. 금방 돌아오면 읽지 않는다: 탭을 오갈 때마다 전체 목록을 읽지 않게
    staleTime: REVISIT_FRESH_MS,
    refetchInterval: POLL_MS,
    // 다음 주기가 곧 다시 읽는다
    retry: false,
  });
}

export function useHomeMockState(): HomeMockState {
  // 고른 채널(POK-390). ChannelScope가 채널이 바뀌면 화면을 다시 세우므로 마운트 동안은 안 바뀐다
  const streamerId = useChannelStreamerId();
  const home = useQuery(homeQueryOptions(streamerId));
  const data = home.data ?? EMPTY;
  // 주의 경계는 클라이언트 시계로 — 서버 렌더와 어긋나지 않게 첫 응답 뒤에만 쓴다
  const now = home.dataUpdatedAt;
  // 보관함(POK-243) — 클립 완료 수 · 만든 클립 · 유튜브 발행이 여기서 나온다. 못 읽으면 null(수는 「–」)
  const library =
    useQuery({ ...libraryQueryOptions(streamerId), refetchInterval: LIBRARY_POLL_MS }).data ?? null;
  const me = useQuery(meQueryOptions).data;
  // 연동 상태는 방송 폴링과 따로 1분마다. 설정 화면 · 라이브 잠금과 같은 캐시를 쓴다. 못 읽은 칸은 지난 값을 둔다(방송 목록과 같은 정책)
  const chzzk = useQuery({ ...chzzkLinkQueryOptions, refetchInterval: LINKS_POLL_MS });
  const soop = useQuery({ ...soopLinkQueryOptions, refetchInterval: LINKS_POLL_MS });
  const youtube = useQuery({ ...youtubeLinkQueryOptions, refetchInterval: LINKS_POLL_MS });
  const streamKey = useQuery({ ...streamKeyStatusQueryOptions, refetchInterval: LINKS_POLL_MS });
  const links = useMemo<HomeLinks>(
    () => ({
      chzzk: chzzk.data ?? null,
      soop: soop.data ?? null,
      youtube: youtube.data ?? null,
      streamKeyIssued: streamKey.data === undefined ? null : streamKey.data.issued === true,
    }),
    [chzzk.data, soop.data, youtube.data, streamKey.data],
  );
  // 연동 상태를 한 번은 물어봤는가 — 그 전에 준비 단계를 그리면 연동을 마친 계정에도 버튼이 잠깐 보인다
  const linksTried = chzzk.isFetched && soop.isFetched && youtube.isFetched && streamKey.isFetched;

  // 홈이 서버에서 읽은 연동 사실을 시작 가이드 체크에 올린다(다른 기기에서 연동한 경우). 해제는 설정 화면이 내린다
  useEffect(() => {
    const store = useOnboardingStore.getState();
    // 방송 채널은 치지직·SOOP 둘 중 하나면 된다(POK-396)
    if ((links.chzzk?.linked || links.soop?.linked) && !store.channelLinked)
      store.setChannelLinked(true);
    if (links.streamKeyIssued && !store.pluginLinked) store.markPluginLinked();
  }, [links]);

  const live =
    data.live === null
      ? null
      : { ...data.live, completedClips: completedClipsOf(library, data.live.streamId) };
  const { lastWeekStart, weekStart } = weekStarts(new Date(now));

  return {
    userName: me?.name?.trim() || me?.email || null,
    live,
    vods: data.vods,
    hasHistory: data.hasHistory,
    week: { ...data.week, ...libraryWeekOf(library, weekStart, Infinity) },
    lastWeek: { ...data.lastWeek, ...libraryWeekOf(library, lastWeekStart, weekStart) },
    lastBroadcast: data.lastBroadcast,
    links,
    loading: home.data === undefined || !linksTried,
  };
}

/**
 * 한 주의 만든 클립·유튜브 발행. 클립은 「가장 최근 영상이 완성」인 편집본을 그 영상을 주문한 시각으로 센다(라이브 띠의
 * 클립 완료와 같은 기준). 발행은 편집본마다 살아 있는 업로드 중 「올림」을 마지막으로 바뀐 시각으로 센다 — 새 판 영상을
 * 만들어도 옛 판 업로드가 남는다(POK-334, liveUploads).
 */
function libraryWeekOf(
  entries: readonly LibraryEntry[] | null,
  from: number,
  to: number,
): Pick<WeekStats, 'clips' | 'published'> {
  if (entries === null) return { clips: null, published: null };
  return {
    clips: entries.filter(
      (e) => e.latestClip?.status === 'rendered' && inRange(e.latestClip.createdAt, from, to),
    ).length,
    published: entries
      .flatMap(liveUploadsOf)
      .filter((u) => u.status === 'uploaded' && inRange(u.updatedAt, from, to)).length,
  };
}

/**
 * 라이브 방송에서 영상까지 만든 편집본 수. 편집본 상태가 아니라 <b>가장 최근 영상이 완성인가</b>로 센다: 영상을 만든 뒤 새 판을
 * 저장만 하면 편집본은 「편집 중」이지만 만든 영상은 그대로 있다. 새 판을 다시 만드는 중이면 목록 줄이
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
