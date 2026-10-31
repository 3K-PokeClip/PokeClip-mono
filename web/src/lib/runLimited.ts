/**
 * items를 차례로 돌리되 동시에 limit개까지만 돈다. 방송마다 한 건씩 묻는 읽기가 한꺼번에 몰리지 않게 한다: 방송이 수백 개인 계정은
 * 화면에 들어올 때마다 그만큼의 요청이 동시에 나갔다. work가 던지면 그대로 던지고 남은 항목은 시작하지 않는다.
 */
export async function runLimited<T>(
  items: readonly T[],
  limit: number,
  work: (item: T) => Promise<void>,
): Promise<void> {
  const queue = items.values();
  const worker = async () => {
    for (const item of queue) await work(item);
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
}
