'use client';

import { X } from 'lucide-react';
import { Dialog, IconButton } from '@/ui';
import type { DetectedOs } from '@/lib/detectOs';
import { PluginDownloadPanel } from './PluginDownloadPanel';
import styles from './PluginSettingsScreen.module.css';

// 다운로드 배너 → 「플러그인 다운로드」가 여는 창 (POK-379). 받기 전에 서명 경고·설치 순서를 한 번 보게 한다.
// 확인·저장할 일이 없는 창이라 아래 버튼 대신 제목 줄 오른쪽 X로 닫는다 — 프로필 사진 창과 같은 방식.
// ESC·바깥 클릭으로도 닫힌다.
export function PluginDownloadDialog({
  open,
  onClose,
  detectedOs,
}: {
  open: boolean;
  onClose: () => void;
  detectedOs: DetectedOs;
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Content className={styles.downloadDialog}>
        <div className={styles.downloadHead}>
          <Dialog.Title className={styles.downloadTitle}>플러그인 다운로드</Dialog.Title>
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="닫기"
            className={styles.downloadCloseX}
            onClick={onClose}
          >
            <X aria-hidden />
          </IconButton>
        </div>
        <Dialog.Description>
          설치할 PC의 운영체제를 고르세요. 지금 쓰는 PC가 아니어도 돼요.
        </Dialog.Description>
        <PluginDownloadPanel detectedOs={detectedOs} />
      </Dialog.Content>
    </Dialog>
  );
}
