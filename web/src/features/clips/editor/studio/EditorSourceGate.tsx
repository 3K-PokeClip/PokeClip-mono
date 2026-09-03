'use client';

import { AlertTriangle } from 'lucide-react';
import { Button, EmptyState, Spinner, Stack } from '@/ui';
import styles from './StudioScreen.module.css';
import type { EditorSourceState } from '../useEditorSource';

// 편집기가 로컬 소스를 기다리거나 못 읽었을 때의 착지점 (POK-238).
//
// 못 읽었다고 편집기를 못 열게 하지 않는다 — 화면 작업은 미디어 없이도 이어져야 하고,
// 「목업으로 열기」가 그 문이다. 소스 없이 여는 것이 원래 이 화면의 기본 동작이기도 하다.

export function EditorSourceGate({
  state,
  onSkip,
}: {
  state: Extract<EditorSourceState, { status: 'loading' } | { status: 'error' }>;
  onSkip: () => void;
}) {
  if (state.status === 'loading') {
    return (
      <div className={styles.gate}>
        <Spinner size="lg" label="편집기 소스를 불러오는 중" />
      </div>
    );
  }

  return (
    <div className={styles.gate}>
      <Stack gap={4} align="center">
        <EmptyState
          icon={<AlertTriangle aria-hidden />}
          title="소스를 불러오지 못했어요"
          // 원인 문구를 그대로 보여준다 — 로컬 개발용 화면이라 어디가 틀렸는지가 곧 해결책이다
          description={state.message}
        />
        <Stack direction="row" gap={2}>
          <Button variant="solid" onClick={state.retry}>
            다시 시도
          </Button>
          <Button variant="ghost" onClick={onSkip}>
            목업으로 열기
          </Button>
        </Stack>
      </Stack>
    </div>
  );
}
