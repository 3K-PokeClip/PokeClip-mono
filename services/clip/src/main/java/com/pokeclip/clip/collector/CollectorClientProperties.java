package com.pokeclip.clip.collector;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 수집기(chat-collector)의 내부 창구 주소와 시한. 토큰은 여기 없다 —
 * {@code pokeclip.internal-api.token}을 그대로 쓴다(서버 간 토큰은 하나다).
 *
 * <p>🔴 <b>주소가 비어도 부팅은 산다 — {@code auth-client}와 다르다.</b> 그쪽은 자격 판정이
 * 없으면 사람 문 전부가 무방비라 {@code validate()}가 부팅을 죽인다. 여기는 수집기가 없으면
 * <b>채팅 문 셋만</b> 503이고 카드 편집·목록·통로는 그대로 돈다 — 그것들을 같이 죽이는 것이
 * 더 나쁜 실패다. 그래서 {@link CollectorClient#enabled()}가 「꺼짐」을 상태로 들고 있다.
 *
 * <p><b>시한을 {@code spring.http.clients.*}(2s+5s)와 따로 두는 이유</b>: 채팅 문은 그 앞에
 * auth 자격 판정을 태우고, 그 왕복만 최악 7초다. 전역 값을 그대로 얹으면 사람이 최악 14초를
 * 기다리고 톰캣 스레드를 그만큼 쥔다. 창구 셋은 전부 읽기 조회라 짧게 잡아도 잃는 것이 없다.
 */
@ConfigurationProperties(prefix = "pokeclip.collector-client")
public record CollectorClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
