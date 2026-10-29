import { useEffect, useId, useRef, useState } from 'react';
import Link from 'next/link';
import {
  AlertCircle,
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  ExternalLink,
  FolderOpen,
  RotateCw,
} from 'lucide-react';
import { Button, LinkButton, Popover, Progress, Spinner, VisuallyHidden } from '@/ui';
import type {
  ClipStatusAction,
  ClipStatusTone,
  ClipStatusView,
  ClipStepState,
} from './studio/clipProgress';
import styles from './editorShared.module.css';

// 「영상 만들기」 옆 영상 상태 알약(POK-492 시안 A, 2026-10-28 사용자 선택) — 회색 글 끝에 붙던 상태가 안 보이고 할 일이
// 없었다. 알약은 상태색 하나와 짧은 말·진행 막대만, 누르면 작은 창이 펼쳐진다. 상태가 화면에 늘 있으니 요청 직후 토스트는
// 띄우지 않는다(같은 말을 두 번 하지 않게).
//
// 작은 창은 세로 단계 기록형(2026-10-29 사용자 선택, 시안 페이지 2차): 머리 한 줄(상태 · 판) → 동그라미를 줄로 이은 단계
// 셋(단계마다 시각, 지금 단계 아래에 진행 막대와 설명) → 버튼 가로 둘(같은 크기). 색은 지금 상태 하나뿐이고 지난 단계와
// 버튼은 무채색이다(카드당 상태색 하나 — 사용자 취향).

function ToneIcon({ tone }: { tone: ClipStatusTone }) {
  switch (tone) {
    case 'progress':
      return <Spinner size="sm" aria-hidden />;
    case 'success':
      return <CheckCircle2 size={14} aria-hidden />;
    case 'danger':
      return <AlertTriangle size={14} aria-hidden />;
    case 'warning':
      return <AlertCircle size={14} aria-hidden />;
  }
}

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

const isCurrent = (state: ClipStepState) => state !== 'done' && state !== 'todo';

/**
 * 영상 상태가 바뀌면 화면 낭독기에 알린다. 요청 직후 토스트(role="status")를 뺀 뒤로 「받았다」가 아무 데서도 읽히지 않았다
 * (PR #454 3차 리뷰). 알약은 상태가 생길 때 함께 붙어 그 안의 영역은 첫 글을 못 읽히므로, 이 영역은 헤더에 늘 떠 있다.
 * 단계가 바뀔 때만 말한다(만드는 중 퍼센트마다 말하지 않는다). 편집기를 열 때 이미 있던 상태는 말하지 않는다.
 */
export function ClipStatusAnnouncer({ view }: { view: ClipStatusView | null }) {
  const key = view === null ? null : `${view.tone}|${view.steps.map((step) => step.state).join()}`;
  const title = view?.title ?? null;
  const lastKey = useRef(key);
  const [message, setMessage] = useState('');
  useEffect(() => {
    if (key === lastKey.current) return;
    lastKey.current = key;
    if (title === null) return;
    // 같은 글이 다시 와도 읽히게 비웠다가 다음 프레임에 채운다(LiveRegionProvider와 같은 방식)
    setMessage('');
    requestAnimationFrame(() => setMessage(`영상 상태: ${title}`));
  }, [key, title]);
  return (
    <VisuallyHidden role="status" aria-live="polite" aria-atomic="true">
      {message}
    </VisuallyHidden>
  );
}

export function ClipStatusPill({
  view,
  versionLabel,
  retrying,
  onRetryUpload,
  onRemake,
  remakeLocked,
}: {
  view: ClipStatusView;
  /** 이 영상을 만든 판(「편집본 v3」). 지금 화면이 더 새 판일 수 있다 */
  versionLabel: string;
  retrying: boolean;
  onRetryUpload: () => void;
  /** 영상 만들기 실패 뒤 「다시 만들기」 — 영상 만들기 창을 연다 */
  onRemake: () => void;
  /**
   * 헤더 「영상 만들기」가 꺼져 있는 동안(편집본 저장 중) 「다시 만들기」도 끈다 — 알약은 저장 중에도 남는데, 누르면 창만 닫히고
   * 영상 만들기 창은 저장이 끝날 때까지 안 열려 아무 일도 없는 것처럼 보였다(PR #454 2차 리뷰)
   */
  remakeLocked: boolean;
}) {
  const [open, setOpen] = useState(false);
  const retryNoteId = useId();
  const settledAll = !view.steps.some((step) => isCurrent(step.state));

  // 할 일이 바뀌거나(다시 올리기 성공) 다시 올리기 단추가 돌기 시작하면 포커스를 쥔 단추가 사라지거나 꺼져 포커스가 body로
  // 떨어졌다 — 창으로 되돌려 키보드·스크린리더가 창 문맥을 잃지 않게 한다(PR #454 1차 리뷰)
  const panelRef = useRef<HTMLDivElement>(null);
  const actionsKey = `${view.actions.join(',')}|${retrying}`;
  const lastActionsKey = useRef(actionsKey);
  useEffect(() => {
    if (lastActionsKey.current === actionsKey) return;
    lastActionsKey.current = actionsKey;
    const panel = panelRef.current;
    if (open && panel !== null && !panel.contains(document.activeElement)) panel.focus();
  }, [open, actionsKey]);

  /** 왼쪽(첫째)이 주 버튼(테두리), 나머지는 글자 버튼. 둘이면 같은 크기로 가로에 나란히, 하나면 폭을 채운다 */
  const actionButton = (action: ClipStatusAction, index: number) => {
    const variant = index === 0 ? 'outline' : 'ghost';
    switch (action) {
      case 'youtube':
        return view.youtubeUrl ? (
          <LinkButton
            key={action}
            href={view.youtubeUrl}
            target="_blank"
            rel="noopener noreferrer"
            variant={variant}
            size="sm"
            fullWidth
            iconStart={<ExternalLink size={13} aria-hidden />}
          >
            유튜브에서 보기
          </LinkButton>
        ) : null;
      case 'retryUpload':
        return (
          <Button
            key={action}
            variant={variant}
            size="sm"
            fullWidth
            loading={retrying}
            iconStart={<RotateCw size={13} aria-hidden />}
            aria-describedby={view.retryNote !== null ? retryNoteId : undefined}
            onClick={onRetryUpload}
          >
            다시 올리기
          </Button>
        );
      case 'remake':
        return (
          <Button
            key={action}
            variant={variant}
            size="sm"
            fullWidth
            disabled={remakeLocked}
            iconStart={<RotateCw size={13} aria-hidden />}
            onClick={() => {
              // 영상 만들기 창(모달)이 이 작은 창 위에 뜨므로 먼저 닫는다
              setOpen(false);
              onRemake();
            }}
          >
            다시 만들기
          </Button>
        );
      case 'library':
      case 'libraryCheck':
        return (
          <LinkButton
            key={action}
            as={Link}
            href="/clips/library"
            variant={variant}
            size="sm"
            fullWidth
            iconStart={<FolderOpen size={13} aria-hidden />}
          >
            {action === 'library' ? '보관함에서 보기' : '보관함에서 확인'}
          </LinkButton>
        );
    }
  };

  return (
    <Popover open={open} onOpenChange={setOpen} side="bottom" align="end" offset={6}>
      <Popover.Trigger>
        <button
          type="button"
          className={styles.statusPill}
          data-tone={view.tone}
          aria-label={`영상 상태: ${view.title}. 자세히 보기`}
        >
          <span className={styles.statusPillIcon}>
            <ToneIcon tone={view.tone} />
          </span>
          <span>{view.title}</span>
          {view.progress !== null ? (
            // 버튼 안의 막대는 버튼의 표현용 자식이라 보조기기에 안 드러나고, 창을 열면 같은 이름 막대가 둘이 된다 — 숨긴다.
            // 퍼센트는 버튼 이름(제목)에 이미 있다(PR #454 1차 리뷰)
            <span className={styles.statusPillBar} aria-hidden>
              <Progress size="sm" value={view.progress.value} label="영상 진행" />
            </span>
          ) : null}
          <ChevronDown size={13} aria-hidden className={styles.statusPillChevron} />
        </button>
      </Popover.Trigger>
      <Popover.Content ref={panelRef} tabIndex={-1} aria-label="영상 상태">
        <div className={styles.statusPopover} data-tone={view.tone}>
          <div className={styles.statusHead}>
            <span className={styles.statusTitle}>{view.title}</span>
            <span className={styles.statusVersion}>{versionLabel}</span>
          </div>
          <ol className={styles.statusSteps} aria-label="진행 단계">
            {view.steps.map((step, i) => {
              const current = isCurrent(step.state);
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
                        <p className={styles.statusDetail}>{view.detail}</p>
                      </>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ol>
          {/* 지금 단계가 없으면(다 끝남 · 안 올림) 설명은 단계 아래에 따로 둔다 */}
          {settledAll ? (
            <p className={styles.statusDetail} data-standalone="">
              {view.detail}
            </p>
          ) : null}
          {/* 다시 올리면 남이 보게 되거나 바로 공개되는 경우의 경고 — 누르기 전에 버튼 바로 위에서 읽힌다 */}
          {view.retryNote !== null ? (
            <p id={retryNoteId} className={styles.statusRetryNote}>
              {view.retryNote}
            </p>
          ) : null}
          <div className={styles.statusActions}>{view.actions.map(actionButton)}</div>
        </div>
      </Popover.Content>
    </Popover>
  );
}
