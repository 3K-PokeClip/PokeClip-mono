import { Plug } from 'lucide-react';
import { Badge, Button } from '@/ui';
import type { PluginConnection } from './usePluginMockState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 상단 연결 상태 배너.
// 플러그인 상태 API가 아직 없어 표시값은 전부 목업이다 (usePluginMockState 참고).
export function PluginStatusBanner({ connection }: { connection: PluginConnection }) {
  const { connected, version, device, obsVersion, lastSignal, latency } = connection;

  return (
    <section
      className={styles.statusBanner}
      data-connected={connected}
      aria-label="플러그인 연결 상태"
    >
      <div className={styles.statusIcon}>
        <Plug size={20} strokeWidth={1.9} aria-hidden />
      </div>
      <div className={styles.statusBody}>
        <div className={styles.statusTitleRow}>
          <span className={styles.statusName}>PokeClip for OBS</span>
          {connected ? (
            <>
              <Badge tone="success" variant="soft" size="sm">
                연결됨
              </Badge>
              <Badge tone="neutral" variant="soft" size="sm">
                {version}
              </Badge>
            </>
          ) : (
            <Badge tone="neutral" variant="soft" size="sm">
              연결 안 됨
            </Badge>
          )}
        </div>
        <div className={styles.statusMeta}>
          {connected
            ? `${device} · OBS ${obsVersion} · 마지막 신호 ${lastSignal} · 지연 ${latency}`
            : '연결 코드를 발급해 OBS 플러그인에 입력하면 여기에 상태가 표시돼요'}
        </div>
      </div>
      <Button variant="ghost" size="sm">
        연결 진단
      </Button>
    </section>
  );
}
