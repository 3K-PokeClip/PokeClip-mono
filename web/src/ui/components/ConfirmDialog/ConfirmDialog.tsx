import type { ReactNode } from 'react';
import { useId } from '../../primitives/hooks/useId';
import { Button } from '../Button/Button';
import { Dialog } from '../Dialog/Dialog';
import styles from './ConfirmDialog.module.css';

// 파괴적 동작 확인 모달 (디자인 1k ②, ADR-044) — 삭제·연동 해제·권한 회수 같은 파괴적
// 동작은 모달로 확인받고 토스트는 결과만 알린다. 그래서 이 모달은 오류를 그리지 않는다.
//
// 원래 화면 로컬(UnlinkChzzkDialog)이던 골격을 두 번째 파괴적 확인(편집자 내보내기,
// POK-208)이 생기면서 승격했다. 두 사용처의 차이는 문자열과, 제목 아래 선택 슬롯 하나다 —
// 1l 내보내기 모달이 확인 대상(아바타·이름) 카드를 제목과 구분선 사이에 끼운다.
// 이름 입력 확인·체크박스 동의 같은 더 큰 구조 차이가 필요해지면 그때 넓힌다.

export interface ConfirmDialogProps {
  open: boolean;
  /** 요청 진행 중 — 확인 버튼이 잠기고 Esc·백드롭으로도 닫히지 않는다. */
  busy: boolean;
  /** 제목 위 카테고리 라벨. 예: 「치지직 연동 해제」 */
  eyebrow: string;
  /** 「~할까요?」 형 질문. */
  title: string;
  /** 제목과 구분선 사이의 확인 대상 요약(1l 내보내기 모달의 대상 카드 자리). 스타일은 호출부가 갖는다. */
  subject?: ReactNode;
  /** 무엇이 멈추고 무엇이 남는지 — 사용자가 판단할 재료. 번호 목록으로 그린다. */
  consequences: readonly string[];
  /** 목록 아래 각주. 예: 「해제 후에도 언제든 다시 연동할 수 있어요.」 */
  footnote?: string;
  cancelLabel?: string;
  /** danger 버튼 라벨. 동작을 그대로 쓴다 — 「연동 해제」·「권한 회수」. */
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: () => void;
}

export function ConfirmDialog({
  open,
  busy,
  eyebrow,
  title,
  subject,
  consequences,
  footnote,
  cancelLabel = '취소',
  confirmLabel,
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  const consequencesId = useId();

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        // 요청이 나간 뒤에 모달만 사라지면 사용자는 동작이 취소된 줄 안다. 결과가
        // 돌아올 때까지(onSettled에서 닫을 때까지) 닫기를 무시한다.
        if (!next && !busy) onCancel();
      }}
    >
      {/* 설명은 각주가 아니라 결과 목록이 맡는다 — 사용자가 판단할 재료가 그쪽이다.
          Dialog.Content의 aria-describedby는 rest가 뒤에 퍼져 여기서 덮인다. */}
      <Dialog.Content className={styles.dialog} aria-describedby={consequencesId}>
        <div className={styles.head}>
          <div className={styles.eyebrow}>{eyebrow}</div>
          <Dialog.Title className={styles.title}>{title}</Dialog.Title>
          {subject !== undefined && <div className={styles.subject}>{subject}</div>}
          <div className={styles.divider} />
        </div>
        <div className={styles.body}>
          <ol id={consequencesId} className={styles.consequences}>
            {consequences.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ol>
          {footnote ? <p className={styles.footnote}>{footnote}</p> : null}
        </div>
        <div className={styles.actions}>
          <Button variant="outline" size="md" fullWidth onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button variant="danger" size="md" fullWidth loading={busy} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </Dialog.Content>
    </Dialog>
  );
}
