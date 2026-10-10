import type { PrivacyStatus } from '@/api/clipEditor';

// 업로드 정보 창(POK-291)의 규칙: 렌더와 떼어 순수 함수로 둔다. clip 렌더 주문 문이 같은 규칙으로 400을 주지만,
// 보내기 전에 창 안에서 말해야 그 칸을 바로 고친다. 제목 규칙은 보관함의 uploadTitleProblem을 그대로 쓴다.

/**
 * 공개 범위 셋. 고른 범위 그대로 올라간다: 2026-10-11 실측에서 감사 전인데도 공개는 공개로, 일부 공개는 일부 공개로 남았다
 * (문서의 「감사 전엔 비공개로 잠긴다」가 이 프로젝트에 안 걸렸다). 창과 보관함이 같은 말을 쓴다
 */
export const PRIVACY_LABEL: Record<PrivacyStatus, string> = {
  private: '비공개',
  unlisted: '일부 공개',
  public: '공개',
};

/** 올린 뒤 공개 범위는 우리가 못 바꾼다(youtube.upload 범위로는 videos.update를 못 부른다) */
export const PRIVACY_NOTE =
  '고른 범위 그대로 올라가요. 공개를 고르면 영상이 다 만들어지는 대로 누구나 볼 수 있어요. 올린 뒤 바꾸려면 유튜브 스튜디오에서 바꿔요.';

export const THUMBNAIL_PHONE_NOTE =
  '직접 고른 썸네일은 유튜브 채널 전화 인증이 끝나야 붙어요. 안 붙으면 영상만 올라가요.';

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
 * 창을 열 때 장면의 처음 값. 재생 위치가 완성 영상 안이면 그 자리, 아니면(구간 앞·뒤, 3분으로 잘린 뒤, 녹화 없음) 가운데.
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

/** clip이 받는 크기 상한(spring multipart max-file-size=10MB) */
export const THUMBNAIL_MAX_BYTES = 10 * 1024 * 1024;

/** 서버는 첫 바이트로 판별하지만, 파일 형식으로 먼저 거른다. 확장자만 바꾼 파일은 서버가 415로 거절한다 */
export function thumbnailFileProblem(file: Blob): string | null {
  if (file.type !== 'image/jpeg' && file.type !== 'image/png') return 'JPG나 PNG만 올릴 수 있어요';
  if (file.size > THUMBNAIL_MAX_BYTES) return '이미지는 10MB까지 올릴 수 있어요';
  return null;
}
