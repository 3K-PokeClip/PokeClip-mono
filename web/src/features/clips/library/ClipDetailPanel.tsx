'use client';

import { useEffect, useRef } from 'react';
import Link from 'next/link';
import { ExternalLink, Play, Trash2, X } from 'lucide-react';
import { Badge, Button, IconButton, LinkButton, VisuallyHidden } from '@/ui';
import { ddayFor } from '@/features/broadcast/vod/vodListView';
import { InlineTitleInput } from './InlineTitleInput';
import {
  dayTimeLabel,
  detailViewFor,
  durationLabel,
  noteText,
  retentionLabel,
  safeExternalUrl,
  type DetailView,
} from './libraryView';
import type { ClipStatus, LibraryClip, LibraryRole } from './useLibraryMockState';
import styles from './LibraryScreen.module.css';

// 시안 1g 우측 상세 패널의 내용. 무엇을 그릴지는 detailViewFor가 정하고 여기는 그리기만 한다.
// 패널 껍데기(폭 전환·inert)는 LibraryScreen이 갖는다 — 닫히는 동안에도 내용이 남아야 해서다.

export function ClipDetailPanel({
  clip,
  status,
  role,
  now,
  onDeselect,
  onTitleChange,
  onUpload,
  onRetryRender,
  onDownload,
  onDelete,
}: {
  clip: LibraryClip;
  status: ClipStatus;
  role: LibraryRole;
  now: Date;
  onDeselect: () => void;
  onTitleChange: (title: string) => void;
  onUpload: () => void;
  onRetryRender: () => void;
  onDownload: () => void;
  onDelete: () => void;
}) {
  const view = detailViewFor(status, role);
  const duration = durationLabel(clip, status);
  const retention = ddayFor(clip.sourceExpiresAt, now);

  // 업로드·렌더 재시도는 상태를 바꾸고, 그러면 주 동작이 button ↔ anchor로 갈리거나 비활성이
  // 된다 — 누르고 있던 노드가 사라져 포커스가 body로 떨어진다. 전이를 일으킨 경우에만
  // 새 주 동작(비활성이면 그다음 조작부)으로 포커스를 옮긴다.
  const actionsRef = useRef<HTMLDivElement>(null);
  const refocusAfterTransition = useRef(false);
  useEffect(() => {
    if (!refocusAfterTransition.current) return;
    refocusAfterTransition.current = false;
    actionsRef.current?.querySelector<HTMLElement>('a[href], button:not([disabled])')?.focus();
  });

  const runTransition = (run: () => void) => {
    refocusAfterTransition.current = true;
    run();
  };

  return (
    <>
      <div className={styles.panelHead}>
        {/* 상태가 바뀌면 배지만 조용히 갈리므로 낭독으로도 알린다 (StreamInfoBar 선례) */}
        <span role="status">
          <Badge tone={view.badge.tone} variant="solid" size="sm">
            {view.badge.label}
          </Badge>
        </span>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="선택 해제"
          className={styles.panelClose}
          onClick={onDeselect}
        >
          <X size={14} aria-hidden />
        </IconButton>
      </div>

      <InlineTitleInput value={clip.title} onChange={onTitleChange} readOnly={view.titleLocked} />

      {/* 미리보기 — 재생은 아직 없다(렌더 결과 파일이 없다). 장식이라 통째로 숨긴다 */}
      <div className={styles.previewWrap}>
        <div className={styles.preview} aria-hidden="true">
          <span className={styles.previewLabel}>선택한 편집본 미리보기</span>
          <span className={styles.previewPlay}>
            <Play size={18} fill="currentColor" />
          </span>
          {view.showDuration && duration ? (
            <span className={styles.previewDuration}>{duration}</span>
          ) : null}
        </div>
      </div>

      <div className={styles.actions} ref={actionsRef}>
        <PrimaryControl
          primary={view.primary}
          youtubeUrl={clip.youtubeUrl}
          onUpload={() => runTransition(onUpload)}
          onRetryRender={() => runTransition(onRetryRender)}
        />
        <div className={styles.actionRow}>
          {view.edit ? (
            <div className={styles.grow}>
              <LinkButton as={Link} href={view.edit.href} variant="soft" size="sm" fullWidth>
                {view.edit.label}
              </LinkButton>
            </div>
          ) : null}
          {view.download ? (
            <div className={styles.grow}>
              <Button variant="ghost" size="sm" fullWidth onClick={onDownload}>
                다운로드
              </Button>
            </div>
          ) : null}
          <IconButton variant="ghost" size="sm" aria-label="삭제" onClick={onDelete}>
            <Trash2 size={14} aria-hidden />
          </IconButton>
        </div>
        {view.note ? <p className={styles.note}>{noteText(view.note, role)}</p> : null}
      </div>

      <hr className={styles.divider} />

      <dl className={styles.meta}>
        <dt>원본 방송</dt>
        <dd>{clip.sourceLabel}</dd>
        <dt>원본 보존</dt>
        <dd data-urgent={retention.kind === 'active' && retention.urgent ? 'true' : undefined}>
          {retentionLabel(retention)}
        </dd>
        <dt>템플릿</dt>
        <dd>{clip.templateLabel}</dd>
        <dt>자막</dt>
        <dd>{clip.subtitleLabel}</dd>
        <dt>비율</dt>
        <dd>9:16 · 1080×1920</dd>
      </dl>

      {view.showRejection && clip.rejection ? (
        <>
          <hr className={styles.divider} />
          <div className={styles.reject}>
            <div className={styles.rejectHead}>
              <span className={styles.rejectTitle}>반려 사유</span>
              <time className={styles.rejectTime} dateTime={clip.rejection.at}>
                {dayTimeLabel(clip.rejection.at, now)}
              </time>
            </div>
            <div className={styles.rejectBody}>
              <span className={styles.rejectRule} aria-hidden="true" />
              <p className={styles.rejectReason}>{clip.rejection.reason}</p>
            </div>
          </div>
        </>
      ) : null}
    </>
  );
}

/**
 * 주 동작 한 줄. 갈 곳이 있으면 링크, 바꿀 것이 있으면 버튼이다. 「유튜브 보기」는 발행 주소가
 * 있을 때만 링크다 — 목업 업로드로 발행된 것은 주소가 없어 비활성 버튼으로 그린다
 * (href 없는 링크는 그리지 않는다 — LinkButton 규칙, 가짜 주소도 만들지 않는다 — ADR-044).
 * 스킴이 http(s)가 아닌 주소도 같은 비활성 버튼으로 떨어진다 — safeExternalUrl 참고.
 */
function PrimaryControl({
  primary,
  youtubeUrl,
  onUpload,
  onRetryRender,
}: {
  primary: DetailView['primary'];
  youtubeUrl: string | undefined;
  onUpload: () => void;
  onRetryRender: () => void;
}) {
  switch (primary.kind) {
    case 'link':
      return (
        <LinkButton as={Link} href={primary.href} variant={primary.variant} size="md" fullWidth>
          {primary.label}
        </LinkButton>
      );
    case 'external': {
      const href = safeExternalUrl(youtubeUrl);
      return href ? (
        <LinkButton
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          variant="solid"
          size="md"
          fullWidth
          iconEnd={<ExternalLink size={14} />}
        >
          {primary.label}
          <VisuallyHidden> (새 창)</VisuallyHidden>
        </LinkButton>
      ) : (
        <Button variant="solid" size="md" fullWidth disabled>
          {primary.label}
        </Button>
      );
    }
    case 'action':
      return (
        <Button
          variant="solid"
          size="md"
          fullWidth
          onClick={primary.action === 'upload' ? onUpload : onRetryRender}
        >
          {primary.label}
        </Button>
      );
  }
}
