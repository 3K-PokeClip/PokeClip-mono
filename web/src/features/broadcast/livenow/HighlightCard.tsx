'use client';

import Link from 'next/link';

import { Bookmark, EyeOff, RotateCw } from 'lucide-react';
import clsx from 'clsx';
import {
  Badge,
  Button,
  IconButton,
  LinkButton,
  Progress,
  Spinner,
  VisuallyHidden,
  useToast,
} from '@/ui';
import { setJumpCardHidden } from '@/api/clipEditor';
import styles from './LiveScreen.module.css';
import { cardViewFor, cardVisualFor } from './highlightCardView';
import type { CardVisual } from './useLiveDetailsMockState';
import type { LiveCardSnapshot, LiveHighlight, LiveStream } from './useLiveMockState';
import { ThumbnailImage } from '@/features/thumbnail/ThumbnailImage';
import { useLiveData } from './liveDataStore';
import { useCardEditorReady } from './useCardEditorReady';

// 하이라이트 카드(시안 1b) — 세로 목록의 한 행이던 것을 가로로 넘기는 카드로 바꿨다.
// 표시 규칙은 전부 highlightCardView가 정한다: 이 파일은 그 결과를 그리기만 한다.

/** 미니 파형 뷰박스 — 진폭 0..1을 여기 맞춰 편다 */
const SPARK_WIDTH = 100;
const SPARK_HEIGHT = 22;

function sparkPoints(spark: readonly number[]): string {
  if (spark.length < 2) return '';
  return spark
    .map((amplitude, index) => {
      const x = (index / (spark.length - 1)) * SPARK_WIDTH;
      const y = SPARK_HEIGHT - Math.min(1, Math.max(0, amplitude)) * SPARK_HEIGHT;
      return `${Math.round(x * 10) / 10},${Math.round(y * 10) / 10}`;
    })
    .join(' ');
}

export function HighlightCard({
  highlight,
  stream,
  visuals,
  onSeek,
  onCardChange,
}: {
  highlight: LiveHighlight;
  stream: LiveStream;
  visuals: Record<string, CardVisual>;
  onSeek: (timestamp: string) => void;
  /** 가리기·되살리기 답을 목록에 넣는다. 통로가 끊겼거나 끝난 방송이면 다음 목록 읽기(1분)까지 기다리지 않게 */
  onCardChange?: (card: LiveCardSnapshot) => void;
}) {
  const { toast } = useToast();
  const view = cardViewFor(highlight);
  const visual = cardVisualFor(highlight, stream, visuals);
  const processing = view.state === 'processing';
  // 편집기는 방송 번호와 카드 번호(`card-23` → 23)로 클립을 연다
  const { streamId } = useLiveData();
  const cardId = highlight.id.replace(/^card-/, '');
  const editorHref = `/clips/editor?stream=${encodeURIComponent(streamId)}&card=${cardId}`;
  // 편집기가 쓰는 영상 · 소리 · 파형을 미리 받아, 다 와야 「편집」을 연다(POK-499). 버튼줄이 있는 카드만 받는다
  const editor = useCardEditorReady(streamId, cardId, view.showActions && !highlight.hidden);
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
        highlight.emphasized && styles.cardEmphasized,
        view.dimmed && styles.cardDimmed,
      )}
    >
      {/* 카드 전체가 아니라 썸네일만 버튼이다 — 아래 버튼줄과 겹치면 버튼 안에 버튼이 된다 */}
      <button
        type="button"
        className={styles.cardThumb}
        onClick={() => onSeek(highlight.timestamp)}
        aria-label={`${highlight.timestamp} 시점으로 이동`}
      >
        <ThumbnailImage
          src={highlight.thumbnailUrl}
          fallback={
            <span className={styles.cardThumbLabel} aria-hidden>
              하이라이트 장면
            </span>
          }
        />
        {visual.spark ? (
          <svg
            className={styles.cardSpark}
            viewBox={`0 0 ${SPARK_WIDTH} ${SPARK_HEIGHT}`}
            preserveAspectRatio="none"
            aria-hidden
          >
            <polyline points={sparkPoints(visual.spark)} vectorEffect="non-scaling-stroke" />
          </svg>
        ) : null}
        {/* 방송 전체에서 이 카드가 어디쯤인지 — 시크바와 같은 눈금이다 */}
        <span className={styles.cardPosTrack} aria-hidden>
          <span className={styles.cardPosMark} style={{ left: `${visual.posPercent}%` }} />
        </span>
        {highlight.score !== undefined ? (
          <span className={styles.cardScore} aria-hidden>
            <Badge tone="point" variant="solid" size="sm">
              {highlight.score}점
            </Badge>
          </span>
        ) : null}
        <span className={styles.cardReason} aria-hidden>
          {visual.reason}
        </span>
        {visual.duration ? (
          <span className={styles.cardDuration} aria-hidden>
            {visual.duration}
          </span>
        ) : null}
        {processing ? (
          <span className={styles.cardProcessing}>
            <Spinner size="sm" label="클립 생성 중" />
          </span>
        ) : null}
      </button>
      <div className={styles.cardBody}>
        <h3 className={styles.cardTitle}>{highlight.title}</h3>
        <p className={styles.cardMeta}>{highlight.meta}</p>
        <div className={styles.cardStatusRow}>
          <Badge tone={view.badgeTone} variant="soft" size="sm">
            {view.badgeLabel}
          </Badge>
          {visual.timeAgo ? <span className={styles.cardTimeAgo}>{visual.timeAgo}</span> : null}
        </div>
        {/* 점수·사유·길이는 시안상 썸네일 위에만 있는데 그쪽은 aria-hidden이다(버튼 이름을
            덮어써 자식 글이 읽히지 않는다). 카드를 정렬하는 근거인 점수를 듣는 쪽에도 남긴다. */}
        <VisuallyHidden>
          {[
            highlight.score !== undefined ? `${highlight.score}점` : null,
            visual.reason,
            visual.duration ? `길이 ${visual.duration}` : null,
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
            <Button variant="soft" size="sm" onClick={() => setHidden(false)}>
              되살리기
            </Button>
          </div>
        ) : view.showActions ? (
          // 「편집」은 이 카드의 방송·카드 번호를 싣고 편집기로 들어간다. 카드를 찜하는 일은 없다(POK-393): 여러 편집자가
          // 같은 카드를 각자 편집한다. 업로드·보관함 저장은 각자 라우트가 설 때까지 비활성으로 둔다.
          <div className={styles.cardActions}>
            {/* 카드는 전부 서버 카드(card-N)다. 화면에만 세우던 수동 마킹 카드(marked-…)는 없어졌다(QA 2026-10-11) */}
            {editor.state === 'ready' || editor.state === 'failed' ? (
              <LinkButton as={Link} href={editorHref} variant="soft" size="sm">
                편집
              </LinkButton>
            ) : (
              // 준비 전: 회색으로 막고, 만드는 중인 카드 자리와 같은 점선 · 도는 표시를 쓴다
              <Button
                variant="soft"
                size="sm"
                disabled
                className={styles.cardEditPending}
                iconStart={<Spinner size="sm" />}
                aria-label={editor.state === 'pending' ? '편집 녹화를 기다리는 중' : '편집 준비 중'}
              >
                편집
              </Button>
            )}
            {/* 미리 받기가 실패해도 「편집」은 연다(편집기가 다시 받는다). 실패는 다시 시도 단추로 알린다 */}
            {editor.state === 'failed' ? (
              <IconButton
                variant="ghost"
                size="sm"
                aria-label="편집 영상을 미리 못 불러왔어요. 다시 시도"
                title="편집 영상을 미리 못 불러왔어요. 다시 시도"
                onClick={editor.retry}
              >
                <RotateCw size={15} aria-hidden />
              </IconButton>
            ) : null}
            <Button variant="solid" size="sm" disabled>
              원클릭 업로드
            </Button>
            <IconButton variant="ghost" size="sm" aria-label="보관함에 저장" disabled>
              <Bookmark size={15} aria-hidden />
            </IconButton>
            {/* 가리기(POK-463): 시안에 카드별 단추가 없어 같은 줄의 작은 아이콘 하나로 둔다. 가린 카드는 패널의 「숨김」 칸이 모은다 */}
            <IconButton
              variant="ghost"
              size="sm"
              aria-label="이 카드 가리기"
              onClick={() => setHidden(true)}
            >
              <EyeOff size={15} aria-hidden />
            </IconButton>
          </div>
        ) : null}
      </div>
    </article>
  );
}
