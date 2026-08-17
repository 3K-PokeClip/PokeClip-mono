import { Download } from 'lucide-react';
import { Button } from '@/ui';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 하단 배너 — 설치 파일 배포는 추후 티켓, 버튼은 자리만
export function DownloadBanner() {
  return (
    <div className={styles.downloadBanner}>
      <Download size={16} aria-hidden className={styles.downloadIcon} />
      <span className={styles.downloadText}>다른 PC에 설치하나요? 설치 파일과 연동 가이드를 받아보세요.</span>
      <Button variant="soft" size="sm" disabled>
        플러그인 다운로드
      </Button>
    </div>
  );
}
