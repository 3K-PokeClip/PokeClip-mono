/**
 * items를 차례로 돌리되 동시에 limit개까지만 돈다. 방송마다 한 건씩 묻는 읽기가 한꺼번에 몰리지 않게 한다: 방송이 수백 개인 계정은
 * 화면에 들어올 때마다 그만큼의 요청이 동시에 나갔다. work가 던지면 남은 항목은 시작하지 않고, 이미 돌고 있는 것이 끝난 뒤에 그 오류를
 * 던진다: 먼저 던지면 기다린 쪽(편집기 소리 풀기의 차례)이 자리를 넘긴 뒤에도 풀던 덩어리가 돌아 옛 · 새 작업이 겹쳤다.
 */
export async function runLimited<T>(
  items: readonly T[],
  limit: number,
  work: (item: T) => Promise<void>,
): Promise<void> {
  const queue = items.values();
  let failed = false;
  const worker = async () => {
    for (const item of queue) {
      if (failed) return;
      try {
        await work(item);
      } catch (error) {
        failed = true;
        throw error;
      }
    }
  };
  const outcomes = await Promise.allSettled(
    Array.from({ length: Math.min(limit, items.length) }, worker),
  );
  const failure = outcomes.find(
    (outcome): outcome is PromiseRejectedResult => outcome.status === 'rejected',
  );
  if (failure !== undefined) throw failure.reason;
}
