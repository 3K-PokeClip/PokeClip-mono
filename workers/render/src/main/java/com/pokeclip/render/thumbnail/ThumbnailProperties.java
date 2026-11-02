package com.pokeclip.render.thumbnail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 사진 줄(POK-277) 설정. 렌더 줄과 따로 읽는다: 렌더는 한 번에 한 주문이고 15분까지 걸려 그 뒤에 서면 라이브 사진이 멈춘다.
 * AWS 주소·clip 주소·내부 열쇠·ffmpeg는 렌더 설정({@code pokeclip.render.*})을 같이 쓴다.
 *
 * @param queueUrl          사진 주문줄 주소. 비면 이 줄을 안 본다(렌더만 도는 일꾼)
 * @param timeout           주문 하나의 상한(받기·뽑기·올리기·보고). 넘기면 메시지를 두고 다음 수신에 다시 한다
 * @param visibilityTimeout 줄의 숨김 시간(큐 설정과 같아야 한다)
 */
@ConfigurationProperties(prefix = "pokeclip.thumbnail")
public record ThumbnailProperties(String queueUrl, Duration timeout, Duration visibilityTimeout) {
}
