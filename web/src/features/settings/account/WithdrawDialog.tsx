'use client';

import { useId } from 'react';
import { Button, Dialog } from '@/ui';
import type { WITHDRAW_FACTS } from './useAccountMockState';
import styles from './AccountSettingsScreen.module.css';

// 회원 탈퇴 재확인 (디자인 1p ②, ADR-044) — 파괴적 동작은 모달로 확인받는다.
// 오류는 그리지 않는다. 결과는 토스트나 완료 화면이 맡는다.
//
// UnlinkChzzkDialog가 적어 둔 승격 트리거(두 번째 파괴적 확인)가 여기서 발화하지만
// 아직 @/ui/components/ConfirmDialog로 올리지 않는다: 이 모달은 골격이 갈린다 —
// 소제목으로 나뉜 결과 목록 둘·수치 박스 둘·1:2 폭 버튼이고 확인 버튼도 danger가
// 아니라 solid다. 공용으로 올리면 표본 둘 중 어느 쪽도 아닌 옵션 덩어리가 된다.
// 게다가 UnlinkChzzkDialog는 POK-205가 아직 손에 쥔 파일이라 여기서 고치면 충돌한다.
// 세 번째 사례가 나와 공통 골격이 확실해질 때 올린다.

/** 1p ②의 「보관함 · 클립」 문구 — 지워지는 것과 안 지워지는 것을 갈라 말한다. */
const ARCHIVE_CONSEQUENCES = [
  '탈퇴 즉시 저장된 지난 방송(VOD)과 보관함의 클립·하이라이트 카드·자동 처리 설정이 모두 삭제되며 복구할 수 없습니다.',
  '업로드 채널에 이미 게시된 영상은 삭제되지 않으며, 해당 채널에서 직접 관리해야 합니다.',
];

/** 1p ②의 「구독 · 결제」 문구. */
const BILLING_CONSEQUENCES = [
  '진행 중인 Pro 구독은 탈퇴 시 즉시 종료되며, 남은 기간은 환불되지 않습니다.',
  '미결제 청구 금액이 남아 있는 경우 결제를 완료해야 탈퇴할 수 있습니다.',
  'OBS 플러그인과 연결된 Google 계정 연동이 모두 해제됩니다.',
];

export function WithdrawDialog({
  open,
  name,
  facts,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  name: string;
  facts: typeof WITHDRAW_FACTS;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const bodyId = useId();

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onCancel()}>
      {/* 판단 재료는 각주가 아니라 결과 목록이다 — describedby를 본문으로 돌린다 */}
      <Dialog.Content className={styles.withdrawDialog} aria-describedby={bodyId}>
        <div className={styles.dialogHead}>
          <div className={styles.dialogEyebrow}>회원 탈퇴</div>
          <Dialog.Title className={styles.dialogTitle}>{name}님, 정말 탈퇴하시나요?</Dialog.Title>
          <p className={styles.dialogLead}>회원 탈퇴 전 아래 사항을 숙지해 주세요.</p>
          <div className={styles.dialogDivider} />
        </div>

        <div id={bodyId} className={styles.withdrawBody}>
          <h3 className={styles.groupTitle}>보관함 · 클립</h3>
          <ol className={styles.consequences}>
            {ARCHIVE_CONSEQUENCES.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ol>
          <div className={styles.factBox}>
            <div className={styles.factRow}>
              <span className={styles.factLabel}>저장된 방송</span>
              <span className={styles.factValue}>{facts.savedBroadcasts}개</span>
            </div>
            <div className={styles.factDivider} />
            <div className={styles.factRow}>
              <span className={styles.factLabel}>보관함 클립</span>
              <span className={styles.factValue}>{facts.archivedClips}개</span>
            </div>
          </div>

          <h3 className={styles.groupTitle}>구독 · 결제</h3>
          <ol className={styles.consequences}>
            {BILLING_CONSEQUENCES.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ol>
          <div className={`${styles.factBox} ${styles.factBoxSingle}`}>
            <div className={styles.factRow}>
              <span className={styles.factLabel}>남은 구독 기간</span>
              <span className={styles.factValue}>{facts.remainingDays}일</span>
            </div>
          </div>

          <div className={styles.footnoteDivider} />
          <p className={styles.footnote}>
            탈퇴 진행이 어려우신 경우 고객센터(
            <a className={styles.footnoteLink} href="mailto:3k.pokeclip@gmail.com">
              3k.pokeclip@gmail.com
            </a>
            )로 문의해 주세요.
          </p>
          <p className={styles.footnote}>
            그동안 PokeClip을 이용해 주셔서 감사합니다.
            <br />
            보다 나은 서비스로 다시 뵐 수 있도록 노력하겠습니다.
          </p>
        </div>

        {/* 1p는 취소 1 : 탈퇴 2 폭이고 확인이 solid다 — danger 변형이 아니다 */}
        <div className={styles.dialogActions}>
          <Button variant="outline" size="md" fullWidth onClick={onCancel}>
            취소
          </Button>
          <Button variant="solid" size="md" fullWidth onClick={onConfirm}>
            탈퇴하기
          </Button>
        </div>
      </Dialog.Content>
    </Dialog>
  );
}
