import clsx from 'clsx';
import { Badge, Button } from '@/ui';
import styles from './LiveScreen.module.css';
import type { LiveHighlight } from './useLiveMockState';

// 하이라이트 카드 한 행 — 상태별 배지·버튼 조합 (디자인 1b).
// "편집"·"업로드"는 클립 편집기(M2 · POK-107)·업로드 전까지 비활성.
function RowActions({ highlight }: { highlight: LiveHighlight }) {
  switch (highlight.status) {
    case 'scored':
      return (
        <>
          <Badge tone="point" variant="solid" size="sm">
            {highlight.score}점
          </Badge>
          <Button variant="solid" size="sm" disabled>
            편집
          </Button>
        </>
      );
    case 'manual':
      return (
        <>
          <Badge tone="neutral" variant="soft" size="sm">
            수동
          </Badge>
          <Button variant="soft" size="sm" disabled>
            편집
          </Button>
        </>
      );
    case 'editing':
      return (
        <Badge tone="accent" variant="soft" size="sm">
          편집 중 · {highlight.editorName}
        </Badge>
      );
    case 'clipped':
      return (
        <>
          <Badge tone="success" variant="soft" size="sm">
            클립 완료
          </Badge>
          <Button variant="ghost" size="sm" disabled>
            업로드
          </Button>
        </>
      );
    case 'unprocessed':
      return (
        <>
          <Badge tone="neutral" variant="soft" size="sm">
            미처리
          </Badge>
          <Button variant="soft" size="sm" disabled>
            편집
          </Button>
        </>
      );
    case 'expired':
      return (
        <Badge tone="danger" variant="soft" size="sm">
          만료
        </Badge>
      );
  }
}

export function HighlightRow({ highlight }: { highlight: LiveHighlight }) {
  return (
    <li
      className={clsx(
        styles.highlightRow,
        highlight.emphasized && styles.highlightRowEmphasized,
        highlight.status === 'expired' && styles.highlightRowExpired,
      )}
    >
      <span className={styles.highlightTime}>{highlight.timestamp}</span>
      <div className={styles.highlightBody}>
        <div className={styles.highlightTitle}>{highlight.title}</div>
        <div className={styles.highlightMeta}>{highlight.meta}</div>
      </div>
      <RowActions highlight={highlight} />
    </li>
  );
}
