import type { ClipSnapshot, PrivacyStatus } from '@/api/clipEditor';
import { PRIVACY_LABEL, uploadFailureReason } from '../uploadInfo';

// 편집기 헤더가 말하는 영상의 진행(POK-291): 만드는 중 N% → 올리는 중 → 업로드됨 / 업로드 실패.
// 「영상 만들기」 옆 상태 알약과 누르면 여는 작은 창(POK-492)이 이 판정 하나로 그린다 — 막대(POK-444)도 여기서 정한다.
// 업로드 정보를 실어 요청하면 렌더가 끝나는 대로 clip이 올리므로, 상태 확인(폴링)도 업로드가 끝날 때까지 간다.
// 화면 말에는 「주문」을 쓰지 않는다(2026-10-28 사용자: 사용자가 못 알아듣는다) — 「요청」·「차례 기다리는 중」이다.

/**
 * 렌더 퍼센트. 만드는 중이고 서버가 숫자를 줬을 때만이다 — 없는 값을 0%로 바꾸지 않는다(POK-444). 일꾼이 아직 진척을
 * 안 보냈으면(progress 없음) 「0%」가 아니라 모르는 것이다.
 */
export function renderPercent(clip: ClipSnapshot): number | null {
  const percent = clip.status === 'rendering' ? clip.progress?.percent : undefined;
  return typeof percent === 'number' && Number.isFinite(percent) ? percent : null;
}

/**
 * 더 물을 것이 없는가. 렌더가 실패했거나, 완성됐는데 업로드 줄이 없거나, 업로드가 끝났으면(올림·실패·확인 필요) 멈춘다.
 * 확인 필요는 사람이 채널을 봐야 풀린다(보관함과 같다).
 */
export function clipSettled(clip: ClipSnapshot): boolean {
  if (clip.status === 'failed') return true;
  if (clip.status !== 'rendered') return false;
  const upload = clip.upload;
  // 완성인데 줄이 없으면 더 기다려도 생기지 않는다(아래 clipStatusView 참고)
  if (upload == null) return true;
  return upload.status === 'uploaded' || upload.status === 'failed' || upload.status === 'checking';
}

/**
 * 진행 막대(POK-444). 아직 기다리는 영상(대기 · 만드는 중 · 올리는 중 — 상태 확인이 도는 동안)만 선다.
 * 렌더 퍼센트가 있으면 그 값, 없으면 불확정(`value: null`)이다: 대기열과 유튜브 업로드는 서버가 퍼센트를 안 주므로 지어내지
 * 않는다. 완성 · 실패 · 업로드 끝 · 확인 필요는 기다릴 것이 없어 막대가 없다(null).
 */
export function clipProgressBar(clip: ClipSnapshot): { value: number | null } | null {
  return clipSettled(clip) ? null : { value: renderPercent(clip) };
}

/** 상태색 하나 — 기다리는 중은 강조색, 끝은 성공, 실패는 위험, 사람이 봐야 하면 경고 */
export type ClipStatusTone = 'progress' | 'success' | 'danger' | 'warning';

/** 작은 창에서 할 수 있는 일 */
export type ClipStatusAction = 'youtube' | 'retryUpload' | 'library';

/** 작은 창의 단계 막대: 대기 → 영상 만들기 → 유튜브 올리기 */
export const CLIP_STEPS = ['대기', '영상 만들기', '유튜브 올리기'] as const;

export interface ClipStatusView {
  tone: ClipStatusTone;
  /** 알약 글(짧게) */
  title: string;
  /** 작은 창의 설명 한두 줄 */
  detail: string;
  /** 지금 단계(CLIP_STEPS 자리). 3이면 다 끝났다 */
  stage: 0 | 1 | 2 | 3;
  progress: { value: number | null } | null;
  actions: ClipStatusAction[];
  /** 올라간 영상 주소. youtube 행동이 있을 때만 */
  youtubeUrl: string | null;
}

/** 편집기에서 거절된 제목·태그를 고칠 곳 — 같은 판이 이미 완성돼 있으면 다시 만들지 않고 고친 정보로 올린다 */
const EDITOR_FIX_HINT = '「영상 만들기」를 다시 눌러 제목·태그를 고친 뒤 올려 주세요.';

function uploadedDetail(privacy: PrivacyStatus | undefined, publishAt: string | null | undefined) {
  if (publishAt) return '비공개로 올렸고, 정한 시각에 공개로 바뀌어요.';
  // 옛 서버는 공개 범위를 안 준다. 그때는 늘 비공개였다
  return `${PRIVACY_LABEL[privacy ?? 'private']}로 올라갔어요.`;
}

/** 영상 하나의 상태를 알약·작은 창 모양으로(POK-492) */
export function clipStatusView(clip: ClipSnapshot): ClipStatusView {
  const progress = clipProgressBar(clip);
  const base = { progress, youtubeUrl: null };
  const waiting = clip.uploadRequest ? '다 만들면 유튜브에 올려요.' : '';
  switch (clip.status) {
    case 'queued':
      return {
        ...base,
        tone: 'progress',
        title: '차례 기다리는 중',
        detail: `영상을 만들 차례를 기다리고 있어요. ${waiting}`.trim(),
        stage: 0,
        actions: ['library'],
      };
    case 'rendering': {
      const percent = renderPercent(clip);
      return {
        ...base,
        tone: 'progress',
        title: `영상 만드는 중${percent === null ? '' : ` ${percent}%`}`,
        detail: `영상을 만들고 있어요. ${waiting}`.trim(),
        stage: 1,
        actions: ['library'],
      };
    }
    case 'failed':
      return {
        ...base,
        tone: 'danger',
        title: '영상 만들기 실패',
        detail: `영상을 만들지 못했어요(${clip.error?.code ?? '?'}). 「영상 만들기」를 다시 눌러 주세요.`,
        stage: 1,
        actions: ['library'],
      };
    case 'rendered': {
      const upload = clip.upload;
      switch (upload?.status) {
        case undefined:
          // clip은 완성과 업로드 줄을 한 트랜잭션에서 만든다. 정보가 있는데 줄이 없으면 자동 업로드를 건너뛴 것이다
          // (업로드가 꺼졌거나 같은 판이 이미 올라갔다). 「올리는 중」이라고 하면 끝나지 않는 대기가 된다
          return clip.uploadRequest
            ? {
                ...base,
                tone: 'warning',
                title: '업로드 안 됨',
                detail: '영상은 다 만들었는데 유튜브 업로드가 시작되지 않았어요. 다시 올려 주세요.',
                stage: 2,
                actions: ['retryUpload', 'library'],
              }
            : {
                ...base,
                tone: 'success',
                title: '영상 완성',
                detail: '영상을 다 만들었어요. 유튜브에는 올리지 않았어요.',
                stage: 3,
                actions: ['library'],
              };
        case 'uploaded':
          return {
            ...base,
            tone: 'success',
            title: '유튜브에 올라갔어요',
            detail: uploadedDetail(upload.privacyStatus, upload.publishAt),
            stage: 3,
            actions: upload.videoId ? ['youtube', 'library'] : ['library'],
            youtubeUrl: upload.videoId ? `https://youtu.be/${upload.videoId}` : null,
          };
        case 'failed':
          return {
            ...base,
            tone: 'danger',
            title: '업로드 실패',
            detail: uploadFailureReason(upload.error, EDITOR_FIX_HINT),
            stage: 2,
            actions: ['retryUpload', 'library'],
          };
        case 'checking':
          // 올라갔는지 모르는 업로드는 사람이 채널을 보고 푼다 — 푸는 곳은 보관함이다(POK-333)
          return {
            ...base,
            tone: 'warning',
            title: '확인 필요',
            detail:
              '유튜브에 올라갔는지 알 수 없어요. 보관함에서 채널을 확인하고 결과를 알려 주세요.',
            stage: 2,
            actions: ['library'],
          };
        default:
          return {
            ...base,
            tone: 'progress',
            title: '유튜브에 올리는 중',
            detail: '영상을 다 만들었어요. 스트리머 채널에 올리고 있어요.',
            stage: 2,
            actions: ['library'],
          };
      }
    }
  }
}
