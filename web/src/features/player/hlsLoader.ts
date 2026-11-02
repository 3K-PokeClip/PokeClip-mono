// 모듈 하나를 재생기끼리 나눠 읽는다(POK-200 M5). 재생 세션은 소스를 갈아 끼울 때마다 재생기를 새로 세우므로, 앞 재생기의 읽기가
// 끝나기 전에 다음 재생기가 또 읽는다 — 겹친 읽기는 한 약속을 나눈다. 읽기가 실패하면(청크 받기 실패 등) 그 실패를 기억하지 않고
// 다음 읽기가 다시 시도한다: 기억하면 한 번 실패로 새로고침 전까지 모든 재생기가 멎는다(cc M4).

/** importer 를 한 번에 하나만 부르는 읽기 함수를 만든다. 실패한 약속은 비운다 */
export function createModuleLoader<T>(importer: () => Promise<T>): () => Promise<T> {
  let pending: Promise<T> | null = null;
  return () => {
    if (pending === null) {
      const mine = importer();
      pending = mine;
      mine.catch(() => {
        if (pending === mine) pending = null;
      });
    }
    return pending;
  };
}

/** hls.js 읽기 — 클라이언트 전용(월드 오브젝트 참조)이라 동적 import 로 번들을 나눈다 */
export const loadHls = createModuleLoader(() => import('hls.js'));
