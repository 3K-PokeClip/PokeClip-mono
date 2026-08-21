'use client';

import { useId } from 'react';
import { Button, Dialog } from '@/ui';
import styles from './ChannelSettingsScreen.module.css';

// 치지직 연동 해제 확인 (디자인 1k ②, ADR-044) — 삭제·연동 해제 같은 파괴적 동작은 모달로
// 확인받고 토스트는 결과만 알린다. 그래서 이 모달은 오류를 그리지 않는다.
//
// 화면 로컬에 둔다. 공용 ConfirmDialog로 올리기엔 표본이 이것 하나뿐이라 두 번째
// 사용처가 요구할 것들(이름 입력 확인·체크박스 동의·위험도 단계)을 지금은 모른다.
// 선례도 로컬이다(IssuedCodeDialog). **승격 트리거:** 두 번째 파괴적 확인이 생기면
// 아래 골격(eyebrow·제목·구분선·결과 목록·각주·반반 폭 2버튼)을 @/ui/components/ConfirmDialog로
// 올린다 — 1k의 유튜브 계정 해제 모달(③)이 같은 골격에 문구만 다르다.

/** 1k ②의 재확인 문구 3줄. 무엇이 멈추고 무엇이 남는지를 갈라 말한다. */
const CONSEQUENCES = [
  '해제하면 방송을 켜도 하이라이트를 감지하지 않아요.',
  '이미 저장된 지난 방송과 보관함 클립은 그대로 남아요.',
  '진행 중인 감지·클립 작업은 즉시 중단됩니다.',
];

export function UnlinkChzzkDialog({
  open,
  busy,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  /** 해제 요청 중 — 확인 버튼이 잠기고 Esc·백드롭으로도 닫히지 않는다. */
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const consequencesId = useId();

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        // 요청이 나간 뒤에 모달만 사라지면 사용자는 해제가 취소된 줄 안다. 결과가
        // 돌아올 때까지(onSettled에서 닫을 때까지) 닫기를 무시한다.
        if (!next && !busy) onCancel();
      }}
    >
      {/* 설명은 각주가 아니라 결과 목록이 맡는다 — 사용자가 판단할 재료가 그쪽이다.
          Dialog.Content의 aria-describedby는 rest가 뒤에 퍼져 여기서 덮인다. */}
      <Dialog.Content className={styles.unlinkDialog} aria-describedby={consequencesId}>
        <div className={styles.unlinkHead}>
          <div className={styles.unlinkEyebrow}>치지직 연동 해제</div>
          <Dialog.Title className={styles.unlinkTitle}>치지직 연동을 해제할까요?</Dialog.Title>
          <div className={styles.unlinkDivider} />
        </div>
        <div className={styles.unlinkBody}>
          <ol id={consequencesId} className={styles.unlinkConsequences}>
            {CONSEQUENCES.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ol>
          <p className={styles.unlinkFootnote}>해제 후에도 언제든 다시 연동할 수 있어요.</p>
        </div>
        <div className={styles.confirmActions}>
          <Button variant="outline" size="md" fullWidth onClick={onCancel} disabled={busy}>
            취소
          </Button>
          <Button variant="danger" size="md" fullWidth loading={busy} onClick={onConfirm}>
            연동 해제
          </Button>
        </div>
      </Dialog.Content>
    </Dialog>
  );
}
