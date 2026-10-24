'use client';

import { ApiError, apiFetch } from './client';
import { markPlaybackCookiesIssued } from './mediaPlayback';
import { notifyChannelGone } from '@/stores/channel';

// clip 서버 창구(POK-251). 전부 로그인 세션의 apiFetch로 간다(Bearer 부착·401 회전).
//   GET /api/clip/broadcasts?state=…            방송 목록 (startedAt 을 얻는 데 쓴다)
//   GET /api/clip/broadcasts/{id}/jump-cards    점프카드
//   GET /api/clip/broadcasts/{id}/segments      조각 장부
//   GET /api/clip/broadcasts/{id}/chat-messages 지난 방송 채팅 (절대 시각 창)
// 그 밖에 편집본·영상 주문·보관함(아래 절).

/** signal: 부른 화면이 내려가면 끊는다(목록 읽기들, 아래 fetchAll*) */
async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await clipFetch(path, signal ? { signal } : undefined);
  return res.json() as Promise<T>;
}

/**
 * 고른 채널로 묻는 목록 읽기(POK-390). 404면 그 채널을 더는 볼 수 없다는 뜻일 수 있어 신호를 남기고 그대로 던진다.
 * 판정을 화면 훅마다 두지 않고 여기 한 자리에 둔다: 홈은 실패를 null로 삼켜 훅까지 404가 안 올라온다.
 */
async function channelGetJson<T>(
  path: string,
  streamerId: number | null,
  signal?: AbortSignal,
): Promise<T> {
  try {
    return await getJson<T>(path, signal);
  } catch (e) {
    if (streamerId !== null && e instanceof ClipApiError && e.status === 404)
      notifyChannelGone(streamerId);
    throw e;
  }
}

export interface JumpCard {
  id: string;
  streamId: string;
  source: string;
  streamTimestampMs: number;
  window: { startMs: number; endMs: number };
  score: number | null;
  evidence: unknown;
  eventSeq: number | null;
  /** 카드 시점 장면 사진(POK-277). 목록 문만 싣는다. 통로(SSE)로 온 카드는 비어 있다 */
  thumbnailUrl?: string | null;
  createdAt: string;
}

export interface BroadcastRow {
  streamId: string;
  status: string;
  relation: string;
  startedAt: string | null;
  endedAt: string | null;
  vodExpiresAt: string | null;
  /**
   * 시각 기준점(POK-255) — 이 방송의 카드·조각 ms가 0이 되는 절대 시각(녹화 첫 조각). 조각이 아직 없으면 null이고,
   * 이 칸을 모르는 옛 서버면 빠져 온다. 둘 다 방송 시작 시각으로 대신한다(수십 초 어긋날 수 있다)
   */
  timelineOriginAt?: string | null;
  /**
   * 영상 경로의 키(POK-233) — 라이브·녹화 주소의 자리. 방송 번호가 회차 번호가 되면 이것과 갈린다. 이 칸을 모르는 옛 서버면
   * 빠져 오고, 그때는 방송 번호가 곧 영상 경로다({@link mediaStreamId})
   */
  ingestStreamId?: string | null;
  /**
   * 사진 주소(POK-277, 60분 미리서명). 방송 중이면 1분마다 바뀌는 최신 화면, 끝났으면 가장 크게 터진 카드 장면. 없거나 이 칸을
   * 모르는 옛 서버면 자리표시를 그린다
   */
  thumbnailUrl?: string | null;
  /**
   * 방송 제목과 카테고리(POK-259). 서버가 켜진 플랫폼 중에서 고른다(POK-397). 수집기가 1분마다 남기는 관측에서 제목이
   * 있는 마지막 값이다. 관측이 없거나, 수집기가 아프거나, 이 칸을 모르는 옛 서버면 비어 온다. 화면에 보일 이름은
   * {@link broadcastTitle}로 고른다
   */
  title?: string | null;
  category?: string | null;
  /**
   * 방송 플랫폼 코드(POK-398). `CHZZK`·`SOOP`. 화면에 보일 이름은 {@link platformLabel}로 고른다. 이 칸을 모르는 옛 서버면 빠져 온다
   */
  platform?: string | null;
  /**
   * 이 방송이 켜진 플랫폼과 플랫폼마다 시청자 수 · 관측 시각(POK-397). 순서는 치지직 → SOOP. 꺼진 플랫폼도 남으므로 무엇을 보일지는
   * features/broadcast/platformView가 관측 시각으로 고른다. 옛 서버면 빠져 온다
   */
  platforms?: { platform: string; viewers?: number | null; observedAt?: string | null }[];
  /**
   * 이 방송의 라이브 대시보드를 지금 열어 둔 편집자 수(POK-398). 스트리머 본인과 나는 빼고 센다. 방송 중 줄만 숫자고 끝난 줄은 null,
   * 옛 서버면 빠져 온다
   */
  editorsOnline?: number | null;
  /**
   * 이 방송 스트리머의 플랫폼 채널 이름(POK-430). 치지직 채널 이름 · SOOP 닉네임, 지금 연동된 것만이고 없으면 null. 옛 서버면 빠져 온다
   */
  channelNames?: { CHZZK: string | null; SOOP: string | null } | null;
}

/** 방송을 사람에게 보일 이름(POK-259). 방송 제목이 있으면 그것, 없으면 방송 번호 */
export function broadcastTitle(row: Pick<BroadcastRow, 'streamId' | 'title'>): string {
  return row.title?.trim() || row.streamId;
}

/** 플랫폼 코드를 화면 이름으로(POK-398). 모르는 코드면 null이다: 치지직이라고 지어내지 않고 태그를 숨긴다 */
export function platformLabel(code: string | null | undefined): string | null {
  if (code === 'CHZZK') return '치지직';
  if (code === 'SOOP') return 'SOOP';
  return null;
}

/** 영상 주소(라이브 LL-HLS·녹화 재생)를 만드는 키. 방송을 가리키는 번호(API 경로·자격)에는 쓰지 않는다 */
export function mediaStreamId(row: Pick<BroadcastRow, 'streamId' | 'ingestStreamId'>): string {
  return row.ingestStreamId || row.streamId;
}

/**
 * 카드·조각 ms의 0초가 되는 절대 시각(epoch ms). 서버 기준점이 정본이고(렌더가 자르는 축과 같다), 없으면 녹화 재생 서버의
 * 첫 구간, 그것도 없으면 방송 시작 시각으로 대신한다
 */
export function timelineBaseMs(
  row: Pick<BroadcastRow, 'startedAt' | 'timelineOriginAt'>,
  recordingStartMs: number | null = null,
): number {
  if (row.timelineOriginAt) return Date.parse(row.timelineOriginAt);
  if (recordingStartMs !== null) return recordingStartMs;
  return Date.parse(row.startedAt!);
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
 * 오래된 것부터 온다: PR #200 codex).
 */
export async function fetchAllJumpCards<T = JumpCard>(
  streamId: string,
  signal?: AbortSignal,
): Promise<T[]> {
  const all: T[] = [];
  let cursor: string | null = null;
  do {
    const qs = new URLSearchParams({ limit: String(CARD_PAGE) });
    if (cursor) qs.set('cursor', cursor);
    const page: { cards: T[]; nextCursor?: string | null } = await getJson(
      `/api/clip/broadcasts/${encodeURIComponent(streamId)}/jump-cards?${qs}`,
      signal,
    );
    all.push(...page.cards);
    cursor = page.nextCursor ?? null;
  } while (cursor !== null);
  return all;
}

export async function fetchJumpCard(streamId: string, cardId: string): Promise<JumpCard | null> {
  const cards = await fetchAllJumpCards(streamId);
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
  streamerId: number | null = null,
  signal?: AbortSignal,
): Promise<BroadcastRow[]> {
  const all: BroadcastRow[] = [];
  let cursor: string | null = null;
  do {
    const qs = new URLSearchParams({ state, limit: String(BROADCAST_PAGE) });
    if (streamerId !== null) qs.set('streamerId', String(streamerId));
    if (cursor) qs.set('cursor', cursor);
    const page: { broadcasts: BroadcastRow[]; nextCursor: string | null } = await channelGetJson(
      `/api/clip/broadcasts?${qs}`,
      streamerId,
      signal,
    );
    all.push(...page.broadcasts);
    if (stop(page.broadcasts)) break;
    cursor = page.nextCursor;
  } while (cursor !== null);
  return all;
}

/**
 * 그 상태의 방송 전부: 사람 규모라 몇 쪽이다.
 * @param streamerId 고른 채널(POK-390). null이면 볼 수 있는 채널 전부를 섞는다(옛 호출)
 * @param signal 부른 화면이 내려가면 끊는다: 남은 쪽을 계속 넘기면 채널 전환 화면이 옛 채널 요청까지 기다린다(PR #342 2차 리뷰)
 */
export function fetchAllBroadcasts(
  state: 'live' | 'past',
  streamerId: number | null = null,
  signal?: AbortSignal,
): Promise<BroadcastRow[]> {
  return walkBroadcasts(state, undefined, streamerId, signal);
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

/**
 * 편집기가 카드를 열었다고 남긴다(POK-393, 시범 운영 측정 재료). 실패는 삼킨다: 기록 하나 때문에 편집을 막지 않는다.
 * 네트워크 오류도 같이 삼킨다(clipFetch가 그대로 던진다)
 */
export async function openJumpCard(cardId: string): Promise<void> {
  try {
    await clipFetch(`/api/clip/jump-cards/${encodeURIComponent(cardId)}/open`, { method: 'POST' });
  } catch {
    // 기록 실패는 무시한다
  }
}

/**
 * 수동 마킹 본문(POK-392). 라이브는 누른 때·보낸 때(브라우저 시계 epoch ms)를 보내 서버가 시각을 정하고, 지난 방송은
 * 기준점 축 재생 위치(정수 ms)를 보낸다. 둘 중 하나만 보낸다(섞으면 서버가 400)
 */
export type WebMarkBody =
  { eventId: string; pressedAt: number; sentAt: number } | { eventId: string; positionMs: number };

/**
 * 방송 화면의 수동 마킹(POK-392). 201은 새 카드, 200은 같은 eventId 카드이거나 20초 안의 앞 카드다(둘 다 카드 모양).
 * 거절은 ClipApiError로 던진다: 503 timeline_not_ready(기준점 아직 없음) · 400 invalid_request(field) · 404 broadcast_not_found.
 * 다시 보낼 때는 같은 eventId를 쓴다(서버가 같은 카드를 준다)
 */
export async function postWebMark<T = JumpCard>(
  streamId: string,
  body: WebMarkBody,
): Promise<{ status: number; card: T }> {
  const res = await clipFetch(`/api/clip/broadcasts/${encodeURIComponent(streamId)}/marks`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
  return { status: res.status, card: (await res.json()) as T };
}

async function sendJson<T>(method: 'POST' | 'PUT', path: string, body?: unknown): Promise<T> {
  const res = await clipFetch(path, {
    method,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  return res.json() as Promise<T>;
}

/** 계약6 본문 — 칸 이름을 한 글자도 바꾸지 않는다. v1(출력마다 crop 하나)과 v2(층·바탕·구분선, 7절)가 있다. */
export type RecipeDocument = RecipeDocumentV1 | RecipeDocumentV2;

interface RecipeCommon {
  streamId: string;
  cut: { inAtMs: number; outAtMs: number } | null;
  audio: { tracks: { trackId: number; gain: number }[] };
}

/** 정규화 사각형 — 원본에서 자를 자리(crop)이자 결과에 놓을 자리(box) */
export interface RecipeRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

type RecipeAspect = 'VERT_9_16' | 'SQUARE_1_1';

export interface RecipeSubtitles {
  mode: 'BURN_AND_CC' | 'BURN_ONLY' | 'CC_ONLY';
  segments: { startAtMs: number; endAtMs: number; text: string }[];
  /** v2만. anchor 쪽 가장자리가 결과 높이의 y에 온다 */
  position?: { anchor: 'TOP' | 'MIDDLE' | 'BOTTOM'; y: number };
}

/** 이미 저장된 편집본 — 편집기는 이제 v2로만 저장한다 */
export interface RecipeDocumentV1 extends RecipeCommon {
  schemaVersion: 1;
  outputs: { outputId: string; aspect: RecipeAspect; crop: RecipeRect }[];
  /** 생략 = 자막 없음(계약6). 보낼 때는 칸을 빼고, clip 응답에는 null 로 온다 */
  subtitles?: Omit<RecipeSubtitles, 'position'> | null;
}

export type RecipeBackground =
  { kind: 'BLUR'; strength: number } | { kind: 'COLOR'; color: string };

/** 계약6 7절 — 결과를 바탕 위에 층을 차례로 얹고 구분선을 그어 만든다. 길이는 결과 폭 비, 색은 #RRGGBB */
export interface RecipeOutputV2 {
  outputId: string;
  aspect: RecipeAspect;
  /** 생략 = 검정 */
  background?: RecipeBackground;
  layers: {
    crop: RecipeRect;
    box: RecipeRect;
    frame?: { width: number; color: string; radius: number; shadow: boolean };
  }[];
  dividers?: { y: number; thickness: number; color: string }[];
}

export interface RecipeDocumentV2 extends RecipeCommon {
  schemaVersion: 2;
  outputs: RecipeOutputV2[];
  /** 보낼 때는 칸을 빼고, clip 응답에는 자막이 없으면 null 로 온다 */
  subtitles?: RecipeSubtitles | null;
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
  /**
   * 이 영상의 판(recipeId·recipeVersion)에 남겨 둔 업로드 정보(POK-291). 있으면 렌더가 끝나는 대로 clip이 저절로 올린다.
   * 없으면 null, 이 칸을 모르는 옛 서버면 빠져 온다
   */
  uploadRequest?: UploadRequestSummary | null;
}

export type PrivacyStatus = 'private' | 'unlisted' | 'public';
export type ThumbnailSource = 'none' | 'scene' | 'file';

/** 업로드 정보의 요약(POK-291). 설명·태그는 목록이 무거워져 싣지 않는다 */
export interface UploadRequestSummary {
  title: string;
  privacyStatus: PrivacyStatus;
  thumbnailSource: ThumbnailSource;
  /** 고른 분류(POK-381). 창을 다시 열 때 되살린다. 옛 서버면 빠져 온다 */
  categoryId?: string;
  /** 예약 공개 시각(ISO-8601, POK-381). 예약이 아니면 null, 옛 서버면 빠져 온다 */
  publishAt?: string | null;
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
  /** 아래 넷은 POK-291에 생겼다. 옛 서버면 빠져 온다(그때는 비공개·썸네일 없음이었다) */
  privacyStatus?: PrivacyStatus;
  madeForKids?: boolean;
  tags?: string[];
  /** 썸네일. status: none(안 고름) · pending(붙이는 중) · set(붙음) · failed(못 붙임, errorCode에 사유) */
  thumbnail?: {
    source: ThumbnailSource;
    status: 'none' | 'pending' | 'set' | 'failed';
    errorCode: string | null;
  };
  /** 「확인 필요」를 사람이 풀었으면 그 사람과 시각(POK-333). 일꾼이 끝냈거나 옛 서버면 null · 빠져 온다 */
  resolvedBy?: string | null;
  resolvedAt?: string | null;
  /** 고른 유튜브 분류와 예약 공개 시각(POK-381). 예약이 아니면 publishAt은 null, 옛 서버면 빠져 온다 */
  categoryId?: string;
  publishAt?: string | null;
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
  /** latestClip의 사진(POK-277). 영상 구간 안 가장 크게 터진 장면, 없으면 가운데 */
  thumbnailUrl?: string | null;
  /** 이 편집본 **지금 판**에 남겨 둔 업로드 정보(POK-291). 없으면 null, 옛 서버면 빠져 온다 */
  uploadRequest?: UploadRequestSummary | null;
  /**
   * 이 편집본 **모든 판**의 살아 있는(실패 아닌) 업로드, 최근 것이 앞(POK-334). 새 판 영상을 주문해 latestClip이 바뀌어도
   * 옛 판으로 올린 영상이 여기 남는다. 옛 서버면 빠져 온다: liveUploadsOf로 읽는다
   */
  liveUploads?: LiveUpload[];
}

/** 보관함 줄의 살아 있는 업로드 하나(POK-334). status는 queued·uploading·checking·uploaded */
export interface LiveUpload {
  id: number;
  /** 이 업로드가 올린 영상 번호(옛 판이면 latestClip과 다르다) */
  clipId: number;
  recipeVersion: number;
  outputId: string;
  title: string;
  status: string;
  videoId: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 살아 있는 업로드 목록. 칸을 모르는 옛 서버면 latestClip의 업로드 하나로 접는다(실패는 뺀다) */
export function liveUploadsOf(entry: LibraryEntry): LiveUpload[] {
  if (entry.liveUploads !== undefined) return entry.liveUploads;
  const clip = entry.latestClip;
  const upload = clip?.upload;
  if (clip == null || upload == null || upload.status === 'failed') return [];
  return [
    {
      id: upload.id,
      clipId: upload.clipId,
      recipeVersion: clip.recipeVersion,
      outputId: upload.outputId,
      title: upload.title,
      status: upload.status,
      videoId: upload.videoId,
      createdAt: upload.createdAt,
      updatedAt: upload.updatedAt,
    },
  ];
}

export interface LibraryDetail extends LibraryEntry {
  recipe: RecipeDocument;
}

/**
 * 첫 저장. 카드에서 연 편집본이면 그 카드 번호를 쿼리로 싣는다(POK-338, 「카드 → 영상」 시간 측정 재료).
 * 계약6 본문을 안 바꾸려고 쿼리다. 서버는 이 방송의 카드가 아니면 출처만 비우고 저장한다.
 */
export function createRecipe(
  streamId: string,
  doc: RecipeDocument,
  sourceCardId: string | null = null,
): Promise<RecipeSnapshot> {
  const query = sourceCardId === null ? '' : `?sourceCardId=${encodeURIComponent(sourceCardId)}`;
  return sendJson(
    'POST',
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/recipes${query}`,
    doc,
  );
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

/**
 * 「영상 만들기」 창에서 고른 유튜브 업로드 정보(POK-291). 칸 이름은 clip 렌더 주문 문의 `upload`와 한 글자도 다르지 않다.
 * 썸네일 이미지 파일은 JSON에 못 싣는다: source가 file이면 파일은 multipart의 thumbnail 파트로 따로 간다.
 */
export interface UploadInfo {
  title: string;
  description: string;
  tags: string[];
  /** 유튜브 분류 번호(POK-381). 빠지면 20(게임) */
  categoryId: string;
  privacyStatus: PrivacyStatus;
  /** 예약 공개 시각(ISO-8601, POK-381). 있으면 privacyStatus는 private다. 예약이 아니면 칸을 안 싣는다 */
  publishAt?: string;
  madeForKids: boolean;
  thumbnail: { source: 'none' } | { source: 'scene'; offsetMs: number } | { source: 'file' };
}

/**
 * 영상 주문. 201 새 주문 · 200 같은 판이 이미 진행 중이거나 이미 만들어져 있다(그것을 돌려준다). 409 source_not_ready 는
 * 조각이 덜 올라온 것.
 *
 * `upload`를 주면 렌더가 끝나는 대로 clip이 그 정보로 유튜브에 올린다(POK-291). 본문은 multipart다: request 파트(JSON
 * `{upload}`) + 이미지를 고른 경우 thumbnail 파트. 거절은 400 invalid_request(field) · 409 already_uploaded/youtube_not_linked ·
 * 413 payload_too_large · 415 unsupported_image · 503 upload_unavailable/thumbnail_store_unavailable.
 */
export async function requestRender(
  streamId: string,
  recipeId: number,
  upload?: UploadInfo,
  thumbnailFile?: Blob | null,
): Promise<{ created: boolean; clip: ClipSnapshot }> {
  let body: FormData | undefined;
  if (upload !== undefined) {
    body = new FormData();
    body.append('request', new Blob([JSON.stringify({ upload })], { type: 'application/json' }));
    // 이미지를 고른 경우에만 싣는다: 그 밖에 파일이 오면 서버가 400(field=thumbnail)으로 거절한다
    if (upload.thumbnail.source === 'file' && thumbnailFile)
      body.append('thumbnail', thumbnailFile);
  }
  const res = await clipFetch(
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/recipes/${recipeId}/renders`,
    { method: 'POST', body },
  );
  // 200이면 새로 주문한 것이 아니다: 부른 쪽이 「새로 만든다」고 말하지 않게 가른다
  return { created: res.status === 201, clip: (await res.json()) as ClipSnapshot };
}

export function fetchClip(streamId: string, clipId: number): Promise<ClipSnapshot> {
  return getJson(`/api/clip/broadcasts/${encodeURIComponent(streamId)}/clips/${clipId}`);
}

/**
 * 유튜브 업로드 주문(POK-220). 201 새 주문 · 200 같은 영상·출력의 살아 있는 업로드가 이미 있다(그것을 돌려준다).
 * 400 invalid_request(field) · 409 clip_not_rendered/already_uploaded · 503 upload_unavailable/authorization_unavailable.
 * 방송 주인(스트리머)의 채널에 올라간다. 공개 범위를 안 보내면 비공개다(업로드 정보 창 없이 만든 옛 영상의 길).
 */
export async function requestUpload(
  streamId: string,
  clipId: number,
  body: { title: string; outputId?: string },
): Promise<{ created: boolean; upload: UploadSnapshot }> {
  const res = await clipFetch(
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/clips/${clipId}/uploads`,
    { method: 'POST', body: JSON.stringify(body) },
  );
  // 200이면 보낸 제목이 아니라 먼저 주문된 업로드다 — 부른 쪽이 「새로 시작했다」고 말하지 않게 가른다
  return { created: res.status === 201, upload: (await res.json()) as UploadSnapshot };
}

/**
 * 실패한 업로드를 저장된 정보 그대로 다시 올린다(POK-291, 창 없이). 업로드 줄이 아직 없으면 그 판의 업로드 정보(영상 만들기
 * 창에서 고른 것)로 올린다. 201 새로 시작 · 200 이미 살아 있는 업로드가 있다(그것을 돌려준다). 409 nothing_to_retry(올린 적도
 * 업로드 정보도 없음)/already_uploaded(같은 판의 다른 영상이 올라갔다) · 503 upload_unavailable.
 */
export async function retryUpload(
  streamId: string,
  clipId: number,
): Promise<{ created: boolean; upload: UploadSnapshot }> {
  const res = await clipFetch(
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/clips/${clipId}/uploads/retry`,
    { method: 'POST' },
  );
  return { created: res.status === 201, upload: (await res.json()) as UploadSnapshot };
}

/** 「확인 필요」 업로드를 사람이 푸는 결과(POK-333). videoId는 영상 번호 그대로이거나 그 영상의 유튜브 주소 */
export type UploadResolution = { result: 'uploaded'; videoId: string } | { result: 'not_uploaded' };

/**
 * 「확인 필요」 업로드를 채널에서 본 결과로 푼다(POK-333). 200 푼 업로드(같은 결과를 다시 보내도 200). 400 invalid_request
 * (result · videoId) · 404 · 409 upload_not_checking(이미 다른 결과로 풀렸거나 일꾼이 끝냄)/resolution_too_early(「안 올라갔다」는
 * 마지막 일꾼 활동에서 30분 뒤부터).
 */
export function resolveUpload(
  streamId: string,
  clipId: number,
  uploadId: number,
  body: UploadResolution,
): Promise<UploadSnapshot> {
  return sendJson(
    'POST',
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/clips/${clipId}/uploads/${uploadId}/resolution`,
    body,
  );
}

/** 완성 영상 파일 주소(POK-220). url은 60분짜리 서명 주소라 재생·내려받기에 같이 쓴다. 만료(403)면 다시 부른다 */
export interface ClipFileAccess {
  clipId: number;
  expiresAt: string;
  files: { outputId: string; kind: 'video' | 'srt'; fileName: string; url: string }[];
}

/** 409 clip_not_rendered 는 아직 영상이 없는 것 */
export function requestFileAccess(streamId: string, clipId: number): Promise<ClipFileAccess> {
  return sendJson(
    'POST',
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/clips/${clipId}/file-access`,
  );
}

/**
 * 원본 조각 주소(POK-389): 편집기가 트랙별로 미리 들으려고 구간의 녹화 조각(방송 소리 트랙이 전부 든 파일)을 받는다.
 * url은 5분짜리 서명 주소라 받자마자 내려받는다. startAtMs는 재생 축(편집본 cut과 같은 축)이고, 올라간 조각만 온다.
 * 400 {field: fromMs|toMs}(6분 넘는 구간 등) · 404 · 410 vod_expired · 503 render_unavailable.
 */
export interface SegmentAudioAccess {
  expiresAt: string;
  segments: { seq: number; startAtMs: number; durationMs: number; url: string }[];
}

export function requestSegmentAudioAccess(
  streamId: string,
  fromMs: number,
  toMs: number,
): Promise<SegmentAudioAccess> {
  const qs = new URLSearchParams({ fromMs: String(fromMs), toMs: String(toMs) });
  return sendJson(
    'POST',
    `/api/clip/broadcasts/${encodeURIComponent(streamId)}/segments/audio-access?${qs}`,
  );
}

/**
 * 내가 볼 수 있는 방송들의 편집본 전부: 한 장씩 이어받아 끝까지 모은다(사람 규모라 몇 장이다).
 * @param streamerId 고른 채널(POK-390). null이면 섞어서
 * @param signal 부른 화면이 내려가면 끊는다(fetchAllBroadcasts와 같다)
 */
export async function fetchLibraryAll(
  streamerId: number | null = null,
  signal?: AbortSignal,
): Promise<LibraryEntry[]> {
  const items: LibraryEntry[] = [];
  let cursor: string | null = null;
  do {
    const qs = new URLSearchParams({ limit: '100' });
    if (streamerId !== null) qs.set('streamerId', String(streamerId));
    if (cursor) qs.set('cursor', cursor);
    const page: { items: LibraryEntry[]; nextCursor: string | null } = await channelGetJson(
      `/api/clip/library?${qs}`,
      streamerId,
      signal,
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
 * 영상 출입증(POK-122) — CloudFront 서명 쿠키를 받아 둔다. 로컬 media는 쿠키를 안 보지만 CDN 뒤에서는 이것이 없으면
 * 조각을 못 받는다. 서명 재료가 없는 서버는 503이고 그때는 그냥 넘어간다(로컬·dev).
 */
/** 출입증 결과 — 받았으면 만료 시각(모르면 null), 서명 재료가 없는 서버(503)면 필요 없음, 그 밖은 실패 */
export type PlaybackAccessResult =
  | { kind: 'granted'; expiresAtMs: number | null }
  | { kind: 'not_needed' }
  | { kind: 'failed' }
  /** 보관 기간(60일)이 지난 방송(410 vod_expired, POK-335). 조각을 지우므로 다시 받아도 같다 */
  | { kind: 'expired' };

/** 문 한 번 부르기. 받아 둔 출입증 · 다시 쓰기 · 403 처리는 playbackAccess.ts 한 곳이 한다(POK-317) */
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
    markPlaybackCookiesIssued();
    return { kind: 'granted', expiresAtMs };
  } catch (e) {
    // 「필요 없음」은 서명 재료가 없는 503뿐이다. 자격 확인이 안 되는 503(authorization_unavailable)도 필요 없음으로 접으면
    // auth가 잠깐 멈춘 사이 연 화면이 다시 묻지 않아 prod 재생이 403으로 굳었다(POK-317)
    if (e instanceof ApiError && e.status === 503 && e.code === 'playback_signing_unavailable')
      return { kind: 'not_needed' };
    if (e instanceof ApiError && e.status === 410 && e.code === 'vod_expired')
      return { kind: 'expired' };
    return { kind: 'failed' };
  }
}
