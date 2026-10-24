'use client';

import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import clsx from 'clsx';
import { MessageSquare, PanelRightClose } from 'lucide-react';
import { Badge, IconButton, Tag, VisuallyHidden } from '@/ui';
import { REPLAY_BLOCK_CLASS } from '@/lib/analytics';
import { formatPerMinute } from './liveDataStore';
import styles from './LiveScreen.module.css';
import type { ChatPanelMessage, ChatSurge } from './useChatPanelMockState';

// 실시간 채팅 패널(시안 1b) — 급증 키워드 · 후원 강조 · 하이라이트 감지 알림 · 하단 상태줄.
// 시안 갱신(2026-09-11, POK-239): 패널은 화면 높이에 sticky로 붙고(CSS), 목록은 자체 스크롤.
// 접으면 패널이 사라지고 플레이어 상단의 「채팅 열기」가 되살린다(시안의 세로 레일은 사용자
// 결정으로 쓰지 않는다).
//
// 수집 상태는 이 헤더의 배지가 말한다(옛 본문 경고 배너에서 옮겨 왔다) — 티켓이 정한 자리이고,
// 채팅이 흐르는 곳 바로 위라 "지금 안 들어오고 있다"가 눈에 들어온다.
// ADR-048에 따라 「다시 하면 복구」 같은 문구는 두지 않는다 — 실연동 뒤 거짓이 된다.

/** 바닥에서 이만큼 안이면 「끝에 있다」로 본다 — 서브픽셀 스크롤·줌 잔차 흡수 */
const FOLLOW_SLACK_PX = 8;

/** 어느 플랫폼 줄인지(POK-466). 한 방송을 치지직 · SOOP에 같이 내보내면 두 채팅이 섞이므로 줄마다 앞에 둔다. 모르면 그리지 않는다 */
function PlatformMark({ label }: { label?: string | null }) {
  return label ? <span className={styles.chatPlatform}>{label}</span> : null;
}

function ChatLine({ message }: { message: ChatPanelMessage }) {
  if (message.kind === 'donation') {
    return (
      <li className={styles.chatDonation}>
        <span className={styles.chatDonationHead}>
          <PlatformMark label={message.platformLabel} />
          <span>
            {message.name} · {message.amountLabel}
          </span>
        </span>
        {/* SOOP 후원은 문구가 늘 비어 온다(POK-431): 빈 자리를 그리면 후원마다 빈 줄이 생긴다 */}
        {message.text ? <span className={styles.chatDonationText}>{message.text}</span> : null}
      </li>
    );
  }
  if (message.kind === 'system') {
    return <li className={styles.chatSystem}>{message.text}</li>;
  }
  return (
    <li className={styles.chatLine}>
      <PlatformMark label={message.platformLabel} />
      <b className={styles.chatName} data-color={message.colorIndex}>
        {message.name}
      </b>{' '}
      <span className={styles.chatText}>{message.text}</span>
    </li>
  );
}

export const ChatPanel = memo(function ChatPanel({
  surges,
  messages,
  ratePerMinute,
  mode,
  replayClock,
  replayWaiting = false,
  statusNote,
  followJump,
  collectionWarning,
  onCollapse,
}: {
  surges: ChatSurge[];
  /** 오래된 → 최신 순. DOM도 이 순서다 — 스크린 리더가 화면과 같은 흐름으로 읽는다 */
  messages: ChatPanelMessage[];
  /** 분당 채팅 수. 모르면 null이고 상태줄에 수를 적지 않는다 */
  ratePerMinute: number | null;
  /** 방송 상태 — 배지 문구가 여기서 갈린다. 안 주면 수집 중 */
  mode?: 'live' | 'ended' | 'offline' | 'unknown';
  /**
   * 채팅이 보여 주는 영상 시점(h:mm:ss): 끝난 방송의 재생 위치, 라이브를 되감은 자리(POK-326). 있으면 바닥이 「그 시점까지 보는
   * 중」이다. 없으면 끝난 방송은 기록 끝까지이거나 아직 어느 시점을 보일지 모르고(replayWaiting), 라이브는 최신 메시지다
   */
  replayClock?: string | null;
  /** 끝난 방송인데 채팅을 보일 시점을 아직 모른다(명부 답 전, 녹화가 자리를 알리기 전). 바닥이 「시점을 확인하는 중」이다 */
  replayWaiting?: boolean;
  /**
   * 채팅을 읽는 중·읽지 못함(목록의 상태 줄과 같은 말). 화면 낭독기에 알린다: 목록 안의 줄은 듣는 사람이 목록을 훑기 전에는
   * 모른다(POK-324 리뷰 1라운드). 눈에는 그 줄이 이미 보이므로 숨겨서 싣는다. 바닥 줄에는 쓰지 않는다
   */
  statusNote?: string | null;
  /**
   * 사람이 영상을 옮긴 횟수(카드·시크바·키(화살표·PageUp·PageDown·Home)·끝으로·다 본 뒤 처음부터). 바뀌면 맨 아래(옮긴 시점)를 따라가는 상태로 돌아간다.
   * 라이브 채팅이 되감은 영상을 따르기 시작하거나 멈춰도 바뀐다(POK-326, 채팅이 통째로 바뀐다).
   * 위로 올려 옛 줄을 읽던 자리가 남으면 옮긴 시점의 줄이 아니라 엉뚱한 줄이 보인다. 다시 세우지 않는다: 목록에 있던 포커스·선택을
   * 잃었다(리뷰 1라운드)
   */
  followJump?: number;
  /** 수집이 끊겼는가 — 동결 계약의 chatWarning을 그대로 받는다 */
  collectionWarning: boolean;
  onCollapse: () => void;
}) {
  // 시안은 column-reverse였지만 그러면 DOM이 시간 역순이 돼 스크린 리더가 대화를 거꾸로 읽는다.
  // DOM은 시간순으로 두고 바닥 고정은 여기서 한다: 끝에 있을 때만 새 줄이 오면 따라 내려가고,
  // 위로 올라가 옛 줄을 읽는 동안엔 자리를 지킨다 — 하단 상태줄의 「따라가는 중」도 이 값이 말한다.
  const listRef = useRef<HTMLUListElement>(null);
  const [following, setFollowing] = useState(true);
  // 옮긴 횟수가 바뀐 그리기에서 바로 따라가기로 돌린다(그린 뒤 효과로 돌리면 옛 자리를 한 번 칠한다)
  const [seenJump, setSeenJump] = useState(followJump);
  if (seenJump !== followJump) {
    setSeenJump(followJump);
    setFollowing(true);
  }

  const handleScroll = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    setFollowing(el.scrollHeight - el.scrollTop - el.clientHeight <= FOLLOW_SLACK_PX);
  }, []);

  // 그리기 전에 붙여야 한 프레임 위에 섰다 내려오는 깜빡임이 없다
  useLayoutEffect(() => {
    const el = listRef.current;
    if (el && following) el.scrollTop = el.scrollHeight;
  }, [messages, following]);

  // 「끝에 있는가」는 스크롤 말고 크기로도 바뀐다 — 창 높이 변화(패널이 100dvh를 탄다), 2단↔1단 전환.
  // 그때 할 일은 방향에 따라 갈린다. 한쪽으로만 하면 반대쪽이 깨진다:
  //   따라가던 중이면 다시 바닥에 붙인다. 브라우저는 컨테이너가 낮아져도 scrollTop을 그대로 두므로
  //   여기서 다시 재면 줄어든 높이만큼 거리가 벌어져, 손 하나 안 댄 사용자가 바닥에서 이탈한다.
  //   따라가지 않던 중이면 다시 잰다. 안 넘치게 된 목록이 「지난 메시지 보는 중」에 갇히면
  //   스크롤할 것이 없어 사용자가 되돌릴 방법도 없다.
  useEffect(() => {
    const el = listRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => {
      if (following) el.scrollTop = el.scrollHeight;
      else handleScroll();
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [following, handleScroll]);

  // 끝난 방송의 분당 수는 통계의 「분당 평균 채팅」과 같은 평균이라 이름도 꼴도 같게 쓴다(formatPerMinute): 채팅 기록 아래에
  // 「분당 0.7」만 쓰면 보이는 줄의 수처럼 읽혔다(리뷰 6라운드). 라이브는 최근 1분의 개수다(되감았으면 영상 시각 앞 1분, POK-326)
  const rateLabel =
    ratePerMinute === null
      ? ''
      : mode === 'ended'
        ? ` · 분당 평균 ${formatPerMinute(ratePerMinute)}`
        : ` · 분당 ${ratePerMinute.toLocaleString('ko-KR')}`;
  // 끝난 방송의 채팅은 새 메시지가 오지 않는 기록이다. 바닥에 붙어 있어도 「최신 메시지를 따라간다」가 아니다(리뷰 5라운드).
  // 영상 시각을 따라가면 그 시점까지다: 5분을 보는데 「마지막 메시지까지」라 하면 방송 끝까지 본 것처럼 읽힌다(POK-324). 시점을
  // 아직 모르면 그렇다고 쓴다: 녹화가 자리를 알리기 전에 바닥만 끝까지 본 것처럼 말했다(리뷰 2라운드). 바닥은 어디까지 보는지만
  // 말하고 읽는 중·실패는 목록의 상태 줄이 말한다: 같은 말을 두 번 썼고, 긴 실패 문구는 바닥을 두 줄로 접어 따라가기를 풀 때마다
  // 목록 높이가 바뀌어 맨 아래로 도로 끌려갔다(리뷰 3라운드). 라이브를 되감았을 때도 그 시점까지다(POK-326): 「최신 메시지」라
  // 하면 영상과 함께 과거를 보는데 지금 채팅을 보는 것처럼 읽힌다
  const followNote = replayClock
    ? `${replayClock} 시점까지 보는 중`
    : mode === 'ended'
      ? replayWaiting
        ? '시점을 확인하는 중'
        : '마지막 메시지까지 보는 중'
      : '최신 메시지 따라가는 중';
  // 끝난 방송의 채팅은 흐르는 것이 아니라 남은 기록이다(QA 2026-10-11)
  const heading = mode === 'ended' ? '채팅 기록' : '실시간 채팅';

  return (
    // 시청자 채팅·후원은 제3자 개인정보라 세션 녹화에서 통째로 뺀다 (POK-358)
    <aside className={`${styles.chatPanel} ${REPLAY_BLOCK_CLASS}`} aria-label={heading}>
      <div className={styles.chatHeader}>
        <MessageSquare size={15} aria-hidden className={styles.chatHeaderIcon} />
        <span className={styles.chatHeading}>{heading}</span>
        {/* 배지를 갈아끼우는 게 아니라 늘 서 있는 리전 안에서 글만 바꾼다 —
            지운 경고 배너가 갖고 있던 role="status"를 여기가 승계한다. 없으면 수집이
            끊기는 순간을 눈으로 보는 사람만 알고, 듣는 사람은 헤더로 돌아와야 안다. */}
        <span role="status">
          {collectionWarning ? (
            <Badge tone="warning" variant="soft" size="sm">
              수집 끊김
            </Badge>
          ) : mode === 'ended' ? (
            <Badge tone="neutral" variant="soft" size="sm">
              지난 채팅
            </Badge>
          ) : mode === 'offline' ? (
            <Badge tone="neutral" variant="soft" size="sm">
              방송 대기
            </Badge>
          ) : mode === 'unknown' ? (
            <Badge tone="neutral" variant="soft" size="sm">
              확인 중
            </Badge>
          ) : (
            <Badge tone="success" variant="soft" size="sm">
              수집 중
            </Badge>
          )}
          {/* 배지 글과 붙어 「지난 채팅채팅을…」으로 읽히지 않게 띄운다 */}
          {statusNote ? <VisuallyHidden>{` ${statusNote}`}</VisuallyHidden> : null}
        </span>
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="채팅 패널 접기"
          className={styles.chatCollapse}
          onClick={onCollapse}
        >
          <PanelRightClose size={14} aria-hidden />
        </IconButton>
      </div>
      <div className={styles.chatSurges}>
        <span className={styles.chatSurgeLabel}>급증</span>
        {surges.map((surge) => (
          <Tag key={surge.keyword} variant="soft" size="sm">
            {surge.keyword} ×{surge.count}
          </Tag>
        ))}
      </div>
      {/* 스크롤 영역은 키보드로도 훑을 수 있어야 한다 (axe scrollable-region-focusable) —
          카드 레일(HighlightCardPanel)과 같은 처리 */}
      <ul
        ref={listRef}
        className={styles.chatList}
        tabIndex={0}
        aria-label="채팅 메시지"
        onScroll={handleScroll}
      >
        {messages.map((message) => (
          <ChatLine key={message.id} message={message} />
        ))}
      </ul>
      {/* 끊겼는데 「따라가는 중」이라 하면 헤더 배지와 한 화면에서 모순된다 — 문구도 같이 바꾼다 */}
      <div className={styles.chatFooter}>
        <span
          className={clsx(styles.chatDot, collectionWarning && styles.chatDotWarning)}
          aria-hidden
        />
        <span>
          {collectionWarning
            ? '수집 끊김 · 새 메시지 없음'
            : `${following ? followNote : '지난 메시지 보는 중'}${rateLabel}`}
        </span>
        {/* 키워드 설정 화면은 라우트가 없다 — 「제목 수정」(StreamInfoBar)처럼 자리만 지킨다 */}
        <button type="button" className={styles.chatFooterLink} disabled>
          키워드 설정
        </button>
      </div>
    </aside>
  );
});
