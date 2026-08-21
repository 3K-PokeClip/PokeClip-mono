'use client';

import { Button, Dialog } from '@/ui';
import styles from './ChannelSettingsScreen.module.css';

// 치지직 연동 해제 확인 (ADR-044) — 삭제·연동 해제 같은 파괴적 동작은 모달로 확인받고
// 토스트는 결과만 알린다. 그래서 이 모달은 오류를 그리지 않는다.
//
// 화면 로컬에 둔다. 공용 ConfirmDialog로 올리기엔 표본이 이것 하나뿐이라 두 번째
// 사용처가 요구할 것들(이름 입력 확인·체크박스 동의·위험도 단계)을 지금은 모른다.
// 선례도 로컬이다(IssuedCodeDialog). **승격 트리거:** 두 번째 파괴적 확인이 생기면
// 아래 골격(제목·설명·결과 목록·2버튼 푸터)을 @/ui/components/ConfirmDialog로 올린다 —
// 그때 도려낼 수 있게 골격과 치지직 전용 문구를 섞지 않았다.

/** 시안 1k의 재확인 문구 3줄. 무엇이 멈추고 무엇이 남는지를 갈라 말한다. */
const CONSEQUENCES = [
  '하이라이트 감지가 중단돼요',
  '지난 방송과 보관함의 클립은 그대로 남아요',
  '진행 중인 작업은 즉시 중단돼요',
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
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        // 요청이 나간 뒤에 모달만 사라지면 사용자는 해제가 취소된 줄 안다. 결과가
        // 돌아올 때까지(onSettled에서 닫을 때까지) 닫기를 무시한다.
        if (!next && !busy) onCancel();
      }}
    >
      <Dialog.Content className={styles.unlinkDialog}>
        <Dialog.Title>치지직 연동을 해제할까요?</Dialog.Title>
        <Dialog.Description>해제하면 다음이 함께 바뀌어요.</Dialog.Description>
        <ul className={styles.unlinkConsequences}>
          {CONSEQUENCES.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
        <div className={styles.confirmActions}>
          <Button variant="ghost" size="md" onClick={onCancel} disabled={busy}>
            취소
          </Button>
          <Button variant="danger" size="md" loading={busy} onClick={onConfirm}>
            연동 해제
          </Button>
        </div>
      </Dialog.Content>
    </Dialog>
  );
}
