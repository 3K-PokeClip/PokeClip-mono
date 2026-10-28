'use client';

import { memo, useState } from 'react';
import Link from 'next/link';
import { Lock, MessageSquare, PanelRightClose } from 'lucide-react';
import { Badge, IconButton, LinkButton, VisuallyHidden } from '@/ui';
import { LINK_LOCK_WORD, type LinkLock } from './linkLock';
import liveStyles from './LiveScreen.module.css';
import statsStyles from './LiveStatsPanel.module.css';
import styles from './LinkLockedPanels.module.css';

// 방송 채널 연동이 없어 잠근 채팅 · 통계(linkLock). 「잠긴 미리보기」(OwnerOnly의 시안 비교 B안): 원래 패널과 같은 틀 · 같은 자리에
// 흐린 미리보기를 깔고 가운데에 까닭과 채널 연동 화면으로 가는 버튼을 둔다. 틀이 같아야 연동해서 잠금이 풀릴 때 화면이 출렁이지 않는다.
// 문구에 플랫폼 이름을 쓰지 않는다(「방송 채널」): 씨미 창구가 붙어도 그대로 참이다. 「연동하면 곧 보여요」라고 약속하지 않고 「연동해야
// 보여요」라고 쓴다: 방송 중에 연동하면 수집기가 다음 재부착 회차에 붙는데, 재부착을 끈 배포에서는 다음 방송부터다(services/README
// 「알려진 한계」 ①)

type LockReason = Exclude<LinkLock, null>;

/** 채널 연동 화면. 처음 연동도 다시 연결도 거기서 한다 */
const CHANNEL_SETTINGS_PATH = '/settings/channels';

const ACTION: Record<LockReason, string> = {
  unlinked: '채널 연동하기',
  broken: '다시 연결하기',
};

const CHAT_COPY: Record<LockReason, { title: string; text: string }> = {
  unlinked: {
    title: '방송 채널을 연동해야 채팅이 보여요',
    text: '채팅은 연동한 채널에서 모아 와요',
  },
  broken: {
    title: '방송 채널 연동이 끊겼어요',
    text: '다시 연결해야 채팅을 모아 올 수 있어요',
  },
};

const STATS_COPY: Record<LockReason, { title: string; text: string }> = {
  unlinked: {
    title: '방송 채널을 연동해야 통계가 보여요',
    text: '시청자 수 · 채팅 · 후원 흐름은 연동한 채널에서 모아 와요',
  },
  broken: {
    title: '방송 채널 연동이 끊겼어요',
    text: '다시 연결해야 시청자 수 · 채팅 · 후원 흐름을 모아 올 수 있어요',
  },
};

const pcu = (n: number) => `calc(${n} * var(--pc-u))`;

/**
 * 흐린 채팅 미리보기의 줄(이름 폭 u, 글 폭 %). 고정 무늬다: 그릴 때마다 바뀌면 잠긴 패널이 움직이는 것처럼 보인다. 실제 채팅이
 * 아니라 막대다: 흐려도 글자 꼴이 비치면 진짜 대화가 가려진 것처럼 읽힌다. 실제 채팅처럼 바닥부터 쌓고(CSS) 큰 화면의 패널 높이도
 * 채우게 넉넉히 둔다: 넘치는 위쪽 줄은 잘린다
 */
const CHAT_PREVIEW = Array.from(
  { length: 40 },
  (_, i) => [36 + ((i * 7) % 20), 40 + ((i * 23) % 47)] as const,
);

/** 흐린 그래프 미리보기: 시청자 선(위 층)과 채팅 막대(아래 층)의 높이(층 높이 대비). 실제 그래프의 두 층과 같은 자리에 선다 */
const VIEWER_PREVIEW = [
  0.32, 0.36, 0.42, 0.47, 0.5, 0.56, 0.6, 0.64, 0.62, 0.66, 0.72, 0.76, 0.74, 0.7, 0.73, 0.79, 0.84,
  0.82, 0.78, 0.8, 0.85, 0.88, 0.86, 0.83,
];
const CHAT_BARS_PREVIEW = [
  0.42, 0.55, 0.48, 0.66, 0.52, 0.74, 0.6, 0.45, 0.68, 0.92, 0.62, 0.5, 0.7, 0.56, 0.44, 0.6, 0.76,
  0.54, 0.47, 0.65, 0.58, 0.43, 0.63, 0.51,
];
const VIEWER_POINTS = VIEWER_PREVIEW.map(
  (ratio, i) =>
    `${((i / (VIEWER_PREVIEW.length - 1)) * 100).toFixed(2)},${(40 - ratio * 36).toFixed(2)}`,
).join(' ');

/** 숫자 줄 미리보기의 칸 — 실제 통계와 같은 이름 · 같은 표시 */
const STRIP_PREVIEW: readonly (readonly [string, string | undefined])[] = [
  ['채팅', statsStyles.keyBar],
  ['시청자', statsStyles.keyLine],
  ['하이라이트', statsStyles.keyDot],
  ['후원', statsStyles.keyGold],
];

function LockNotice({ reason, title, text }: { reason: LockReason; title: string; text: string }) {
  return (
    <div className={styles.notice}>
      <span className={styles.icon} aria-hidden>
        <Lock />
      </span>
      <p className={styles.title}>{title}</p>
      <p className={styles.text}>{text}</p>
      <LinkButton as={Link} href={CHANNEL_SETTINGS_PATH} variant="solid" size="sm">
        {ACTION[reason]}
      </LinkButton>
    </div>
  );
}

/**
 * 잠금이 걸리고 풀리는 것을 화면 낭독기에 알린다. 실제 채팅 패널은 배지를 늘 서 있는 status 안에 두어 수집 상태가 바뀌면 읽히는데,
 * 잠금은 채팅 · 통계 패널을 통째로 바꿔 끼운다: 새로 들어온 리전은 처음 그려질 때 읽히지 않아 잠기고 풀리는 순간을 듣는 사람은
 * 몰랐다(PR #453 리뷰). 그래서 잠금과 상관없이 늘 서 있는 칸 하나에서 글만 바꾼다. 처음 그린 상태는 알리지 않는다(화면을 열면
 * 어차피 읽는다). 앱에는 공용 알림 장치(LiveRegionProvider)가 붙어 있지 않다
 */
export function LinkLockAnnouncer({
  lock,
  liveOwner,
}: {
  lock: LinkLock;
  /** 지금도 방송 중인 내 방송인가. 이때 풀렸으면 까닭은 연동뿐이다 */
  liveOwner: boolean;
}) {
  const [seen, setSeen] = useState(lock);
  const [message, setMessage] = useState('');
  // 바뀐 그리기에서 바로 글을 바꾼다(렌더 중 상태 고침: React의 「이전 값에 맞춰 상태 고치기」 방식). 잠금은 방송이 끝나거나 다른
  // 방송으로 넘어가 저장소가 비어도 풀린다: 그때 「연동돼」라고 하면 거짓이라 알리지 않는다(PR #453 2차 리뷰)
  if (seen !== lock) {
    setSeen(lock);
    setMessage(
      lock !== null ? ANNOUNCE[lock] : seen !== null && liveOwner ? UNLOCKED_ANNOUNCE : '',
    );
  }
  return <VisuallyHidden role="status">{message}</VisuallyHidden>;
}

const ANNOUNCE: Record<LockReason, string> = {
  unlinked: '방송 채널 연동이 없어 채팅과 통계를 잠갔어요',
  broken: '방송 채널 연동이 끊겨 채팅과 통계를 잠갔어요',
};
const UNLOCKED_ANNOUNCE = '방송 채널이 연동돼 채팅과 통계를 다시 보여요';

/**
 * 잠근 실시간 채팅. 머리줄(이름 · 배지 · 접기)은 실제 패널과 같다: 접으면 플레이어의 「채팅 열기」로 되살린다. memo인 까닭은 실제
 * 패널과 같다: 화면이 경과 표기 때문에 매초 다시 그려지는데, 받는 값(까닭 · 안정된 접기)은 거의 안 바뀐다
 */
export const LockedChatPanel = memo(function LockedChatPanel({
  reason,
  onCollapse,
}: {
  reason: LockReason;
  onCollapse: () => void;
}) {
  return (
    <aside className={liveStyles.chatPanel} aria-label="실시간 채팅">
      <div className={liveStyles.chatHeader}>
        <MessageSquare size={15} aria-hidden className={liveStyles.chatHeaderIcon} />
        <span className={liveStyles.chatHeading}>실시간 채팅</span>
        <Badge tone={reason === 'broken' ? 'warning' : 'neutral'} variant="soft" size="sm">
          {LINK_LOCK_WORD[reason]}
        </Badge>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="채팅 패널 접기"
          className={liveStyles.chatCollapse}
          onClick={onCollapse}
        >
          <PanelRightClose size={14} aria-hidden />
        </IconButton>
      </div>
      <div className={styles.chatBody}>
        <ul className={styles.chatPreview} aria-hidden>
          {CHAT_PREVIEW.map(([name, text], i) => (
            <li key={i}>
              <span className={styles.previewName} style={{ width: pcu(name) }} />
              <span className={styles.previewText} style={{ width: `${text}%` }} />
            </li>
          ))}
        </ul>
        <LockNotice reason={reason} {...CHAT_COPY[reason]} />
      </div>
    </aside>
  );
});

/** 잠근 실시간 통계. 방송 중에만 잠그므로 제목은 늘 「실시간 통계」다. memo인 까닭은 잠근 채팅과 같다 */
export const LockedStatsPanel = memo(function LockedStatsPanel({ reason }: { reason: LockReason }) {
  return (
    <section className={statsStyles.panel} aria-label="실시간 통계">
      <div className={statsStyles.head}>
        <h2 className={statsStyles.headTitle}>실시간 통계</h2>
      </div>
      <div className={styles.statsBody}>
        <div className={styles.statsPreview} aria-hidden>
          <div className={statsStyles.strip}>
            {STRIP_PREVIEW.map(([label, keyClass]) => (
              <div key={label} className={statsStyles.cell}>
                <span className={statsStyles.cellLabel}>
                  <span className={keyClass} />
                  {label}
                </span>
                <span className={styles.previewValue} />
              </div>
            ))}
          </div>
          <div className={statsStyles.body}>
            <div className={styles.chartPreview}>
              <svg
                className={styles.viewerLine}
                viewBox="0 0 100 40"
                preserveAspectRatio="none"
                focusable="false"
              >
                <polyline points={VIEWER_POINTS} />
              </svg>
              <div className={styles.chatBars}>
                {CHAT_BARS_PREVIEW.map((ratio, i) => (
                  <span key={i} style={{ height: `${Math.round(ratio * 100)}%` }} />
                ))}
              </div>
            </div>
          </div>
        </div>
        <LockNotice reason={reason} {...STATS_COPY[reason]} />
      </div>
    </section>
  );
});
