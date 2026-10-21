'use client';

import { Button, Dialog } from '@/ui';
import type { DetectedOs } from '@/lib/detectOs';
import type { PluginRelease } from '@/lib/pluginRelease/types';
import { PluginDownloadPanel } from './PluginDownloadPanel';
import styles from './PluginSettingsScreen.module.css';

// 다운로드 배너 → 「플러그인 다운로드」가 여는 창 (POK-379). 받기 전에 서명 경고·설치 순서를 한 번 보게 한다.
export function PluginDownloadDialog({
  open,
  onClose,
  release,
  detectedOs,
}: {
  open: boolean;
  onClose: () => void;
  release: PluginRelease;
  detectedOs: DetectedOs;
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Content className={styles.downloadDialog}>
        <Dialog.Title>플러그인 다운로드</Dialog.Title>
        <Dialog.Description>
          설치할 PC의 운영체제를 고르세요. 지금 쓰는 PC가 아니어도 돼요.
        </Dialog.Description>
        <PluginDownloadPanel release={release} detectedOs={detectedOs} />
        <div className={styles.confirmActions}>
          <Dialog.Close>
            <Button variant="ghost" size="md">
              닫기
            </Button>
          </Dialog.Close>
        </div>
      </Dialog.Content>
    </Dialog>
  );
}
