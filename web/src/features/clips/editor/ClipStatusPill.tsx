import { useState } from 'react';
import Link from 'next/link';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ExternalLink,
  FolderOpen,
  RotateCw,
} from 'lucide-react';
import { Button, LinkButton, Popover, Progress, Spinner } from '@/ui';
import { CLIP_STEPS, type ClipStatusTone, type ClipStatusView } from './studio/clipProgress';
import styles from './editorShared.module.css';

// 「영상 만들기」 옆 영상 상태 알약(POK-492 시안 A, 2026-10-28 사용자 선택) — 회색 글 끝에 붙던 상태가 안 보이고 할 일이
// 없었다. 알약은 상태색 하나와 짧은 말·진행 막대만, 누르면 작은 창에 단계·설명·할 일(유튜브에서 보기 · 다시 올리기 ·
// 보관함에서 보기)이 펼쳐진다. 상태가 화면에 늘 있으니 요청 직후 토스트는 띄우지 않는다(같은 말을 두 번 하지 않게).

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

/** 단계 하나의 모양: 지난 단계 · 지금(상태색) · 남은 단계 */
function stepState(view: ClipStatusView, index: number): string {
  if (view.stage === 3 || index < view.stage) return 'done';
  if (index > view.stage) return 'todo';
  return view.tone;
}

export function ClipStatusPill({
  view,
  versionLabel,
  retrying,
  onRetryUpload,
}: {
  view: ClipStatusView;
  /** 이 영상을 만든 판(「v3로 만든 영상」). 지금 화면이 더 새 판일 수 있다 */
  versionLabel: string;
  retrying: boolean;
  onRetryUpload: () => void;
}) {
  const [open, setOpen] = useState(false);
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
            <span className={styles.statusPillBar}>
              <Progress size="sm" value={view.progress.value} label="영상 진행" />
            </span>
          ) : null}
          <ChevronDown size={13} aria-hidden className={styles.statusPillChevron} />
        </button>
      </Popover.Trigger>
      <Popover.Content aria-label="영상 상태">
        <div className={styles.statusPopover} data-tone={view.tone}>
          <div className={styles.statusPopoverTitle}>
            <span className={styles.statusPopoverIcon}>
              <ToneIcon tone={view.tone} />
            </span>
            {view.title}
          </div>
          <ol className={styles.statusSteps} aria-label="진행 단계">
            {CLIP_STEPS.map((label, i) => {
              const state = stepState(view, i);
              return (
                <li
                  key={label}
                  className={styles.statusStep}
                  data-state={state}
                  aria-current={state !== 'done' && state !== 'todo' ? 'step' : undefined}
                >
                  <span className={styles.statusStepBar} aria-hidden />
                  {label}
                </li>
              );
            })}
          </ol>
          <p className={styles.statusDetail}>{view.detail}</p>
          <p className={styles.statusVersion}>{versionLabel}</p>
          <div className={styles.statusActions}>
            {view.actions.map((action) => {
              switch (action) {
                case 'youtube':
                  return view.youtubeUrl ? (
                    <LinkButton
                      key={action}
                      href={view.youtubeUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      variant="soft"
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
                      variant="soft"
                      size="sm"
                      fullWidth
                      loading={retrying}
                      iconStart={<RotateCw size={13} aria-hidden />}
                      onClick={onRetryUpload}
                    >
                      다시 올리기
                    </Button>
                  );
                case 'library':
                  return (
                    <LinkButton
                      key={action}
                      as={Link}
                      href="/clips/library"
                      variant="ghost"
                      size="sm"
                      fullWidth
                      iconStart={<FolderOpen size={13} aria-hidden />}
                    >
                      보관함에서 보기
                    </LinkButton>
                  );
              }
            })}
          </div>
        </div>
      </Popover.Content>
    </Popover>
  );
}
