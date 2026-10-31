import { describe, expect, it } from 'vitest';
import { ApiError } from '@/api/client';
import { ClipApiError } from '@/api/clipEditor';
import { retryServerFailure } from './queryRetry';

describe('retryServerFailure', () => {
  it('서버 오류와 네트워크 오류는 한 번 다시 한다', () => {
    expect(retryServerFailure(0, new ApiError(503, '장애'))).toBe(true);
    expect(retryServerFailure(0, new TypeError('Failed to fetch'))).toBe(true);
  });

  it('두 번째 실패에서는 멈춘다', () => {
    expect(retryServerFailure(1, new ApiError(503, '장애'))).toBe(false);
  });

  it('4xx는 다시 하지 않는다: 두 오류 종류 모두', () => {
    expect(retryServerFailure(0, new ApiError(404, '없음'))).toBe(false);
    expect(retryServerFailure(0, new ClipApiError(404, null, null))).toBe(false);
  });

  it('값이 없는 오류도 터지지 않고 다시 한다', () => {
    expect(retryServerFailure(0, null)).toBe(true);
  });
});
