/**
 * 로그인 · 스트림 키 · 채널 연동 · 유튜브 토큰 · CloudFront 서명 쿠키 발급.
 *
 * <p>{@code clip}과 한 프로세스에서 돌지만 폴더는 나눠 둔다. 서로의
 * {@code ..internal..}을 직접 부르지 않는다 — 나중에 떼어낼 때 이 패키지를
 * 통째로 옮기고 main 클래스 하나만 추가하면 되게 하기 위해서다.
 */
package com.pokeclip.core.auth;
