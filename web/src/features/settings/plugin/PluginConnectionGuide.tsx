import { Plug } from 'lucide-react';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 상단 연결 상태 배너 자리 — 상태 대신 확인할 곳을 안내한다.
// 플러그인은 대기 중 서버에 신호를 보내지 않고, 웹은 루프백 독에 닿을 수 없어 웹이 아는 연결 상태가 없다.
// 「연결됨」「연결 안 됨」 같은 배지를 붙이면 누군가에게는 거짓이 된다(ADR-077 결정 4) — 그래서 배지도,
// 「연결 진단」 버튼도 두지 않는다. 코드 입력 결과는 OBS PokeClip 독이 바로 보여 준다.
export function PluginConnectionGuide() {
  return (
    <section className={styles.guideBanner} aria-labelledby="plugin-connection-guide-title">
      <div className={styles.guideIcon}>
        <Plug size={20} strokeWidth={1.9} aria-hidden />
      </div>
      <div className={styles.guideBody}>
        <h2 className={styles.guideTitle} id="plugin-connection-guide-title">
          연결 상태는 OBS에서 확인해 주세요
        </h2>
        <p className={styles.guideDesc}>
          OBS 메뉴 「도킹 › PokeClip」에서 연결·전송 상태와 오류 안내를 볼 수 있어요
        </p>
      </div>
    </section>
  );
}
