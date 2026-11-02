import { AlertCircle, AlertTriangle, Check } from 'lucide-react';
import { Progress } from '@/ui';
import type { ClipStatusView, ClipStepState } from './studio/clipProgress';
import styles from './editorShared.module.css';

// 영상 진행 세로 단계(POK-492 시안 A): 동그라미를 줄로 이은 대기 → 영상 만들기 → 유튜브 올리기, 단계마다 시각, 지금 단계 아래에
// 진행 막대와 설명. 편집기 상태 알약의 작은 창과 보관함 상세 패널의 진행 카드(POK-510)가 같은 판정(clipStatusView) · 같은 모양으로
// 그린다 — 두 곳의 말이 갈리지 않게. 상태색은 목록이 data-tone으로 스스로 정한다(감싸는 쪽이 색을 몰라도 된다).

function StepMark({ state }: { state: ClipStepState }) {
  switch (state) {
    case 'done':
      return <Check size={11} strokeWidth={3} aria-hidden />;
    case 'now':
      // 도는 고리는 동그라미 테두리가 맡는다(CSS) — 안에 스피너를 또 넣으면 원이 두 겹으로 겹쳐 보였다
      return <span className={styles.statusStepDot} aria-hidden />;
    case 'danger':
      return <AlertTriangle size={11} strokeWidth={2.5} aria-hidden />;
    case 'warning':
      return <AlertCircle size={11} strokeWidth={2.5} aria-hidden />;
    case 'todo':
      return null;
  }
}

/** 지금 단계인가 — 진행 · 실패 · 사람이 봐야 함. 지난 단계와 아직 안 온 단계는 아니다 */
export const isCurrentStep = (state: ClipStepState) => state !== 'done' && state !== 'todo';

/** 지금 단계가 하나도 없다(다 끝남 · 안 올림) — 그러면 설명은 목록 밖에 따로 둔다 */
export function stepsSettled(view: ClipStatusView): boolean {
  return !view.steps.some((step) => isCurrentStep(step.state));
}

export function ClipStepList({
  view,
  detail = view.detail,
}: {
  view: ClipStatusView;
  /** 지금 단계 아래 설명. 보관함은 자기 말(확인 필요 · 업로드 실패 안내)로 바꿔 넣는다 */
  detail?: string;
}) {
  return (
    <ol className={styles.statusSteps} data-tone={view.tone} aria-label="진행 단계">
      {view.steps.map((step, i) => {
        const current = isCurrentStep(step.state);
        return (
          <li
            key={step.label}
            className={styles.statusStep}
            data-state={step.state}
            aria-current={current ? 'step' : undefined}
          >
            <span className={styles.statusStepMark}>
              <StepMark state={step.state} />
            </span>
            {i < view.steps.length - 1 ? (
              <span className={styles.statusStepLine} aria-hidden />
            ) : null}
            <div className={styles.statusStepBody}>
              <div className={styles.statusStepRow}>
                <span className={styles.statusStepLabel}>{step.label}</span>
                {step.note !== null ? (
                  <span className={styles.statusStepNote}>{step.note}</span>
                ) : null}
              </div>
              {current ? (
                <>
                  {view.progress !== null && step.state === 'now' ? (
                    <div className={styles.statusStepBar}>
                      <Progress size="sm" value={view.progress.value} label="영상 진행" />
                    </div>
                  ) : null}
                  <p className={styles.statusDetail}>{detail}</p>
                </>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
