package com.pokeclip.chat.collector.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 채팅 시각에서 빼는 보정값(ms). 채널마다 덮어쓸 수 있고 <b>음수를 허용한다</b>.
 *
 * <p>보정값은 한 항이 아니라 합이다 — (방송 지연 + 시청자 반응 지연 + 전달 지연) −
 * 우리 인제스트 지연. 여기에 <b>치지직 시계와 우리 시계의 차이까지 섞여 있고</b> 그 차이는
 * 고정이 아니다(실측 전력에 +175ms와 −39ms가 둘 다 있다). 그래서 기계나 환경이 바뀌면
 * 다시 재야 하고, 부호를 미리 못 박을 수 없어 음수를 허용한다.
 *
 * <p><b>{@code channel-offset-ms:} 줄을 yml에 쓰지 마라.</b> 빈 맵 {@code {}}을 적으면
 * Spring의 {@code YamlProcessor.buildFlattenedMap}이 그것을 <b>빈 문자열로 평탄화</b>하고,
 * Binder가 {@code String → Map} 변환기를 못 찾아 바인딩이 실패한다. 이 record는
 * {@code @ConfigurationPropertiesScan}이 <b>모든 컨텍스트</b>에 올리므로 검사 전부와 운영
 * 부팅이 통째로 죽는다(계획 검증에서 재현). 줄을 안 쓰면 아래 compact 생성자가
 * {@code null}을 빈 맵으로 받는다.
 */
@ConfigurationProperties(prefix = "pokeclip.sync")
public record SyncProperties(long defaultOffsetMs, Map<String, Long> channelOffsetMs) {

    public SyncProperties {
        channelOffsetMs = channelOffsetMs == null ? Map.of() : Map.copyOf(channelOffsetMs);
    }

    /** 채널을 모르면(옛 경로처럼 {@code null}이면) 기본값이다 — 보정을 건너뛰지 않는다. */
    public long offsetFor(String channelId) {
        if (channelId == null) {
            return defaultOffsetMs;
        }
        return channelOffsetMs.getOrDefault(channelId, defaultOffsetMs);
    }
}
