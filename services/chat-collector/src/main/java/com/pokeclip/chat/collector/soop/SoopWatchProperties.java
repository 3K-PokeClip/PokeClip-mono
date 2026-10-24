package com.pokeclip.chat.collector.soop;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SOOP 방송 지켜보기 설정(POK-397). 검증은 {@link #validate()}로 둔다({@code @ConfigurationPropertiesScan}이 모든 컨텍스트에 올리므로,
 * 애노테이션으로 걸면 지켜보기를 안 켠 부팅까지 죽는다. {@code RelayProperties}와 같은 이유).
 *
 * @param watchEnabled     켜면 지켜본다. 기본 꺼짐: 🔴 PR3b(웹이 플랫폼별로 읽기) 배포 뒤에 켠다. 먼저 켜면 플랫폼을 모르는 화면의 숫자·그래프가
 *                         두 플랫폼 사이를 매분 오간다(계획 검증 L8)
 * @param diagnoseResponse 켜면 방송 여부 응답의 키 이름과 status 값만 INFO로 남긴다(실방송 진단, 값은 안 찍는다)
 * @param apiBaseUri       SOOP 오픈 API 주소. 시험은 가짜 서버로 바꾼다
 * @param maxWatched       동시에 지켜보는 방송 상한. 한 틱 시간이 이것에 비례한다
 */
@ConfigurationProperties(prefix = "pokeclip.soop")
public record SoopWatchProperties(boolean watchEnabled, boolean diagnoseResponse, String apiBaseUri, Duration interval,
                                  Duration initialDelay, int maxWatched) {

    /** @throws IllegalStateException 주소가 비었거나 상한이 1보다 작으면 */
    public void validate() {
        if (apiBaseUri == null || apiBaseUri.isBlank()) {
            throw new IllegalStateException("pokeclip.soop.api-base-uri이(가) 비어 있다.");
        }
        if (maxWatched < 1) {
            throw new IllegalStateException("pokeclip.soop.max-watched는 1 이상이어야 한다.");
        }
    }
}
