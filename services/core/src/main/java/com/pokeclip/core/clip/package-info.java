/**
 * 방송 세션 · 세그먼트 인덱스 · 클립 상태 · 승인 · SQS 렌더 잡 발행.
 *
 * <p>{@code auth}와 한 프로세스에서 돌지만 폴더는 나눠 둔다. 서로의
 * {@code ..internal..}을 직접 부르지 않는다.
 */
package com.pokeclip.core.clip;
