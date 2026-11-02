/**
 * 읽기를 다시 할지. 서버 · 네트워크 실패만 한 번 더 한다. 4xx는 다시 물어도 같은 답이라 요청만 늘고 실패를 늦게 알린다.
 * ApiError와 ClipApiError 둘 다 status를 갖는다: ClipApiError는 무거운 clipEditor 모듈에 있어 전역 설정에서 가져오지 않는다.
 */
export function retryServerFailure(failureCount: number, error: unknown): boolean {
  if (failureCount >= 1) return false;
  const status = (error as { status?: unknown } | null)?.status;
  return !(typeof status === 'number' && status >= 400 && status < 500);
}
