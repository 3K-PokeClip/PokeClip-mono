'use client';

import { ApiError, apiFetch } from './client';

// clip 서버 창구(POK-251). 전부 로그인 세션의 apiFetch로 간다(Bearer 부착·401 회전).
//   GET /api/clip/broadcasts?state=…            방송 목록 (startedAt 을 얻는 데 쓴다)
//   GET /api/clip/broadcasts/{id}/jump-cards    점프카드
//   GET /api/clip/broadcasts/{id}/segments      조각 장부
//   GET /api/clip/broadcasts/{id}/chat-messages 지난 방송 채팅 (절대 시각 창)
// 그 밖에 편집본·영상 주문·보관함(아래 절).

async function getJson<T>(path: string): Promise<T> {
  const res = await clipFetch(path);
  return res.json() as Promise<T>;
}

export interface JumpCard {
  id: string;
  streamId: string;
  source: string;
  streamTimestampMs: number;
  window: { startMs: number; endMs: number };
  score: number | null;
  evidence: unknown;
  claimedBy: string | null;
  hidden: boolean;
  eventSeq: number | null;
  createdAt: string;
}

export interface BroadcastRow {
  streamId: string;
  status: string;
  relation: string;
  startedAt: string | null;
  endedAt: string | null;
  vodExpiresAt: string | null;
}

export interface SegmentIndex {
  complete: boolean;
  availableFromMs: number | null;
  availableUntilMs: number | null;
  segments: { seq: number; startPtsMs: number; durationMs: number; discontinuity: boolean }[];
}

export interface ChatMessage {
  kind: string;
  id: string;
  /** ISO 절대 시각 */
  time: string;
  nickname: string;
  text: string;
  amount: number | null;
}

/** 카드 목록 한 쪽의 최대 크기 — clip JumpCardService.MAX_LIST_LIMIT */
const CARD_PAGE = 200;

/**
 * 방송의 카드를 쪽마다 끝까지 읽는다. 한 쪽만 읽으면 51번째 카드부터 없는 것이 된다(기본 쪽 크기 50,
 * 오래된 것부터 온다 — PR #200 codex). `includeHidden`이면 숨긴 카드도 받는다(숨김 반영·편집기 열기).
 */
export async function fetchAllJumpCards<T = JumpCard>(
  streamId: string,
  { includeHidden = false }: { includeHidden?: boolean } = {},
): Promise<T[]> {
  const all: T[] = [];
  let cursor: string | null = null;
  do {
    const qs = new URLSearchParams({ limit: String(CARD_PAGE) });
    if (includeHidden) qs.set('includeHidden', 'true');
    if (cursor) qs.set('cursor', cursor);
    const page: { cards: T[]; nextCursor?: string | null } = await getJson(
      `/api/clip/broadcasts/${encodeURIComponent(streamId)}/jump-cards?${qs}`,
    );
    all.push(...page.cards);
    cursor = page.nextCursor ?? null;
  } while (cursor !== null);
  return all;
}

export async function fetchJumpCard(streamId: string, cardId: string): Promise<JumpCard | null> {
  // 숨긴 카드도 편집기로는 열 수 있다 — 숨김은 목록에서 빼는 것이지 지우는 것이 아니다
  const cards = await fetchAllJumpCards(streamId, { includeHidden: true });
  return cards.find((card) => String(card.id) === cardId) ?? null;
}

/** 방송 목록 한 쪽의 최대 크기 — clip BroadcastListService.MAX_LIMIT */
const BROADCAST_PAGE = 100;

/**
 * 방송 목록을 쪽마다 넘기며 읽는다. `stop`이 참을 돌려주면 거기서 멈춘다(찾던 줄을 만났을 때).
 * 한 쪽만 읽으면 오래된 방송이 「없다」로 보인다 — 기본 쪽 크기가 20이다(POK-251 리뷰).
 */
async function walkBroadcasts(
  state: 'live' | 'past',
  stop: (rows: BroadcastRow[]) => boolean = () => false,
): Promise<BroadcastRow[]> {
  const all: BroadcastRow[] = [];
  let cursor: string | null = null;
  do {
    const qs = new URLSearchParams({ state, limit: String(BROADCAST_PAGE) });
    if (cursor) qs.set('cursor', cursor);
    const page: { broadcasts: BroadcastRow[]; nextCursor: string | null } = await getJson(
      `/api/clip/broadcasts?${qs}`,
    );
    all.push(...page.broadcasts);
    if (stop(page.broadcasts)) break;
    cursor = page.nextCursor;
  } while (cursor !== null);
  return all;
}

/** 그 상태의 방송 전부 — 사람 규모라 몇 쪽이다 */
export function fetchAllBroadcasts(state: 'live' | 'past'): Promise<BroadcastRow[]> {
  return walkBroadcasts(state);
}

/** 방송 한 줄 — 라이브 목록에서 먼저 찾고 없으면 지난 방송 목록을 끝까지 본다. */
export async function fetchBroadcast(streamId: string): Promise<BroadcastRow | null> {
  for (const state of ['live', 'past'] as const) {
    let found: BroadcastRow | undefined;
    await walkBroadcasts(state, (rows) => {
      found = rows.find((b) => b.streamId === streamId);
      return found !== undefined;
    });
    if (found !== undefined) return found;
  }
  return null;
}

export function fetchSegments(
  streamId: string,
  startMs: number,
  endMs: number,
): Promise<SegmentIndex> {
  return getJson<SegmentIndex>(
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/segments?startMs=${startMs}&endMs=${endMs}`,
  );
}

export async function fetchChatMessages(
  streamId: string,
  fromIso: string,
  toIso: string,
): Promise<{ items: ChatMessage[]; appliedOffsetMs: number }> {
  const qs = new URLSearchParams({ from: fromIso, to: toIso, limit: '200' });
  return getJson(`/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-messages?${qs}`);
}

// ── 편집본(레시피)·영상 주문·보관함 — 2026-09-15 clip 문들 (POK-124·125·243) ──

/** 상태 코드와 서버 봉투를 들고 가는 실패 — 409 source_not_ready 같은 갈래를 화면이 가른다. */
export class ClipApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string | null,
    public readonly field: string | null,
  ) {
    super(code ? `${status} ${code}${field ? ` (${field})` : ''}` : `요청이 실패했다 (${status})`);
    this.name = 'ClipApiError';
  }
}

/**
 * clip 창구 공통 호출. apiFetch의 ApiError를 ClipApiError로 옮겨 사유 코드(409 source_not_ready 등)를
 * 화면이 가를 수 있게 한다. 네트워크 오류는 그대로 던진다.
 */
export async function clipFetch(path: string, init?: RequestInit): Promise<Response> {
  try {
    return await apiFetch(path, init);
  } catch (e) {
    if (e instanceof ApiError) throw new ClipApiError(e.status, e.code, e.field);
    throw e;
  }
}

async function sendJson<T>(method: 'POST' | 'PUT', path: string, body?: unknown): Promise<T> {
  const res = await clipFetch(path, {
    method,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  return res.json() as Promise<T>;
}

/** 계약6 rev7 본문 — 칸 이름을 한 글자도 바꾸지 않는다. */
export interface RecipeDocument {
  schemaVersion: 1;
  streamId: string;
  cut: { inAtMs: number; outAtMs: number } | null;
  outputs: {
    outputId: string;
    aspect: 'VERT_9_16' | 'SQUARE_1_1';
    crop: { x: number; y: number; w: number; h: number };
  }[];
  audio: { tracks: { trackId: number; gain: number }[] };
  /** 생략 = 자막 없음(계약6). null 로 보내지 않고 칸을 뺀다 */
  subtitles?: {
    mode: 'BURN_AND_CC' | 'BURN_ONLY' | 'CC_ONLY';
    segments: { startAtMs: number; endAtMs: number; text: string }[];
  };
}

export interface RecipeSnapshot {
  id: number;
  streamId: string;
  creatorId: string;
  recipeVersion: number;
  recipe: RecipeDocument;
  createdAt: string;
  updatedAt: string;
}

/** 주문 문의 완성 영상 봉투(POK-125). 보관함 줄의 latestClip도 같은 모양이다. */
export interface ClipSnapshot {
  id: number;
  streamId: string;
  recipeId: number;
  recipeVersion: number;
  requestedBy: string;
  status: 'queued' | 'rendering' | 'rendered' | 'failed';
  progress: { percent: number; stage: string | null; attempt: number; jobId: string } | null;
  outputs: { outputId: string; kind: string; s3Key: string }[] | null;
  error: { code: string; message: string | null } | null;
  createdAt: string;
  updatedAt: string;
  /** 가장 최근 유튜브 업로드(POK-220). 한 번도 안 올렸으면 null. 옛 응답에는 칸이 없다 */
  upload?: UploadSnapshot | null;
}

/** 유튜브 업로드 한 건(POK-220). 주소는 https://youtu.be/{videoId} */
export interface UploadSnapshot {
  id: number;
  clipId: number;
  outputId: string;
  title: string;
  status: string;
  videoId: string | null;
  error: { code: string; message: string | null } | null;
  requestedBy: string;
  createdAt: string;
  updatedAt: string;
}

/** 보관함 상태 일곱(clip LibraryStatus). 뒤의 셋은 유튜브 업로드(POK-220) */
export type LibraryStatus =
  'editing' | 'rendering' | 'rendered' | 'failed' | 'uploading' | 'checking' | 'uploaded';

/** 보관함 목록 한 줄(POK-243). 상세는 여기에 recipe(계약6 본문)가 얹힌다. */
export interface LibraryEntry {
  recipeId: number;
  streamId: string;
  creatorId: string;
  recipeVersion: number;
  cut: { inAtMs: number; outAtMs: number } | null;
  status: LibraryStatus;
  broadcast: {
    status: string;
    startedAt: string | null;
    endedAt: string | null;
    vodExpiresAt: string | null;
  };
  latestClip: ClipSnapshot | null;
  createdAt: string;
  updatedAt: string;
}

export interface LibraryDetail extends LibraryEntry {
  recipe: RecipeDocument;
}

export function createRecipe(streamId: string, doc: RecipeDocument): Promise<RecipeSnapshot> {
  return sendJson('POST', `/api/clip/broadcasts/${encodeURIComponent(streamId)}/recipes`, doc);
}

export function updateRecipe(
  streamId: string,
  recipeId: number,
  doc: RecipeDocument,
): Promise<RecipeSnapshot> {
  return sendJson(
    'PUT',
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/recipes/${recipeId}`,
    doc,
  );
}

export function fetchRecipe(streamId: string, recipeId: number): Promise<RecipeSnapshot> {
  return getJson(`/api/clip/broadcasts/${encodeURIComponent(streamId)}/recipes/${recipeId}`);
}

/** 201 새 주문 · 200 같은 판이 이미 진행 중(그것을 돌려준다). 409 source_not_ready 는 조각이 덜 올라온 것. */
export function requestRender(streamId: string, recipeId: number): Promise<ClipSnapshot> {
  return sendJson(
    'POST',
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/recipes/${recipeId}/renders`,
  );
}

export function fetchClip(streamId: string, clipId: number): Promise<ClipSnapshot> {
  return getJson(`/api/clip/broadcasts/${encodeURIComponent(streamId)}/clips/${clipId}`);
}

/** 내가 볼 수 있는 방송들의 편집본 전부 — 한 장씩 이어받아 끝까지 모은다(사람 규모라 몇 장이다). */
export async function fetchLibraryAll(): Promise<LibraryEntry[]> {
  const items: LibraryEntry[] = [];
  let cursor: string | null = null;
  do {
    const qs = new URLSearchParams({ limit: '100' });
    if (cursor) qs.set('cursor', cursor);
    const page: { items: LibraryEntry[]; nextCursor: string | null } = await getJson(
      `/api/clip/library?${qs}`,
    );
    items.push(...page.items);
    cursor = page.nextCursor;
  } while (cursor !== null);
  return items;
}

export function fetchLibraryDetail(recipeId: number): Promise<LibraryDetail> {
  return getJson(`/api/clip/library/${recipeId}`);
}

export interface Me {
  id: number | string;
  name?: string | null;
  email?: string | null;
}

export async function fetchMeLoose(): Promise<Me | null> {
  try {
    return await getJson<Me>('/api/auth/me');
  } catch {
    return null;
  }
}

// ── 편집기가 계약6 본문을 조립하는 규칙 ──

/** 컷 길이 한도(계약6 · kty 확정 2026-08-24): 5초–3분. */
export const CUT_MIN_MS = 5_000;
export const CUT_MAX_MS = 180_000;

/**
 * 카드 창(방송 시작 기준 ms)을 계약6 컷(절대 UTC epoch ms)으로. 5초보다 짧으면 뒤를 늘리고
 * 3분보다 길면 뒤를 자른다 — 저장 문이 그 밖을 400으로 거절하므로 화면에서 먼저 맞춘다.
 */
export function cutFromWindow(
  startedAtIso: string,
  startMs: number,
  endMs: number,
): { inAtMs: number; outAtMs: number } {
  const base = Date.parse(startedAtIso);
  const inAtMs = base + Math.max(0, startMs);
  const length = Math.min(CUT_MAX_MS, Math.max(CUT_MIN_MS, endMs - startMs));
  return { inAtMs, outAtMs: inAtMs + length };
}

/**
 * 세로 쇼츠 한 벌 + 최종 믹스(track 0) — 편집기 UI가 레이아웃·트랙을 실제로 고르기 전의 기본 조립.
 * crop 은 16:9 소스의 가운데 9:16 창(w = 9/16 ÷ 16/9). 종횡비 픽셀식(±1%)은 3층(렌더)이 판정한다.
 */
export function defaultRecipe(
  streamId: string,
  cut: { inAtMs: number; outAtMs: number } | null,
): RecipeDocument {
  const w = 9 / 16 / (16 / 9);
  return {
    schemaVersion: 1,
    streamId,
    cut,
    outputs: [{ outputId: 'o1', aspect: 'VERT_9_16', crop: { x: (1 - w) / 2, y: 0, w, h: 1 } }],
    audio: { tracks: [{ trackId: 0, gain: 1.0 }] },
  };
}

/**
 * 영상 출입증(POK-122) — CloudFront 서명 쿠키를 받아 둔다. 로컬 media는 쿠키를 안 보지만 CDN 뒤에서는 이것이 없으면
 * 조각을 못 받는다. 서명 재료가 없는 서버는 503이고 그때는 그냥 넘어간다(로컬·dev).
 */
/** 출입증 결과 — 받았으면 만료 시각(모르면 null), 서명 재료가 없는 서버(503)면 필요 없음, 그 밖은 실패 */
export type PlaybackAccessResult =
  { kind: 'granted'; expiresAtMs: number | null } | { kind: 'not_needed' } | { kind: 'failed' };

export async function requestPlaybackAccess(streamId: string): Promise<PlaybackAccessResult> {
  try {
    const res = await apiFetch(
      `/api/clip/broadcasts/${encodeURIComponent(streamId)}/playback-access`,
      {
        method: 'POST',
        credentials: 'include',
      },
    );
    let expiresAtMs: number | null = null;
    try {
      const body = (await res.json()) as { expiresAt?: unknown };
      if (typeof body.expiresAt === 'string' && Number.isFinite(Date.parse(body.expiresAt)))
        expiresAtMs = Date.parse(body.expiresAt);
    } catch {
      /* 본문이 없으면 만료를 모른다 — 부른 쪽이 기본 간격으로 다시 받는다 */
    }
    return { kind: 'granted', expiresAtMs };
  } catch (e) {
    if (e instanceof ApiError && e.status === 503) return { kind: 'not_needed' };
    return { kind: 'failed' };
  }
}
