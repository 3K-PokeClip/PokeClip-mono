import type { PrivacyStatus, UploadSnapshot } from '@/api/clipEditor';
import type { YoutubeCategoryId } from '@/lib/analytics';

// 업로드 정보 창(POK-291)의 규칙: 렌더와 떼어 순수 함수로 둔다. clip 렌더 주문 문이 같은 규칙으로 400을 주지만,
// 보내기 전에 창 안에서 말해야 그 칸을 바로 고친다. 제목 규칙은 보관함의 uploadTitleProblem을 그대로 쓴다.

/** 공개 범위 셋. 창과 보관함이 같은 이름을 쓴다 */
export const PRIVACY_LABEL: Record<PrivacyStatus, string> = {
  private: '비공개',
  unlisted: '일부 공개',
  public: '공개',
};

/**
 * 공개 범위마다 누가 보나. 창과 보관함이 이 말로 문장을 만든다. 고른 범위 그대로 올라간다는 근거와 단서(유튜브 문서 둘이 어긋나고
 * 실측은 테스트 상태 앱 하나다)는 services/README.md 업로드 절에 있다
 */
export const PRIVACY_VIEWERS: Record<Exclude<PrivacyStatus, 'private'>, string> = {
  unlisted: '주소를 아는 사람만',
  public: '누구나',
};

/** 공개로 올라갈 때 덧붙인다. notifySubscribers를 안 보내 유튜브 기본값대로 알림이 간다(2026-10-11 사용자 결정) */
export const PUBLIC_NOTIFY_NOTE = '구독자에게 새 영상 알림이 갈 수 있어요.';

/** 올린 뒤 범위를 바꾸는 곳. 우리는 못 바꾼다(youtube.upload·youtube.readonly로는 videos.update를 못 부른다) */
export const PRIVACY_CHANGE_NOTE =
  '올린 뒤에는 스트리머 채널의 유튜브 스튜디오에서만 바꿀 수 있어요.';

/** 창의 공개 범위 안내: 고른 범위에 맞춘다. 편집자도 고를 수 있고 경고만 보인다(2026-10-11 사용자 결정) */
export function privacyNote(privacy: PrivacyStatus): string {
  if (privacy === 'private') return PRIVACY_CHANGE_NOTE;
  const notify = privacy === 'public' ? ` ${PUBLIC_NOTIFY_NOTE}` : '';
  return `${PRIVACY_VIEWERS[privacy]} 볼 수 있어요.${notify} ${PRIVACY_CHANGE_NOTE}`;
}

// ---------- 카테고리 ----------

/**
 * 사람이 고를 수 있는 유튜브 분류(videoCategories assignable, POK-381). clip이 같은 목록 밖이면 400(categoryId)을 준다
 * (UploadInfoParser.CATEGORIES). 처음 값은 게임이다: 우리 영상은 게임 방송에서 나온다.
 * 번호 목록의 정본은 analytics `YOUTUBE_CATEGORY_IDS`다(분석 이벤트 열거) — 번호를 더하면 거기부터 고친다.
 */
export const CATEGORIES: readonly { value: YoutubeCategoryId; label: string }[] = [
  { value: '20', label: '게임' },
  { value: '24', label: '엔터테인먼트' },
  { value: '22', label: '인물/블로그' },
  { value: '23', label: '코미디' },
  { value: '17', label: '스포츠' },
  { value: '10', label: '음악' },
  { value: '1', label: '영화/애니메이션' },
  { value: '27', label: '교육' },
  { value: '28', label: '과학기술' },
  { value: '26', label: '노하우/스타일' },
  { value: '19', label: '여행/이벤트' },
  { value: '15', label: '애완동물/동물' },
  { value: '2', label: '자동차/교통' },
  { value: '25', label: '뉴스/정치' },
  { value: '29', label: '비영리/사회운동' },
];

export const DEFAULT_CATEGORY = '20';

// ---------- 예약 공개 ----------

/**
 * 예약은 지금부터 이만큼 뒤부터 고른다. 만들고 올리는 데 시간이 걸리고, 그 전에 예약 시각이 지나면 유튜브가 올리자마자
 * 공개한다(videos status.publishAt 문서). clip은 「지금보다 뒤」만 본다.
 */
export const SCHEDULE_MIN_LEAD_MS = 60 * 60 * 1000;

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];

/** 로컬 날짜(2026-10-23)와 시각(18:00) → 그 순간. 둘 중 하나라도 비었거나 틀리면 null */
export function scheduleInstant(date: string, time: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null;
  const at = new Date(`${date}T${time}`);
  return Number.isNaN(at.getTime()) ? null : at;
}

/** 10월 23일 (목) 18:00. 날짜·시각이 틀리면 빈 글자 */
export function scheduleLabel(date: string, time: string): string {
  const at = scheduleInstant(date, time);
  return at === null ? '' : instantLabel(at);
}

/** 그 순간을 이 기기 시각으로: 10월 23일 (목) 18:00 */
export function instantLabel(at: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${at.getMonth() + 1}월 ${at.getDate()}일 (${WEEKDAY[at.getDay()]}) ${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

export function scheduleProblem(date: string, time: string, now: number): string | null {
  const at = scheduleInstant(date, time);
  if (at === null) return '날짜와 시각을 골라 주세요';
  if (at.getTime() < now + SCHEDULE_MIN_LEAD_MS) return '지금부터 1시간 뒤부터 고를 수 있어요';
  return null;
}

/** 로컬 날짜 표기(2026-10-23) */
export function localDate(at: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`;
}

/** 그 순간 → 예약 칸 값(로컬 날짜 · 시각). 저장된 예약을 창에 되살릴 때 쓴다 */
export function scheduleOf(at: Date): { date: string; time: string } {
  const pad = (n: number) => String(n).padStart(2, '0');
  return { date: localDate(at), time: `${pad(at.getHours())}:${pad(at.getMinutes())}` };
}

/** 예약 칸의 처음 값: 내일 18:00 */
export function defaultSchedule(now: number): { date: string; time: string } {
  return { date: localDate(new Date(now + 24 * 60 * 60 * 1000)), time: '18:00' };
}

/** 빠른 고르기 셋. 지금부터 1시간이 안 남은 것은 뺀다(저녁 늦게 열면 「오늘 21:00」이 없다) */
export function quickSchedules(now: number): { label: string; date: string; time: string }[] {
  const today = localDate(new Date(now));
  const tomorrow = localDate(new Date(now + 24 * 60 * 60 * 1000));
  return [
    { label: '오늘 21:00', date: today, time: '21:00' },
    { label: '내일 18:00', date: tomorrow, time: '18:00' },
    { label: '내일 21:00', date: tomorrow, time: '21:00' },
  ].filter((q) => scheduleProblem(q.date, q.time, now) === null);
}

// ---------- 추천 장면 ----------

export interface ScenePick {
  id: 'editor' | 'highlight' | 'start' | 'playhead' | 'middle' | 'end';
  label: string;
  /** 완성 영상 첫 장면 기준 ms */
  offsetMs: number;
}

/** 장면 고르기의 눈금. 띠를 끌거나 ← → 로 옮기는 단위다 */
export const SCENE_STEP_MS = 250;

/**
 * 추천 장면: (편집기에서 고름) · 하이라이트 · 시작 · 재생 위치 · 중간 · 마지막(POK-381 사용자 결정, 편집기에서 고른 장면은 POK-389). 하이라이트는 카드에서 연 편집기만 안다(카드
 * 시각), 재생 위치는 녹화가 있어야 안다. 모르거나 영상 밖이면 뺀다. 앞의 것과 한 눈금 안으로 겹치면 뒤의 것을 뺀다(같은 그림
 * 둘이 나란히 놓이지 않게).
 */
export function scenePicks(
  cutLengthMs: number,
  highlightOffsetMs: number | null,
  playheadOffsetMs: number | null,
  /** 편집기 「이 장면을 썸네일로」로 고른 장면(POK-389). 없으면 null */
  editorOffsetMs: number | null = null,
): ScenePick[] {
  const last = Math.max(0, cutLengthMs - 1);
  const inside = (ms: number | null) => ms !== null && ms >= 0 && ms < cutLengthMs;
  const all: (ScenePick | null)[] = [
    inside(editorOffsetMs)
      ? { id: 'editor', label: '편집기에서 고름', offsetMs: editorOffsetMs! }
      : null,
    inside(highlightOffsetMs)
      ? { id: 'highlight', label: '하이라이트', offsetMs: highlightOffsetMs! }
      : null,
    { id: 'start', label: '시작', offsetMs: 0 },
    inside(playheadOffsetMs)
      ? { id: 'playhead', label: '재생 위치', offsetMs: playheadOffsetMs! }
      : null,
    { id: 'middle', label: '중간', offsetMs: Math.floor(cutLengthMs / 2) },
    { id: 'end', label: '마지막', offsetMs: last },
  ];
  const picks: ScenePick[] = [];
  for (const pick of all) {
    if (pick === null) continue;
    if (picks.some((p) => Math.abs(p.offsetMs - pick.offsetMs) < SCENE_STEP_MS)) continue;
    picks.push(pick);
  }
  return picks;
}

/** 장면 시각 표기: 0:03.2 */
export function sceneLabel(ms: number): string {
  const tenths = Math.floor(ms / 100);
  const minutes = Math.floor(tenths / 600);
  const seconds = (tenths % 600) / 10;
  return `${minutes}:${seconds.toFixed(1).padStart(4, '0')}`;
}

// ---------- 장면 시각 ----------

/**
 * 재생 위치를 완성 영상 첫 장면 기준 ms로 옮긴다. 재생 위치(초)와 구간 시작은 같은 축(시각 기준점 축)이고, 컷은 구간 시작에서
 * 시작한다. 영상 밖이면 영상 안 끝으로 자른다(0 ~ 컷 길이−1). 재생 위치를 모르면(녹화가 없다) null.
 */
export function sceneOffsetFromPlayhead(
  playheadSeconds: number | null,
  rangeStartSeconds: number,
  cutLengthMs: number,
): number | null {
  if (playheadSeconds === null) return null;
  const raw = Math.round((playheadSeconds - rangeStartSeconds) * 1000);
  return Math.min(cutLengthMs - 1, Math.max(0, raw));
}

/**
 * 창을 열 때 장면의 처음 값(하이라이트를 모를 때, POK-381부터는 하이라이트가 먼저다). 재생 위치가 완성 영상 안이면 그 자리,
 * 아니면(구간 앞·뒤, 3분으로 잘린 뒤, 녹화 없음) 가운데.
 * 「안」은 구간이 아니라 컷으로 잰다: 5초로 늘어난 컷은 구간 끝 뒤도 영상에 있고, 3분으로 잘린 컷은 그 뒤가 없다.
 */
export function defaultSceneOffsetMs(
  playheadSeconds: number | null,
  rangeStartSeconds: number,
  cutLengthMs: number,
): number {
  const middle = Math.floor(cutLengthMs / 2);
  if (playheadSeconds === null) return middle;
  const raw = Math.round((playheadSeconds - rangeStartSeconds) * 1000);
  return raw >= 0 && raw < cutLengthMs ? raw : middle;
}

/**
 * 편집기에서 고른 썸네일 장면(소스 축 초)으로 업로드 창의 처음 값을 바꾼다(POK-389). 지금 막 고른 값이라 기억된 창 값·저장된
 * 업로드 정보보다 앞선다. 고르지 않았으면 처음 값 그대로다.
 */
export function withPickedScene<T extends { thumbnailSource: string; sceneOffsetMs: number }>(
  initial: T,
  thumbnailSeconds: number | null,
  rangeStartSeconds: number,
  cutLengthMs: number,
): { draft: T; picked: boolean } {
  const offset = sceneOffsetFromPlayhead(thumbnailSeconds, rangeStartSeconds, cutLengthMs);
  if (offset === null) return { draft: initial, picked: false };
  return { draft: { ...initial, thumbnailSource: 'scene', sceneOffsetMs: offset }, picked: true };
}

// ---------- 태그 ----------

const TAGS_MAX = 500;

/**
 * 태그 합계(유튜브 규칙). 글자 수(코드포인트) + 공백이 든 태그는 따옴표 두 글자 + 태그 사이 쉼표 하나씩.
 * clip이 같은 식으로 잰다: 따옴표를 빼먹으면 화면은 받고 서버는 400을 준다.
 */
export function tagsLength(tags: readonly string[]): number {
  const chars = tags.reduce(
    (sum, tag) => sum + Array.from(tag).length + (/\s/.test(tag) ? 2 : 0),
    0,
  );
  return chars + Math.max(0, tags.length - 1);
}

/**
 * 입력 칸의 글자를 태그로 더한다. 쉼표로 여럿을 한 번에 넣을 수 있고, 앞뒤 공백을 깎고, 빈 것은 건너뛰고, 이미 있는 것은
 * 하나만 둔다(순서 유지). 하나라도 규칙에 어긋나면 아무것도 더하지 않고 까닭을 돌려준다.
 */
export function addTags(
  current: readonly string[],
  raw: string,
): { tags: string[]; problem?: string } {
  const next = [...current];
  for (const piece of raw.split(',')) {
    const tag = piece.trim();
    if (tag === '' || next.includes(tag)) continue;
    if (/[<>]/.test(tag))
      return { tags: [...current], problem: '태그에는 < 와 > 를 쓸 수 없어요.' };
    next.push(tag);
  }
  if (tagsLength(next) > TAGS_MAX)
    return { tags: [...current], problem: '태그는 합쳐서 500자까지예요.' };
  return { tags: next };
}

// ---------- 설명 ----------

export const DESCRIPTION_MAX_BYTES = 5000;

export function descriptionBytes(text: string): number {
  return new TextEncoder().encode(text).length;
}

export function descriptionProblem(text: string): string | null {
  if (descriptionBytes(text) > DESCRIPTION_MAX_BYTES) return '설명은 5000바이트까지예요.';
  if (/[<>]/.test(text)) return '설명에는 < 와 > 를 쓸 수 없어요.';
  return null;
}

// ---------- 썸네일 이미지 ----------

/** 이미지 칸 안내. 우리 영상은 세로(쇼츠)라 세로 크기를 권한다 */
export const THUMBNAIL_FILE_HINT = 'JPG·PNG · 10MB까지 · 세로 1080×1920 권장';

/** clip이 받는 크기 상한(spring multipart max-file-size=10MB) */
export const THUMBNAIL_MAX_BYTES = 10 * 1024 * 1024;

/** 서버는 첫 바이트로 판별하지만, 파일 형식으로 먼저 거른다. 확장자만 바꾼 파일은 서버가 415로 거절한다 */
export function thumbnailFileProblem(file: Blob): string | null {
  if (file.type !== 'image/jpeg' && file.type !== 'image/png') return 'JPG나 PNG만 올릴 수 있어요';
  if (file.size > THUMBNAIL_MAX_BYTES) return '이미지는 10MB까지 올릴 수 있어요';
  return null;
}

// ---------- 실패한 업로드 ----------

/**
 * 연동 문제로 끝난 업로드 코드(업로드 일꾼이 `YOUTUBE_` + auth 사유로 적는다). 일시 실패(REFRESH_UNAVAILABLE)는
 * 일꾼이 다시 시도해 실패로 남지 않는다.
 */
const LINK_FAILURES = new Set(['YOUTUBE_NOT_LINKED', 'YOUTUBE_UNLINKED', 'YOUTUBE_BROKEN']);

/**
 * 실패한 업로드의 사유를 사람 말로 — 보관함 상세와 편집기 영상 상태 창(POK-492)이 같은 말을 쓴다.
 * @param fixHint 유튜브가 영상을 거절했을 때 제목·태그를 고칠 곳. 보관함과 편집기에서 가는 길이 다르다
 */
export function uploadFailureReason(error: UploadSnapshot['error'], fixHint: string): string {
  const code = error?.code ?? 'UNKNOWN';
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
    const why = error?.message ? `(${error.message})` : '';
    // 「다시 시도」는 실패한 줄의 제목·설명·태그를 그대로 다시 보낸다. 고칠 곳은 영상 만들기 창이다
    return `유튜브가 영상을 받지 않았어요${why}. 같은 정보로 다시 시도하면 또 거절될 수 있어요. ${fixHint}`;
  }
  return `유튜브에 올리지 못했어요(${code}). 다시 올려 주세요.`;
}
