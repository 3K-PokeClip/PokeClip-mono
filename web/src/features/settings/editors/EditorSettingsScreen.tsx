'use client';

import { Info, UserPlus } from 'lucide-react';
import { Button, Skeleton } from '@/ui';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { EditorRow } from './EditorRow';
import { InviteEditorDialog } from './InviteEditorDialog';
import { PendingInvitationRow } from './PendingInvitationRow';
import { RevokeEditorDialog } from './RevokeEditorDialog';
import { useEditorSettingsState, type EditorSettingsViewState } from './useEditorSettingsState';
import styles from './EditorSettingsScreen.module.css';

// 디자인 1l 설정 · 편집자 관리 (POK-208). 조립만 한다 — 상태 판단은
// useEditorSettingsState, 행 표현은 EditorRow·PendingInvitationRow가 갖는다.
//
// 시안과 어긋나게 그리는 것들(전부 티켓이 정본으로 지정): 정원 배지 「2 / 3」·정원 초과
// 사전 모달은 정원 API(POK-207)가 없어 아직 없다 — 상한 도달은 초대 모달의 409 문구가
// 알린다. 권한 등급 드롭다운·비교 팝오버·「권한 2단계 비교」 링크는 등급 결정 대기라
// 넣지 않는다.
export function EditorSettingsScreen() {
  const state = useEditorSettingsState();

  return (
    <div className={styles.screen}>
      <div className={styles.headerRow}>
        {/* 1l의 상단은 제목 한 줄 + 우측 초대 버튼이다 */}
        <SettingsPageHeader title="편집자 관리" />
        <Button
          className={styles.headerAction}
          variant="solid"
          size="sm"
          onClick={state.openInvite}
        >
          편집자 초대
        </Button>
      </div>

      <div className={styles.rows}>{listOf(state)}</div>

      {state.view === 'ready' && state.editors.length > 0 && (
        <p className={styles.note}>
          <Info aria-hidden="true" />
          권한 회수는 즉시 적용되고, 대기 중이던 승인 요청은 무효가 됩니다
        </p>
      )}

      <InviteEditorDialog
        open={state.inviteOpen}
        busy={state.inviting}
        error={state.inviteError}
        onCancel={state.closeInvite}
        onSubmit={state.invite}
      />
      <RevokeEditorDialog
        target={state.revokeTarget}
        busy={state.revoking}
        onCancel={state.closeRevoke}
        onConfirm={state.confirmRevoke}
      />
    </div>
  );
}

function listOf(state: EditorSettingsViewState) {
  switch (state.view) {
    case 'loading':
      // 행 높이를 유지해 로드 후 레이아웃이 튀지 않게 한다 (ChzzkChannelRow 선례).
      return (
        <>
          <Skeleton height="calc(70 * var(--pc-u))" radius="lg" />
          <Skeleton height="calc(70 * var(--pc-u))" radius="lg" />
        </>
      );
    case 'unavailable':
      // 행 개수를 모르는 상태라 행 단위 폴백이 성립하지 않는다 — 목록 자리 한 덩어리로.
      return (
        <div className={styles.fallbackCard}>
          <span className={styles.fallbackText}>편집자 목록을 불러오지 못했어요</span>
          <Button variant="soft" size="sm" loading={state.retrying} onClick={state.retry}>
            다시 시도
          </Button>
        </div>
      );
    default:
      if (state.empty) {
        return (
          <div className={styles.emptyCard}>
            <span className={styles.emptyIcon} aria-hidden="true">
              <UserPlus size={20} />
            </span>
            <span className={styles.emptyTitle}>아직 편집자가 없어요</span>
            <span className={styles.emptyBody}>
              편집자를 초대하면 하이라이트 검토와 클립 편집을 맡길 수 있어요. 업로드는
              기본적으로 내 승인을 거칩니다.
            </span>
            <Button variant="solid" size="sm" onClick={state.openInvite}>
              편집자 초대
            </Button>
          </div>
        );
      }
      return (
        <>
          {state.editors.map((editor) => (
            <EditorRow key={editor.id} editor={editor} onRevoke={state.openRevoke} />
          ))}
          {/* 시안 1l대로 대기 초대는 편집자 목록과 같은 스택의 마지막이다 — 별도 섹션이 아니다 */}
          {state.pendingInvitations.map((invitation) => (
            <PendingInvitationRow
              key={invitation.id}
              invitation={invitation}
              canceling={state.cancelingId === invitation.id}
              onCancel={state.cancelInvitation}
            />
          ))}
        </>
      );
  }
}
