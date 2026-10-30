import type { ClipSnapshot, PrivacyStatus } from '@/api/clipEditor';
import { instantLabel, PRIVACY_LABEL, retryPrivacyNote, uploadFailureReason } from '../uploadInfo';

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

/**
 * 작은 창에서 할 수 있는 일. 앞의 것이 주 버튼이다. 확인 필요는 푸는 곳이 보관함이라 「보관함에서 확인」이고(새 판을 저장했으면
 * 풀 곳이 없어 「보관함에서 보기」), 영상 만들기 실패는 영상 만들기 창을 다시 연다(같은 판이면 저장 없이 다시 요청한다)
 */
export type ClipStatusAction = 'youtube' | 'retryUpload' | 'remake' | 'library' | 'libraryCheck';

/** 작은 창의 세로 단계(POK-492 시안 A): 대기 → 영상 만들기 → 유튜브 올리기 */
export const CLIP_STEPS = ['대기', '영상 만들기', '유튜브 올리기'] as const;

/** 지난 단계 · 지금(진행) · 지금(실패) · 지금(사람이 봐야 함) · 아직 */
export type ClipStepState = 'done' | 'now' | 'danger' | 'warning' | 'todo';

export interface ClipStep {
  label: (typeof CLIP_STEPS)[number];
  state: ClipStepState;
  /** 단계 오른쪽의 시각·진행(「10:21」 · 「10:23부터」 · 「42%」 · 「10:24 실패」). 모르면 null */
  note: string | null;
}

export interface ClipStatusView {
  tone: ClipStatusTone;
  /** 알약 글이자 작은 창 제목(짧게) */
  title: string;
  /** 작은 창의 설명 한두 줄 — 지금 단계 아래에 붙는다 */
  detail: string;
  steps: [ClipStep, ClipStep, ClipStep];
  progress: { value: number | null } | null;
  actions: ClipStatusAction[];
  /** 올라간 영상 주소. youtube 행동이 있을 때만 */
  youtubeUrl: string | null;
  /** 「다시 올리기」를 누르면 어떤 범위로 올라가나(버튼 바로 위). 다시 올리기가 없거나 비공개면 null */
  retryNote: string | null;
}

/** 편집기에서 거절된 제목·태그를 고칠 곳 — 같은 판이 이미 완성돼 있으면 다시 만들지 않고 고친 정보로 올린다 */
const EDITOR_FIX_HINT = '「영상 만들기」를 다시 눌러 제목·태그를 고친 뒤 올려 주세요.';

function uploadedDetail(
  privacy: PrivacyStatus | undefined,
  publishAt: string | null | undefined,
  now: Date,
) {
  if (publishAt) {
    // 시각이 지났으면 「바뀌어요」라고 하지 않는다 — 이미 공개됐을 영상이다(보관함 privacyNoteText와 같은 말, PR #454 2차 리뷰)
    const at = new Date(publishAt);
    const when = instantLabel(at);
    return at.getTime() <= now.getTime()
      ? `「예약」 ${when}에 공개되도록 올렸어요.`
      : `「예약」 ${when}에 공개되도록 올렸어요. 그 전까지는 비공개예요.`;
  }
  // 옛 서버는 공개 범위를 안 준다. 그때는 늘 비공개였다
  return `${PRIVACY_LABEL[privacy ?? 'private']}로 올라갔어요.`;
}

/**
 * 단계 옆 시각. 오늘이면 시:분, 아니면 월/일 시:분이다 — 편집기를 다음 날 다시 열면 어제의 시각이 보인다.
 * 서버 값이 없거나 못 읽으면 null(지어내지 않는다).
 */
export function stepClock(iso: string | null | undefined, now: Date): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  const hm = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  return at.toDateString() === now.toDateString()
    ? hm
    : `${at.getMonth() + 1}/${at.getDate()} ${hm}`;
}

const since = (clock: string | null) => (clock === null ? null : `${clock}부터`);
const failedAt = (clock: string | null) => (clock === null ? '실패' : `${clock} 실패`);

function steps(
  wait: [ClipStepState, string | null],
  render: [ClipStepState, string | null],
  upload: [ClipStepState, string | null],
): [ClipStep, ClipStep, ClipStep] {
  return [
    { label: CLIP_STEPS[0], state: wait[0], note: wait[1] },
    { label: CLIP_STEPS[1], state: render[0], note: render[1] },
    { label: CLIP_STEPS[2], state: upload[0], note: upload[1] },
  ];
}

/**
 * 영상 하나의 상태를 알약·작은 창 모양으로(POK-492). now는 단계 시각의 날짜와 예약 공개 시각이 지났는지 볼 때 쓴다.
 * currentVersion은 지금 편집본의 판이다: 편집기는 저장한 뒤에도 옛 판 영상의 알약을 그대로 보인다. 영상이 지금 판일 때만 부르는
 * 보관함은 안 넘긴다
 */
export function clipStatusView(
  clip: ClipSnapshot,
  now: Date = new Date(),
  currentVersion?: number | null,
): ClipStatusView {
  const progress = clipProgressBar(clip);
  const base = { progress, youtubeUrl: null, retryNote: null };
  const waiting = clip.uploadRequest ? '다 만들면 유튜브에 올려요.' : '';
  // 요청한 때 = 영상 줄이 생긴 때. 완성한 때 = 영상 줄의 마지막 변경(clip은 영상 줄을 만드는 중 · 완성 · 실패로 바꿀 때만 고친다).
  // 업로드 줄이 생긴 때로 잡으면 「다시 올리기」로 새 줄이 생길 때마다 완성 시각이 다시 올린 시각으로 바뀌었다(PR #454 1차 리뷰)
  const requested = stepClock(clip.createdAt, now);
  const renderedAt = stepClock(clip.updatedAt, now);
  switch (clip.status) {
    case 'queued':
      return {
        ...base,
        tone: 'progress',
        title: '차례 기다리는 중',
        detail: `영상을 만들 차례를 기다리고 있어요. ${waiting}`.trim(),
        steps: steps(['now', since(requested)], ['todo', null], ['todo', null]),
        actions: ['library'],
      };
    case 'rendering': {
      const percent = renderPercent(clip);
      return {
        ...base,
        tone: 'progress',
        title: `영상 만드는 중${percent === null ? '' : ` ${percent}%`}`,
        detail: `영상을 만들고 있어요. ${waiting}`.trim(),
        steps: steps(
          ['done', requested],
          ['now', percent === null ? null : `${percent}%`],
          ['todo', null],
        ),
        actions: ['library'],
      };
    }
    case 'failed':
      return {
        ...base,
        tone: 'danger',
        title: '영상 만들기 실패',
        detail: `영상을 만들지 못했어요(${clip.error?.code ?? '?'}). 다시 만들어 주세요.`,
        steps: steps(
          ['done', requested],
          ['danger', failedAt(stepClock(clip.updatedAt, now))],
          ['todo', null],
        ),
        actions: ['remake', 'library'],
      };
    case 'rendered': {
      const upload = clip.upload;
      const uploadStart = stepClock(upload?.createdAt, now);
      const uploadEnd = stepClock(upload?.updatedAt, now);
      const made = (state: ClipStepState, note: string | null) =>
        steps(['done', requested], ['done', renderedAt], [state, note]);
      // 다시 올리기는 실패한 줄의 범위·예약 시각을, 줄이 없으면 업로드 정보의 것을 그대로 쓴다
      const retryNote = retryPrivacyNote(
        upload != null ? upload.privacyStatus : clip.uploadRequest?.privacyStatus,
        upload != null ? upload.publishAt : clip.uploadRequest?.publishAt,
        now,
      );
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
                steps: made('warning', '시작 안 됨'),
                actions: ['retryUpload', 'library'],
                retryNote,
              }
            : {
                ...base,
                tone: 'success',
                title: '영상 완성',
                detail: '영상을 다 만들었어요. 유튜브에는 올리지 않았어요.',
                steps: made('todo', '안 올림'),
                actions: ['library'],
              };
        case 'uploaded':
          return {
            ...base,
            tone: 'success',
            title: '유튜브에 올라갔어요',
            detail: uploadedDetail(upload.privacyStatus, upload.publishAt, now),
            steps: made('done', uploadEnd),
            actions: upload.videoId ? ['youtube', 'library'] : ['library'],
            youtubeUrl: upload.videoId ? `https://youtu.be/${upload.videoId}` : null,
          };
        case 'failed':
          return {
            ...base,
            tone: 'danger',
            title: '업로드 실패',
            detail: uploadFailureReason(upload.error, EDITOR_FIX_HINT),
            steps: made('danger', failedAt(uploadEnd)),
            actions: ['retryUpload', 'library'],
            retryNote,
          };
        case 'checking': {
          // 올라갔는지 모르는 업로드는 사람이 채널을 보고 푼다 — 푸는 곳은 보관함이다(POK-333). 새 판을 저장하면 보관함은 이 업로드를
          // 이전 판 줄에 글자로만 보여 풀 곳이 없으니(2026-10-30 사용자 결정) 보내지 않고 할 수 있는 일을 말한다. 서버의 겹침 검사는
          // 판마다라 옛 판이 확인 필요여도 새 판은 올릴 수 있다
          const superseded = currentVersion != null && currentVersion !== clip.recipeVersion;
          return {
            ...base,
            tone: 'warning',
            title: '확인 필요',
            detail: superseded
              ? '이 판은 올라갔는지 확인하지 못했어요. 지금 판으로 영상을 만들면 다시 올릴 수 있어요.'
              : '유튜브에 올라갔는지 알 수 없어요. 보관함에서 채널을 확인하고 결과를 알려 주세요.',
            steps: made('warning', '확인 필요'),
            actions: [superseded ? 'library' : 'libraryCheck'],
          };
        }
        default:
          return {
            ...base,
            tone: 'progress',
            title: '유튜브에 올리는 중',
            detail: '영상을 다 만들었어요. 스트리머 채널에 올리고 있어요.',
            steps: made('now', since(uploadStart)),
            actions: ['library'],
          };
      }
    }
  }
}
