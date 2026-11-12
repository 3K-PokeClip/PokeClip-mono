'use client';

import { memo } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { Bookmark, BookmarkCheck, EyeOff, Play, RotateCw } from 'lucide-react';
import clsx from 'clsx';
import {
  Button,
  IconButton,
  LinkButton,
  Progress,
  Spinner,
  Tooltip,
  VisuallyHidden,
  useToast,
} from '@/ui';
import { forgetLibraryList, setJumpCardHidden } from '@/api/clipEditor';
import styles from './LiveScreen.module.css';
import { cardViewFor, cardVisualFor } from './highlightCardView';
import type { CardVisual } from './useLiveDetailsMockState';
import {
  UNKNOWN_CLOCK,
  type LiveCardSnapshot,
  type LiveHighlight,
  type LiveStream,
} from './useLiveMockState';
import { ThumbnailImage } from '@/features/thumbnail/ThumbnailImage';
import { useLiveField } from './liveDataStore';
import { useCardEditorReady } from './useCardEditorReady';
import { useCardSave, type CardSaveState } from './cardSave';
import { CardNeedsEditorError } from '@/features/clips/editor/cardDraftErrors';

// 하이라이트 카드(시안 B2-1, 2026-11-04) — 사진이 카드의 몸통이다. 왼쪽 위 점수(수동은 「수동」), 오른쪽 위 「저장」 알약,
// 아래 그늘에 방송 시각 · 길이(왼쪽)와 경과(오른쪽). 사진 밑은 「편집」과 가리기만. 감지 사유 · 상태 배지 · 원클릭 업로드는 뺐다.
// 표시 규칙은 highlightCardView가 정한다: 이 파일은 그 결과를 그리기만 한다.

const LIBRARY_HREF = '/clips/library';

/**
 * 사진 오른쪽 위 「보관함 저장」 알약 — 글자가 붙어 무엇인지 바로 읽힌다. 저장되면 강조색 「저장됨」이 되고 누르면 보관함으로 간다
 * (같은 카드를 또 저장해 편집본이 둘 생기지 않게).
 * 세 상태 모두 같은 단추 하나다: 저장 중에 disabled로 막거나 저장 뒤 링크로 바꾸면 요소가 초점을 잃어 키보드 초점이 문서로 떨어진다
 * (PR #480 리뷰). 저장 중엔 aria-disabled로만 막는다
 */
function SavePill({
  state,
  onSave,
  onOpen,
}: {
  state: CardSaveState;
  onSave: () => void;
  onOpen: () => void;
}) {
  const saved = state === 'saved';
  const saving = state === 'saving';
  return (
    <button
      type="button"
      className={clsx(styles.cardSave, saved && styles.cardSaveDone)}
      aria-disabled={saving || undefined}
      onClick={saving ? undefined : saved ? onOpen : onSave}
      aria-label={
        saved ? '보관함에 저장됨 · 보관함 열기' : saving ? '보관함에 저장하는 중' : '보관함에 저장'
      }
    >
      {saving ? (
        <Spinner size="sm" />
      ) : saved ? (
        <BookmarkCheck size={13} aria-hidden />
      ) : (
        <Bookmark size={13} aria-hidden />
      )}
      {saving ? '저장 중' : saved ? '저장됨' : '저장'}
    </button>
  );
}

// memo인 이유 — 카드 목록이 바뀌어도(방금 생긴 카드의 「n초 전」은 매초다) 내용이 같은 카드는 객체가 그대로다(reuseHighlights).
// 긴 방송은 카드가 수백 장이라 전부 다시 그리면 매초 그만큼의 썸네일을 다시 맞춘다
export const HighlightCard = memo(function HighlightCard({
  highlight,
  stream,
  visuals,
  onSeek,
  onCardChange,
}: {
  highlight: LiveHighlight;
  stream: LiveStream;
  visuals: Record<string, CardVisual>;
  /** 카드로 옮긴다(초, 서버 기준점 축 — LiveHighlight.seekSeconds · 플레이어용 PDT — LiveHighlight.playerAtMs) */
  onSeek: (seconds: number, atMs: number | null | undefined) => void;
  /** 가리기·되살리기 답을 목록에 넣는다. 통로가 끊겼거나 끝난 방송이면 다음 목록 읽기(1분)까지 기다리지 않게 */
  onCardChange?: (card: LiveCardSnapshot) => void;
}) {
  const { toast } = useToast();
  const router = useRouter();
  const queryClient = useQueryClient();
  const view = cardViewFor(highlight);
  const visual = cardVisualFor(highlight, stream, visuals);
  const processing = view.state === 'processing';
  // 방송 시작부터 몇 초인지 모르면(UNKNOWN_CLOCK) 단추 이름 · 알림 글에 시각을 싣지 않는다: 「--:--:-- 시점으로 이동」은 읽히지 않는다
  const clock = highlight.timestamp === UNKNOWN_CLOCK ? null : highlight.timestamp;
  // 편집기는 방송 번호와 카드 번호(`card-23` → 23)로 클립을 연다
  const streamId = useLiveField('streamId');
  const cardId = highlight.id.replace(/^card-/, '');
  const editorHref = `/clips/editor?stream=${encodeURIComponent(streamId)}&card=${cardId}`;
  // 편집기가 쓰는 영상 · 소리 · 파형을 미리 받아, 다 와야 「편집」을 연다(POK-499). 버튼줄이 있는 카드만 받는다
  const editor = useCardEditorReady(streamId, cardId, view.showActions && !highlight.hidden);
  // 아직 「편집」을 못 여는 카드는 만드는 중인 카드 자리와 같은 마젠타 점선으로 두르고, 다 받으면(실패로 연 것도) 기본 카드로 돌아간다.
  // 미리 받지 않는 카드(가린 카드 · 만료 카드)는 상태가 loading에 머무르므로 받는 카드인지 먼저 본다
  const preparing =
    view.showActions &&
    !highlight.hidden &&
    (editor.state === 'loading' || editor.state === 'pending');
  // 보관함 저장 — 편집기를 열지 않고 clip 이 준 카드 구간 그대로 편집본을 만든다. 영상을 안 받으므로 편집 준비 중에도 된다
  const cardSave = useCardSave(streamId, cardId);
  const canSave = view.showActions && !highlight.hidden;
  const save = () => {
    cardSave.save().then(
      (snap) => {
        if (snap === null) return;
        forgetLibraryList(queryClient);
        // 되돌리기는 두지 않는다: 편집본을 지우는 문이 아직 없다(되돌릴 수 없는 일에 되돌리기를 달지 않는다, ADR-044)
        toast({
          tone: 'success',
          title: '보관함에 저장했어요',
          description: clock === null ? `편집본 #${snap.id}` : `편집본 #${snap.id} · ${clock} 장면`,
          action: { label: '보관함 열기', onClick: () => router.push(LIBRARY_HREF) },
        });
      },
      (error: unknown) =>
        toast(
          // clip 이 창 두 끝을 주지 않은 카드다 — 실패가 아니라 편집기에서 범위를 정할 일이다(POK-473 E7)
          error instanceof CardNeedsEditorError
            ? {
                tone: 'info',
                title: '이 카드는 편집기에서 범위를 정해 저장해 주세요',
                action: { label: '편집기에서 열기', onClick: () => router.push(editorHref) },
              }
            : { tone: 'error', title: '보관함에 저장하지 못했어요' },
        ),
    );
  };
  // 가리기·되살리기(POK-463). 실패는 카드가 제 칸에 그대로 남으니 토스트로만 알린다
  const setHidden = (hidden: boolean) => {
    setJumpCardHidden<LiveCardSnapshot>(cardId, hidden).then(
      (card) => onCardChange?.(card),
      () =>
        toast({
          tone: 'error',
          title: hidden ? '카드를 가리지 못했어요' : '카드를 되살리지 못했어요',
        }),
    );
  };

  return (
    <article
      ref={editor.ref}
      className={clsx(
        styles.card,
        preparing && styles.cardPreparing,
        view.dimmed && styles.cardDimmed,
      )}
    >
      <div className={styles.cardThumbBox}>
        {/* 카드 전체가 아니라 사진만 단추다 — 아래 버튼줄과 겹치면 버튼 안에 버튼이 된다. 저장 알약도 그래서 단추 밖 형제다 */}
        <button
          type="button"
          className={styles.cardThumb}
          onClick={() => onSeek(highlight.seekSeconds, highlight.playerAtMs)}
          aria-label={`${clock ?? highlight.title} 시점으로 이동`}
        >
          <ThumbnailImage
            src={highlight.thumbnailUrl}
            fallback={
              <span className={styles.cardThumbLabel} aria-hidden>
                하이라이트 장면
              </span>
            }
          />
          <span className={styles.cardShade} aria-hidden />
          {/* 수동 카드는 출처로 가린다: 점수 칸은 자동 카드도 비어 있을 수 있다(서버 score nullable) — 점수 없는 자동 카드는 칩을 안 그린다 */}
          {highlight.source === 'manual' ? (
            <span className={styles.cardScore} aria-hidden>
              수동
            </span>
          ) : highlight.score !== undefined ? (
            <span className={styles.cardScore} aria-hidden>
              {highlight.score}점
            </span>
          ) : null}
          <span className={styles.cardCaption} aria-hidden>
            <span className={styles.cardTime}>
              {highlight.timestamp}
              {visual.duration ? <small>{visual.duration}</small> : null}
            </span>
            {visual.timeAgo ? <span className={styles.cardAgo}>{visual.timeAgo}</span> : null}
          </span>
          {/* 준비 중에는 위치 눈금 자리에 도는 띠가 흐른다. 아니면 방송 전체에서 이 카드가 어디쯤인지(시크바와 같은 눈금) */}
          {preparing ? (
            <span className={styles.cardSweep} aria-hidden />
          ) : (
            <span className={styles.cardPosTrack} aria-hidden>
              <span className={styles.cardPosMark} style={{ left: `${visual.posPercent}%` }} />
            </span>
          )}
          {processing ? (
            <span className={styles.cardProcessing}>
              <Spinner size="sm" label="클립 생성 중" />
            </span>
          ) : null}
          {/* 올리면 「여기부터 보기」 — 사진이 시점 이동 단추라는 것을 보인다 */}
          <span className={styles.cardPlay} aria-hidden>
            <Play size={16} fill="currentColor" /> 여기부터 보기
          </span>
        </button>
        {canSave ? (
          <SavePill state={cardSave.state} onSave={save} onOpen={() => router.push(LIBRARY_HREF)} />
        ) : null}
      </div>
      <div className={styles.cardBody}>
        {/* 제목 · 점수 · 감지 사유는 사진 위에 안 쓰거나 aria-hidden이다(단추 이름이 덮는다). 듣는 쪽에는 남긴다 */}
        <h3 className={styles.cardTitleHidden}>{highlight.title}</h3>
        <VisuallyHidden>
          {[
            highlight.score !== undefined ? `${highlight.score}점` : null,
            visual.reason,
            visual.duration ? `길이 ${visual.duration}` : null,
            visual.timeAgo,
          ]
            .filter(Boolean)
            .join(' · ')}
        </VisuallyHidden>
        {processing && visual.progress !== null ? (
          <div className={styles.cardProgressRow}>
            <Progress value={visual.progress} size="sm" label="클립 생성 진행률" />
            <span className={styles.cardProgressValue}>{visual.progress}%</span>
          </div>
        ) : null}
        {highlight.hidden ? (
          // 가린 카드는 되살리기만 한다. 되살리기는 누구나 한다(가린 사람이 자리를 비워도 막히지 않게, 서버와 같은 규칙)
          <div className={styles.cardActions}>
            <Button
              variant="soft"
              size="sm"
              className={styles.cardGrow}
              onClick={() => setHidden(false)}
            >
              되살리기
            </Button>
          </div>
        ) : view.showActions ? (
          // 「편집」은 이 카드의 방송·카드 번호를 싣고 편집기로 들어간다. 카드를 찜하는 일은 없다(POK-393): 여러 편집자가
          // 같은 카드를 각자 편집한다.
          <div className={styles.cardActions}>
            {/* 카드는 전부 서버 카드(card-N)다. 화면에만 세우던 수동 마킹 카드(marked-…)는 없어졌다(QA 2026-10-11) */}
            {editor.state === 'ready' || editor.state === 'failed' ? (
              <LinkButton
                as={Link}
                href={editorHref}
                variant="solid"
                size="sm"
                className={styles.cardGrow}
              >
                편집
              </LinkButton>
            ) : (
              // 준비 전: 막고 까닭을 글로 쓴다(준비 중이라는 점선은 카드 테두리가 맡는다, cardPreparing)
              <Button
                variant="soft"
                size="sm"
                disabled
                className={styles.cardGrow}
                iconStart={<Spinner size="sm" />}
                aria-label={editor.state === 'pending' ? '녹화 기다리는 중' : '편집 준비 중'}
              >
                {editor.state === 'pending' ? '녹화 기다리는 중' : '편집 준비 중'}
              </Button>
            )}
            {/* 미리 받기가 실패해도 「편집」은 연다(편집기가 다시 받는다). 실패는 경고색 다시 시도 단추로 알린다 */}
            {editor.state === 'failed' ? (
              <Tooltip content="편집 영상을 미리 못 불러왔어요. 다시 시도" describe={false}>
                <IconButton
                  variant="ghost"
                  size="sm"
                  className={styles.cardRetry}
                  aria-label="편집 영상을 미리 못 불러왔어요. 다시 시도"
                  onClick={editor.retry}
                >
                  <RotateCw size={15} aria-hidden />
                </IconButton>
              </Tooltip>
            ) : null}
            {/* 가리기(POK-463): 가린 카드는 패널의 「숨김」 칸이 모은다 */}
            <Tooltip content="이 카드 가리기" describe={false}>
              <IconButton
                variant="ghost"
                size="sm"
                aria-label="이 카드 가리기"
                onClick={() => setHidden(true)}
              >
                <EyeOff size={15} aria-hidden />
              </IconButton>
            </Tooltip>
          </div>
        ) : (
          <p className={styles.cardNote}>되감기 범위를 지나 곧 사라져요</p>
        )}
      </div>
    </article>
  );
});
