/**
 * 탭이 보이는 동안만 도는 주기(setInterval 대신). 숨으면 멈추고, 다시 보이면 한 주기가 지났을 때만 바로 한 번 돌리고 이어 간다:
 * 탭을 잠깐 오갈 때마다 요청이 나가지 않게.
 *
 * react-query의 refetchInterval은 숨은 탭에서 스스로 멈춘다. 그 밖의 직접 도는 주기가 쓴다. 브라우저의 타이머 제한은 주기를 늦출
 * 뿐 멈추지 않아서, 켜 둔 탭이 하루 종일 서버를 두드렸다.
 *
 * @returns 멈추는 함수
 */
export function setVisibleInterval(run: () => void, ms: number): () => void {
  let timer: number | null = null;
  let lastRun = Date.now();

  const tick = () => {
    lastRun = Date.now();
    run();
  };
  const start = () => {
    timer ??= window.setInterval(tick, ms);
  };
  const pause = () => {
    if (timer !== null) window.clearInterval(timer);
    timer = null;
  };
  const onVisibility = () => {
    if (document.visibilityState !== 'visible') {
      pause();
      return;
    }
    if (timer !== null) return;
    if (Date.now() - lastRun >= ms) tick();
    start();
  };

  if (document.visibilityState === 'visible') start();
  document.addEventListener('visibilitychange', onVisibility);
  return () => {
    pause();
    document.removeEventListener('visibilitychange', onVisibility);
  };
}
