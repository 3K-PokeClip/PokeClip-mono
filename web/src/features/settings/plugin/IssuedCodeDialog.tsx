'use client';

import { useEffect, useState } from 'react';
import { Check, Copy, TriangleAlert } from 'lucide-react';
import { Button, Dialog, IconButton, VisuallyHidden } from '@/ui';
import type { IssuedPairingCode } from '@/api/streamKeys';
import { useCountdown } from './useCountdown';
import styles from './PluginSettingsScreen.module.css';

// 발급 직후 코드 1회 표시 모달 (디자인 1m ③·④, POK-103).
// 닫으면(확인·ESC·백드롭 모두) 원문이 사라지고 다시 볼 수 없다 (ADR-019 일회 노출).
// 열려 있는 동안 10분 카운트다운이 흐르고, 만료되면 ④ 상태로 전환된다 —
// 제목이 바뀌고 코드를 숨긴 채 재발급을 안내한다.
// 복사는 코드 박스 우측의 아이콘 버튼 — 성공 시 체크로 1.5초 바뀌고 "복사됨" 툴팁을 띄운다.
export function IssuedCodeDialog({
  issued,
  busy,
  onClose,
  onIssueNew,
}: {
  issued: IssuedPairingCode | null;
  busy?: boolean;
  onClose: () => void;
  /** 만료 후 새 코드 발급 — 코드 만료는 키와 무관하므로 rotate 없이 발급 1콜이면 된다. */
  onIssueNew: () => void;
}) {
  const countdown = useCountdown(issued?.expiresAt ?? null);
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
        {countdown.expired ? (
          <>
            <Dialog.Title>코드가 만료됐어요</Dialog.Title>
            {/* 만료는 안내가 곧 상태 변화라 낭독돼야 한다 — 카운트다운 자체는 낭독하지 않는다 */}
            <p role="status" className={styles.issueExpired}>
              발급 후 10분이 지나 코드가 만료되었어요.
              <br />
              새 코드를 발급해 주세요.
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
              <span className={styles.issueCode}>{issued.code}</span>
              <div className={styles.issueCopyWrap} data-copied={copied || undefined}>
                {copied ? (
                  <span className={styles.issueCopiedTip} aria-hidden>
                    복사됨
                  </span>
                ) : null}
                <IconButton variant="ghost" size="sm" aria-label="코드 복사" onClick={() => void copy()}>
                  {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
                </IconButton>
                {/* 시각 피드백(체크·툴팁)과 짝 — 스크린리더에도 복사 성공을 알린다 */}
                <VisuallyHidden role="status">{copied ? '복사됨' : ''}</VisuallyHidden>
              </div>
            </div>
            <p className={styles.issueCountdown} role="timer" aria-label="코드 만료까지 남은 시간">
              {countdown.label} 후 만료돼요
            </p>
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
      </Dialog.Content>
    </Dialog>
  );
}
