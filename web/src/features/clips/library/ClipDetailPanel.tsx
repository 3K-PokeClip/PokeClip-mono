'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { ExternalLink, Play, Trash2, X } from 'lucide-react';
import { Badge, Button, IconButton, LinkButton, VisuallyHidden, useToast } from '@/ui';
import { ddayFor } from '@/features/broadcast/vod/vodListView';
import { InlineTitleInput } from './InlineTitleInput';
import { UploadResolutionControls } from './UploadResolutionControls';
import {
  detailViewForClip,
  durationLabel,
  noteText,
  previousUploadsOf,
  shownUploadOf,
  privacyNoteText,
  retentionLabel,
  safeExternalUrl,
  thumbnailFailureText,
  uploadFailureText,
  type DetailView,
  type PreviousUpload,
} from './libraryView';
import type { UploadResolution } from '@/api/clipEditor';
import type { ClipStatus, LibraryClip } from './useLibraryMockState';
import styles from './LibraryScreen.module.css';

// 시안 1g 우측 상세 패널의 내용. 무엇을 그릴지는 detailViewFor가 정하고 여기는 그리기만 한다.
// 패널 껍데기(폭 전환·inert)는 LibraryScreen이 갖는다 — 닫히는 동안에도 내용이 남아야 해서다.

/** 비율을 못 읽었을 때 다시 읽는 간격 */
const ASPECT_RETRY_MS = 10_000;

export function ClipDetailPanel({
  clip,
  status,
  now,
  onDeselect,
  onTitleChange,
  onUpload,
  onRetryRender,
  onRetryUpload,
  onResolveUpload,
  onDownload,
  onDelete,
  sending = false,
  loadPreview,
  loadAspect,
}: {
  clip: LibraryClip;
  status: ClipStatus;
  now: Date;
  onDeselect: () => void;
  onTitleChange: (title: string) => void;
  onUpload: () => void;
  onRetryRender: () => void;
  /** 업로드 실패를 저장된 정보 그대로 다시 올린다(POK-291) */
  onRetryUpload: () => void;
  /** 「확인 필요」 업로드를 채널에서 본 결과로 푼다(POK-333). 없으면(목업 · 시험 주입) 단추를 안 그린다 */
  onResolveUpload?: (uploadId: number, resolution: UploadResolution) => Promise<boolean>;
  onDownload: () => void;
  onDelete: () => void;
  /** 업로드 주문·다시 시도를 보내는 중: 주 동작을 잠근다 */
  sending?: boolean;
  /** 완성 영상 주소를 받는다. 없으면(목업 줄) 미리보기는 장식이다 */
  loadPreview?: () => Promise<string | null>;
  /** 레시피 출력의 비율(「9:16」). 없으면(목업 줄) 비율을 모른다 */
  loadAspect?: () => Promise<string | null>;
}) {
  const view = detailViewForClip(clip, status);
  const uploadFailure = uploadFailureText(clip);
  const thumbnailFailure = thumbnailFailureText(clip);
  const privacyNote = privacyNoteText(clip, now);
  const previousUploads = previousUploadsOf(clip.entry);
  const shown = clip.entry ? shownUploadOf(clip.entry) : null;
  const checkingUpload = shown?.status === 'checking' ? shown : null;
  const playable = loadPreview !== undefined && clip.entry?.latestClip?.status === 'rendered';
  const duration = durationLabel(clip, status);
  const retention = ddayFor(clip.sourceExpiresAt, now);

  // 비율은 레시피에 있어 패널을 열 때 상세를 읽는다(POK-254). 판이 바뀌면 다시 읽고, 다른 편집본의 늦은 답은 버린다.
  // 못 읽으면 「—」로 굳히지 않고 10초 뒤 다시 읽는다: 패널은 닫아도 내려가지 않아 다시 열어도 새로 읽지 않는다(PR #256 codex)
  const aspectKey = `${clip.id}:${clip.entry?.recipeVersion ?? ''}`;
  const loadAspectRef = useRef(loadAspect);
  loadAspectRef.current = loadAspect;
  const [aspect, setAspect] = useState<{ key: string; label: string | null } | null>(null);
  useEffect(() => {
    let alive = true;
    let retry: number | undefined;
    const read = () => {
      const load = loadAspectRef.current;
      if (load === undefined) return;
      load().then(
        (label) => alive && setAspect({ key: aspectKey, label }),
        () => {
          if (alive) retry = window.setTimeout(read, ASPECT_RETRY_MS);
        },
      );
    };
    read();
    return () => {
      alive = false;
      window.clearTimeout(retry);
    };
  }, [aspectKey]);
  const aspectLabel = aspect?.key === aspectKey ? aspect.label : null;

  // 업로드·렌더 재시도는 상태를 바꾸고, 그러면 주 동작이 button ↔ anchor로 갈리거나 비활성이
  // 된다 — 누르고 있던 노드가 사라져 포커스가 body로 떨어진다. 전이를 일으킨 경우에만
  // 새 주 동작(비활성이면 그다음 조작부)으로 포커스를 옮긴다.
  // 🔴 포커스가 실제로 떨어졌을 때만 옮긴다. 누른 뒤 아무것도 안 바뀐 경우(제목이 틀려 업로드가 안 나감)에도 표시가 남아,
  // 다음에 제목을 칠 때 포커스를 단추로 빼앗으면 이어 친 스페이스가 반쯤 친 제목으로 업로드를 누른다(로컬 리뷰 2라운드).
  const actionsRef = useRef<HTMLDivElement>(null);
  const refocusAfterTransition = useRef(false);
  useEffect(() => {
    if (!refocusAfterTransition.current) return;
    refocusAfterTransition.current = false;
    const active = document.activeElement;
    const stillFocused =
      active instanceof HTMLElement &&
      active !== document.body &&
      active.isConnected &&
      !(active as HTMLButtonElement).disabled;
    if (stillFocused) return;
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

      {/* 주문을 보내는 동안에도 잠근다 — 답이 오면 보낸 제목으로 덮여 그사이 고친 글자가 사라진다 */}
      <InlineTitleInput
        value={clip.title}
        onChange={onTitleChange}
        readOnly={view.titleLocked || sending}
      />
      {view.titleIsDraft ? (
        <p className={styles.titleDraftNote}>
          올릴 때 쓰는 제목이에요 · 새로 고침하면 처음 제목으로 돌아가요
        </p>
      ) : null}

      {/* 미리보기 — 완성 영상이 있으면 눌러서 튼다(주소는 그때 받는다). 없으면 장식이라 통째로 숨긴다.
          영상이 바뀌면(다시 렌더) 받은 주소를 버리도록 영상 번호로 새로 그린다 */}
      <div className={styles.previewWrap}>
        {playable ? (
          <PreviewPlayer
            key={`${clip.id}:${clip.entry?.latestClip?.id}`}
            load={loadPreview}
            duration={view.showDuration ? duration : null}
          />
        ) : (
          <div className={styles.preview} aria-hidden="true">
            <span className={styles.previewLabel}>선택한 편집본 미리보기</span>
            <span className={styles.previewPlay}>
              <Play size={18} fill="currentColor" />
            </span>
            {view.showDuration && duration ? (
              <span className={styles.previewDuration}>{duration}</span>
            ) : null}
          </div>
        )}
      </div>

      <div className={styles.actions} ref={actionsRef}>
        <PrimaryControl
          editHref={clip.editHref ?? '/clips/editor'}
          primary={view.primary}
          youtubeUrl={clip.youtubeUrl}
          sending={sending}
          onUpload={() => runTransition(onUpload)}
          onRetryRender={() => runTransition(onRetryRender)}
          onRetryUpload={() => runTransition(onRetryUpload)}
        />
        <div className={styles.actionRow}>
          {view.edit ? (
            <div className={styles.grow}>
              <LinkButton
                as={Link}
                href={clip.editHref ?? view.edit.href}
                variant="soft"
                size="sm"
                fullWidth
              >
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
          {/* 서버 편집본은 지우는 창구가 없다(편집본 영구 보존, POK-124) — 지운 척하지 않게 잠근다(PR #200 codex) */}
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="삭제"
            disabled={clip.entry !== undefined}
            title={clip.entry !== undefined ? '편집본은 지우지 않고 보관돼요' : undefined}
            onClick={onDelete}
          >
            <Trash2 size={14} aria-hidden />
          </IconButton>
        </div>
        {/* 진행 한 줄(만드는 중 N% · 올리는 중)은 원래 안내 자리에 둔다: 「자막」 칸은 자막 정보 자리다(POK-291) */}
        {clip.progressLabel ? <p className={styles.note}>{clip.progressLabel}</p> : null}
        {view.note ? <p className={styles.note}>{noteText(view.note)}</p> : null}
        {/* 확인 필요를 사람이 푼다(POK-333): 위쪽이 보이는 업로드가 확인 필요일 때만 */}
        {checkingUpload && onResolveUpload ? (
          // 업로드 번호를 열쇠로: 창이 열린 사이 목록 새로 읽기가 다른 업로드로 바꾸면 입력 · 확인 상태를 버린다(PR #261 codex)
          <UploadResolutionControls
            key={checkingUpload.id}
            busy={sending}
            onResolve={(resolution) => onResolveUpload(checkingUpload.id, resolution)}
          />
        ) : null}
        {uploadFailure ? <p className={styles.note}>{uploadFailure}</p> : null}
        {thumbnailFailure ? <p className={styles.note}>{thumbnailFailure}</p> : null}
        {privacyNote ? <p className={styles.note}>{privacyNote}</p> : null}
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
        <dd>{aspectLabel ?? '—'}</dd>
      </dl>

      {/* 이전 판으로 올린 영상(POK-334): 새 판을 만들어도 옛 판 업로드가 유튜브에 남아 있다. 모양은 위 칸을 줄마다 반복 */}
      {previousUploads.length > 0 ? (
        <>
          <hr className={styles.divider} />
          <section aria-label="이전 판으로 올린 영상">
            <dl className={styles.meta}>
              {previousUploads.map((u) => (
                <PreviousUploadRow
                  key={u.id}
                  upload={u}
                  busy={sending}
                  onResolve={
                    u.checking && onResolveUpload
                      ? (resolution) => onResolveUpload(u.id, resolution)
                      : undefined
                  }
                />
              ))}
            </dl>
          </section>
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
  editHref,
  youtubeUrl,
  sending,
  onUpload,
  onRetryRender,
  onRetryUpload,
}: {
  primary: DetailView['primary'];
  editHref: string;
  youtubeUrl: string | undefined;
  sending: boolean;
  onUpload: () => void;
  onRetryRender: () => void;
  onRetryUpload: () => void;
}) {
  switch (primary.kind) {
    case 'link':
      return (
        <LinkButton
          as={Link}
          href={primary.href === '/clips/editor' ? editHref : primary.href}
          variant={primary.variant}
          size="md"
          fullWidth
        >
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
          disabled={primary.action !== 'retryRender' && sending}
          onClick={
            primary.action === 'upload'
              ? onUpload
              : primary.action === 'retryUpload'
                ? onRetryUpload
                : onRetryRender
          }
        >
          {primary.label}
        </Button>
      );
    case 'busy':
      return (
        <Button variant="solid" size="md" fullWidth disabled>
          {primary.label}
        </Button>
      );
  }
}

/**
 * 완성 영상 미리보기. 누르기 전에는 시안의 재생 단추 그대로이고, 누르면 주소를 받아 그 자리에서 튼다. 주소는 60분짜리라
 * 패널을 열 때마다 미리 받지 않는다.
 */
function PreviewPlayer({
  load,
  duration,
}: {
  load: () => Promise<string | null>;
  duration: string | null;
}) {
  const { toast } = useToast();
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  if (url !== null) {
    return (
      <div className={styles.preview}>
        <video
          className={styles.previewVideo}
          src={url}
          controls
          autoPlay
          playsInline
          aria-label="선택한 편집본 미리보기"
          // 주소는 60분짜리다. 만료(403) 등으로 못 읽으면 재생 단추로 돌아가 누를 때 새 주소를 받는다
          onError={() => {
            setUrl(null);
            toast({
              tone: 'error',
              title: '미리보기를 틀지 못했어요',
              description: '다시 누르면 새 주소로 틀어요. 계속 안 되면 다운로드로 받아 보세요.',
            });
          }}
        />
      </div>
    );
  }
  return (
    <button
      type="button"
      className={styles.preview}
      aria-label="미리보기 재생"
      disabled={loading}
      onClick={() => {
        setLoading(true);
        void load().then((next) => {
          if (!alive.current) return;
          setLoading(false);
          setUrl(next);
        });
      }}
    >
      <span className={styles.previewLabel} aria-hidden="true">
        선택한 편집본 미리보기
      </span>
      <span className={styles.previewPlay} aria-hidden="true">
        <Play size={18} fill="currentColor" />
      </span>
      {duration ? (
        <span className={styles.previewDuration} aria-hidden="true">
          {duration}
        </span>
      ) : null}
    </button>
  );
}

/**
 * 이전 판 업로드 한 줄. 올린 것만 유튜브로 가는 링크이고, 올리는 중·확인 필요는 글자만이다. 확인 필요면 결과를 적는 단추를 붙인다
 * (POK-333: 새 판을 주문해 위쪽이 새 판을 보여도 옛 판의 확인 필요를 풀 수 있게)
 */
function PreviousUploadRow({
  upload,
  busy,
  onResolve,
}: {
  upload: PreviousUpload;
  busy: boolean;
  onResolve?: (resolution: UploadResolution) => Promise<boolean>;
}) {
  return (
    <>
      <dt>{`이전 판 ${upload.versionLabel}`}</dt>
      <dd>
        {upload.href ? (
          <a href={upload.href} target="_blank" rel="noopener noreferrer">
            {upload.text}
            <VisuallyHidden> (새 창)</VisuallyHidden>
          </a>
        ) : (
          upload.text
        )}
        {onResolve ? (
          <UploadResolutionControls
            label={`이전 판 ${upload.versionLabel}`}
            busy={busy}
            onResolve={onResolve}
          />
        ) : null}
      </dd>
    </>
  );
}
