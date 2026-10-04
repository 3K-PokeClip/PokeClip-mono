'use client';

import Link from 'next/link';

import { Bookmark, EyeOff } from 'lucide-react';
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
import styles from './LiveScreen.module.css';
import { cardViewFor, cardVisualFor } from './highlightCardView';
import type { CardVisual } from './useLiveDetailsMockState';
import type { LiveHighlight, LiveStream } from './useLiveMockState';
import { claimCard, hideCard } from './useLiveMockState';
import { ThumbnailImage } from '@/features/thumbnail/ThumbnailImage';
import { useLiveData } from './liveDataStore';

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
}: {
  highlight: LiveHighlight;
  stream: LiveStream;
  visuals: Record<string, CardVisual>;
  onSeek: (timestamp: string) => void;
}) {
  const { toast } = useToast();
  const view = cardViewFor(highlight);
  const visual = cardVisualFor(highlight, stream, visuals);
  const processing = view.state === 'processing';
  // 편집기는 방송 번호와 카드 번호(`card-23` → 23)로 클립을 연다
  const { streamId } = useLiveData();
  // 서버 카드(card-N)만 편집기가 찾을 수 있다
  const fromServer = highlight.id.startsWith('card-');
  const editorHref = `/clips/editor?stream=${encodeURIComponent(streamId)}&card=${highlight.id.replace(/^card-/, '')}`;

  return (
    <article
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
        {view.showActions ? (
          // 「편집」은 이 카드의 방송·카드 번호를 싣고 편집기로 들어간다.
          // 업로드·보관함 저장은 각자 라우트가 설 때까지 비활성으로 둔다.
          <div className={styles.cardActions}>
            {/* 「편집」을 누르는 것이 곧 「이 카드는 내가 집었다」다(clip claim) — 다른 편집자 화면에 「○○ 편집 중」이 뜬다.
                이미 남이 집은 카드면 서버가 거절하지만 들어가 보는 것까지 막지는 않는다. */}
            {fromServer ? (
              <LinkButton
                as={Link}
                href={editorHref}
                variant="soft"
                size="sm"
                onClick={() => {
                  void claimCard(highlight.id);
                }}
              >
                편집
              </LinkButton>
            ) : (
              // 이 화면에서 찍은 수동 마킹은 아직 서버에 없는 카드다(marked-…) — 편집기가 찾을 수 없어 잠근다(PR #200 codex)
              <Button variant="soft" size="sm" disabled>
                편집
              </Button>
            )}
            <Button variant="solid" size="sm" disabled>
              원클릭 업로드
            </Button>
            <IconButton variant="ghost" size="sm" aria-label="보관함에 저장" disabled>
              <Bookmark size={15} aria-hidden />
            </IconButton>
            {/* 숨기기(clip hide) — 시안에는 카드별 단추가 없어 같은 줄의 작은 아이콘 하나로 둔다. 숨긴 수는 패널 머리가 센다 */}
            {highlight.id.startsWith('card-') ? (
              <IconButton
                variant="ghost"
                size="sm"
                aria-label="이 카드 숨기기"
                onClick={() => {
                  void hideCard(highlight.id).then((r) => {
                    if (!r.ok) toast({ tone: 'error', title: '카드를 숨기지 못했어요' });
                  });
                }}
              >
                <EyeOff size={15} aria-hidden />
              </IconButton>
            ) : null}
          </div>
        ) : null}
      </div>
    </article>
  );
}
