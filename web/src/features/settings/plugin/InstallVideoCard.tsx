import styles from './PluginSettingsScreen.module.css';

// 설정 › 플러그인 아래의 「설치 과정 영상」(POK-615). 다운로드 → OBS 끄고 설치 → OBS 다시 켜기 → 코드 받기 →
// PokeClip 창에 코드 넣기 → 연결 확인까지 46초. 글로 된 순서(ConnectStepsCard)를 실제 화면으로 한 번 보여 준다.
//
// 파일은 web/public/guide/ 에 있다(고정 주소 — 서버를 거치지 않는다). 누르기 전에는 영상을 받지 않는다
// (preload="none" — 3MB를 화면에 들어온 모든 회원이 받을 까닭이 없다). 그동안은 표지 이미지가 자리를 채운다.
// 자막은 영상에 박혀 있다(소리를 끄고 봐도 읽힌다) — 따로 켜고 끄는 자막 트랙은 없다.
//
// 영상 속 화면은 2026-11-20의 실제 화면이다. 연결 순서 카드나 받기 창, 독의 연결 카드가 바뀌면 다시 찍는다.
export const INSTALL_VIDEO = {
  src: '/guide/plugin-install.mp4',
  poster: '/guide/plugin-install-poster.webp',
} as const;

export function InstallVideoCard() {
  return (
    <section className={styles.card} aria-labelledby="install-video-title">
      <h2 id="install-video-title" className={styles.cardTitle}>
        설치 과정 영상
      </h2>
      <p id="install-video-desc" className={styles.codeCardDesc}>
        다운로드부터 연결까지 1분이면 볼 수 있어요
      </p>
      {/* controls만 둔다 — 자동 재생하지 않는다(소리가 있는 영상이다) */}
      <video
        className={styles.installVideo}
        controls
        playsInline
        preload="none"
        poster={INSTALL_VIDEO.poster}
        aria-labelledby="install-video-title"
        aria-describedby="install-video-desc"
      >
        <source src={INSTALL_VIDEO.src} type="video/mp4" />
      </video>
    </section>
  );
}
