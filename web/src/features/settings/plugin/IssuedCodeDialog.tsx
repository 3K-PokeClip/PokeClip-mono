'use client';

import { useEffect, useRef, useState } from 'react';
import { Check, Copy, TriangleAlert } from 'lucide-react';
import { Button, Dialog, IconButton, VisuallyHidden } from '@/ui';
import { REPLAY_BLOCK_CLASS } from '@/lib/analytics';
import type { DisplayedPairingCode } from './useStreamKeyState';
import { useCountdown } from './useCountdown';
import styles from './PluginSettingsScreen.module.css';

// 발급 직후 코드 1회 표시 모달 (디자인 1m ③·④, POK-103).
// 코드가 OBS에 들어가면 「연결됐어요」로 바뀐다(POK-608) — 서버의 연결 시각이 바뀐 것을 훅이 지켜본다.
// 닫으면(확인·ESC·백드롭 모두) 원문이 사라지고 다시 볼 수 없다 (ADR-019 일회 노출).
// 열려 있는 동안 10분 카운트다운이 흐르고, 만료되면 ④ 상태로 전환된다 —
// 제목이 바뀌고 코드를 숨긴 채 재발급을 안내한다.
// 복사는 코드 박스 우측의 아이콘 버튼 — 성공 시 체크로 1.5초 바뀌고 "복사됨" 툴팁을 띄운다.
export function IssuedCodeDialog({
  issued,
  paired = false,
  busy,
  onClose,
  onIssueNew,
}: {
  issued: DisplayedPairingCode | null;
  /** 띄워 둔 이 코드가 OBS에 들어갔다(POK-608) — 코드 대신 연결됐다고 알린다. 만료보다 먼저 본다. */
  paired?: boolean;
  busy?: boolean;
  onClose: () => void;
  /** 만료 후 새 코드 발급 — 코드 만료는 키와 무관하므로 rotate 없이 발급 1콜이면 된다. */
  onIssueNew: () => void;
}) {
  // deadline은 클라 시계로 앵커된 값 — 서버 expiresAt 직접 비교의 시계 오차를 피한다 (리뷰 #74)
  const countdown = useCountdown(issued?.deadline ?? null);
  const [copied, setCopied] = useState(false);

  // 새 코드가 오면 복사 표시를 리셋한다 (만료 → 새 코드 발급 경로)
  useEffect(() => {
    setCopied(false);
  }, [issued?.code]);

  // 복사 피드백(체크 아이콘 + 툴팁)은 1.5초만 유지 — 디자인 1m ③ 정본값
  useEffect(() => {
    if (!copied) return;
    const id = window.setTimeout(() => setCopied(false), 1500);
    return () => window.clearTimeout(id);
  }, [copied]);

  // 연결을 본 순간 초점이 있던 「코드 복사」가 사라진다 — FocusScope는 처음 열릴 때만 초점을 잡아서, 옮기지 않으면
  // 초점이 창 밖(body)으로 빠져 Enter·Space가 듣지 않는다(PR #539 2차 리뷰). 남은 하나뿐인 「확인」으로 옮긴다
  const confirmRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (paired) confirmRef.current?.focus();
  }, [paired]);

  if (issued === null) return null;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(issued.code);
      setCopied(true);
    } catch {
      /* 클립보드 권한 거부 — 코드가 화면에 있으므로 손으로 옮기면 된다 */
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      {/* DS 기본 폭(480)보다 좁은 디자인 폭(410)으로 고정 — 발급·만료 두 상태가 같은 폭을 유지한다 */}
      <Dialog.Content className={styles.issueDialog}>
        {paired ? (
          <>
            {/* 연결을 본 순간 코드는 이미 쓰였다 — 원문·카운트다운·만료 경고를 치우고 결과만 말한다.
                만료 직전에 넣어 둘이 겹쳐도 연결이 이긴다(쓰인 코드는 만료될 수 없다) */}
            <Dialog.Title>플러그인이 연결됐어요</Dialog.Title>
            {/* 낭독은 창 맨 아래의 늘 있는 상태 칸이 맡는다 — 이 문단은 바뀐 순간 새로 끼워져 읽힌다는 보장이 없다 */}
            <p className={styles.issueExpired}>
              OBS에서 코드를 확인했어요.
              <br />
              이제 방송을 켜면 포키가 하이라이트를 찾기 시작해요.
            </p>
            <div className={styles.confirmActions}>
              <Button ref={confirmRef} variant="solid" size="md" onClick={onClose}>
                확인
              </Button>
            </div>
          </>
        ) : countdown.expired ? (
          <>
            <Dialog.Title>코드가 만료됐어요</Dialog.Title>
            {/* 만료는 안내가 곧 상태 변화라 낭독돼야 한다 — 카운트다운 자체는 낭독하지 않는다 */}
            <p role="status" className={styles.issueExpired}>
              발급 후 10분이 지나 코드가 만료되었어요.
              <br />새 코드를 발급해 주세요.
            </p>
            <div className={styles.confirmActions}>
              <Button variant="ghost" size="md" onClick={onClose}>
                닫기
              </Button>
              <Button variant="solid" size="md" loading={busy} onClick={onIssueNew}>
                새 코드 발급
              </Button>
            </div>
          </>
        ) : (
          <>
            <Dialog.Title>연동 코드가 발급되었어요</Dialog.Title>
            <Dialog.Description>OBS 플러그인 설정에 아래 코드를 입력하세요.</Dialog.Description>
            <div className={styles.issueCodeBox}>
              {/* 페어링 코드는 세션 녹화에서 통째로 뺀다 — 자릿수도 남기지 않는다 (POK-358) */}
              <span className={`${styles.issueCode} ${REPLAY_BLOCK_CLASS}`}>{issued.code}</span>
              <div className={styles.issueCopyWrap} data-copied={copied || undefined}>
                {copied ? (
                  <span className={styles.issueCopiedTip} aria-hidden>
                    복사됨
                  </span>
                ) : null}
                <IconButton
                  variant="ghost"
                  size="sm"
                  aria-label="코드 복사"
                  onClick={() => void copy()}
                >
                  {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
                </IconButton>
                {/* 시각 피드백(체크·툴팁)과 짝 — 스크린리더에도 복사 성공을 알린다 */}
                <VisuallyHidden role="status">{copied ? '복사됨' : ''}</VisuallyHidden>
              </div>
            </div>
            <p className={styles.issueCountdown} role="timer" aria-label="코드 만료까지 남은 시간">
              {countdown.label} 후 만료돼요
            </p>
            {/* 창을 띄워 둔 동안 연결을 지켜본다(useStreamKeyState의 PAIRING_WATCH_MS) — 넣고 돌아오면 위 「연결됐어요」로 바뀐다 */}
            <p className={styles.issueWatching}>OBS에 코드를 넣으면 이 창이 바로 알려 드려요</p>
            {/* 텍스트는 span 하나로 감싼다 — flex 컨테이너에 텍스트 노드가 직접 놓이면
                <b>와 별개 플렉스 아이템으로 쪼개져 줄이 갈라진다(이전 버그) */}
            <div className={styles.issueWarning}>
              <TriangleAlert aria-hidden className={styles.issueWarningIcon} />
              <span>
                이 코드는 발급 후 <b>10분 동안만</b> 유효해요. 시간이 지나면 자동으로 만료되니, 그
                안에 OBS 플러그인에 입력해 연동을 마쳐 주세요.
              </span>
            </div>
            <Button variant="solid" size="md" className={styles.issueConfirm} onClick={onClose}>
              확인했어요
            </Button>
          </>
        )}
        {/* 창이 떠 있는 내내 있는 상태 칸 — 연결을 본 순간 글이 채워져 낭독된다. 내용과 함께 새로 끼우는 role="status"는
            화면 읽기 프로그램이 읽는다는 보장이 없다(PR #539 2차 리뷰) */}
        <VisuallyHidden role="status">
          {paired ? '플러그인이 연결됐어요. OBS에서 코드를 확인했어요.' : ''}
        </VisuallyHidden>
      </Dialog.Content>
    </Dialog>
  );
}
