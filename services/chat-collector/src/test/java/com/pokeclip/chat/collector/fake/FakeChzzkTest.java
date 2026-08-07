package com.pokeclip.chat.collector.fake;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 가짜 치지직 서버를 띄우는 통합 테스트의 공통 설정.
 *
 * <p>셋을 묶는다 — 랜덤 포트(서블릿 컨테이너가 있어야 WS 핸드셰이크가 된다),
 * 프로파일 test(<b>없으면 @NotBlank가 빈 토큰에 걸려 컨텍스트가 통째로 죽는다</b>),
 * 그리고 @Import(<b>@TestConfiguration은 컴포넌트 스캔에서 제외된다</b>).
 *
 * <p>클래스마다 손으로 쌓지 않는 이유는 하나라도 빠지면 원인이 안 보이는
 * 부팅 실패가 나기 때문이다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FakeChzzkServer.class)
public @interface FakeChzzkTest { }
