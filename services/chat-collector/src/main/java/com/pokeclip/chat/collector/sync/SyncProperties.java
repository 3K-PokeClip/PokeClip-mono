package com.pokeclip.chat.collector.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 채팅 시각에서 빼는 보정값(ms). 채널마다 덮어쓸 수 있고 <b>음수를 허용하되 크기에 상한이
 * 있다</b>({@link #MAX_ABS_OFFSET_MS}).
 *
 * <p>보정값은 한 항이 아니라 합이다 — (방송 지연 + 시청자 반응 지연 + 전달 지연) −
 * 우리 인제스트 지연. 여기에 <b>치지직 시계와 우리 시계의 차이까지 섞여 있고</b> 그 차이는
 * 고정이 아니다(실측 전력에 +175ms와 −39ms가 둘 다 있다). 그래서 기계나 환경이 바뀌면
 * 다시 재야 하고, 부호를 미리 못 박을 수 없어 음수를 허용한다.
 *
 * <p><b>🔴 이 값은 채널뿐 아니라 「세션과 시점」에 따라서도 변한다</b>(2026-08-24 실측).
 * 카드는 「채널마다 다르다」를 전제했는데 <b>같은 채널·같은 방송 안에서도</b> 움직였다 —
 * 송출을 재시작한 직후 5초 넘게 튀었다가 8분 뒤 정상으로 돌아왔고, 매 회차의 처음 한두
 * 번은 리듬을 탄 뒤보다 1초쯤 느렸다(시청자가 다음 사건을 예상하는가에 따라 반응 지연이
 * 그만큼 갈린다). 즉 <b>아래 채널별 덮어쓰기만으로는 부족할 수 있다</b> — 값이 하나로
 * 고정된 양이 아니기 때문이다. 판별기(POK-59)가 붙어 이 위치를 실제로 쓰기 시작할 때
 * 다시 본다. 기본값을 어느 회차에서 고를지도 이 변동에서 나왔다 —
 * 근거는 {@code application.yml}의 그 줄에 있다(숫자를 두 곳에 두지 않는다).
 *
 * <p><b>{@code channel-offset-ms:} 줄을 yml에 쓰지 마라.</b> 빈 맵 {@code {}}을 적으면
 * Spring의 {@code YamlProcessor.buildFlattenedMap}이 그것을 <b>빈 문자열로 평탄화</b>하고,
 * Binder가 {@code String → Map} 변환기를 못 찾아 바인딩이 실패한다. 이 record는
 * {@code @ConfigurationPropertiesScan}이 <b>모든 컨텍스트</b>에 올리므로 검사 전부와 운영
 * 부팅이 통째로 죽는다(계획 검증에서 재현). 줄을 안 쓰면 아래 compact 생성자가
 * {@code null}을 빈 맵으로 받는다.
 *
 * <p><b>키만 쓰고 값을 빠뜨리면({@code streamer-a:}) 그 줄은 조용히 사라진다</b>(2026-08-24 실측).
 * 위와 같은 평탄화로 값이 빈 문자열이 되는데, 이때는 Binder가 <b>엔트리째 버려서</b> 부팅이
 * 멀쩡하고 그 채널만 기본 보정값으로 떨어진다 — {@code Map.copyOf}까지 오지도 않는다.
 * <b>여기에는 그물을 놓을 수 없다</b>(엔트리가 사라진 뒤라 남는 정보가 없다). 결말이 가벼워
 * (그 채널의 위치가 덜 정확할 뿐, 아래 자릿수 착각처럼 {@code no_footage}로 가지 않는다)
 * Environment를 직접 훑는 대가를 치르지 않았고, 대신 {@code SyncPropertiesValidationTest}가
 * 그 동작을 못박아 둔다.
 */
@ConfigurationProperties(prefix = "pokeclip.sync")
public record SyncProperties(long defaultOffsetMs, Map<String, Long> channelOffsetMs) {

    /**
     * 받아 주는 보정값의 크기 상한(부호는 안 본다 — 음수 허용이 이 카드의 결정이다).
     *
     * <h2>왜 10분인가 — 이 값이 무엇의 합인지에서 나왔다</h2>
     * 보정값 = (A 방송 지연 + B 시청자 반응 지연 + C 전달 지연) − P 우리 인제스트 지연.
     * 이 상한을 정할 때는 <b>C와 P만 재어져 있었고</b> — C는 ±175ms 수준(2026-08-05 +175ms ·
     * 08-15 −39~−70ms), P는 인코딩 0.1~0.5s + SRT 버퍼 0.2s~ — <b>A·B는 미측정이라 「초 단위일
     * 것」이라는 추정</b> 위에 10배 이상의 여유를 준 자리가 10분이었다.
     *
     * <p><b>그 추정이 실측으로 확인됐다</b>(2026-08-24): 합을 통째로 잰 값이 <b>3.9초</b>였고
     * (표본 20개 중앙값 3,884ms · 범위 3,746~4,977), 그 안에서 P는 785ms였다. 즉 합의 현실적
     * 크기는 <b>수 초</b>이고 상한 10분은 그 <b>150배</b>다.
     *
     * <p><b>그래도 상한을 좁히지 않는다.</b> 여유가 목적이다 — 실측은 로컬 노트북·테스트 패턴
     * 송출·시청자 한 명의 값이고 운영에서 다시 재면 달라진다(플랫폼이 늘면 더). 이 서버는
     * 부팅 실패가 곧 <b>채팅 수집 정지</b>이고 채팅에는 백필이 없다 — 그물이 과하면 그쪽이 더
     * 나쁜 실패다. 실측이 상한에 <b>가까워지는</b> 날에 다시 보면 된다.
     *
     * <p><b>자릿수 착각은 그래도 전부 걸린다</b>: 초를 마이크로초로 착각한 값(1.5초 → 25분)도,
     * 나노초로 착각한 값(1.5초 → 17일)도 이 상한을 넘는다. 그물의 목적이 정확히 그것이다 —
     * <b>실측값은 통과시키고 단위 착각만 자른다.</b> {@code VideoPositionController}의
     * 1970~2200 그물이 입력 쪽에서 같은 일을 한다.
     */
    static final long MAX_ABS_OFFSET_MS = 600_000;

    public SyncProperties {
        requireInRange(defaultOffsetMs, "pokeclip.sync.default-offset-ms");
        if (channelOffsetMs == null) {
            channelOffsetMs = Map.of();
        } else {
            // Map.copyOf보다 「먼저」 훑는다 — copyOf는 값이 null이면 자기 자신을 가리키는 NPE를
            // 던져 원인이 설정의 어느 줄인지가 안 드러난다.
            channelOffsetMs.forEach(SyncProperties::requireChannelOffset);
            channelOffsetMs = Map.copyOf(channelOffsetMs);
        }
    }

    private static void requireChannelOffset(String channelId, Long offsetMs) {
        // 채널 식별자를 메시지에 싣는다 — 이것은 운영자가 설정 파일에 직접 적은 스트리머 채널이지
        // 이 서버가 가리는 것(채팅 본문·시청자 식별자·토큰)이 아니고, 어느 줄인지가 곧 진단이다.
        // 부팅이 끝나기 전 한 번 나가고 끝이라 수집 로그 흐름에 섞이지도 않는다.
        String property = "pokeclip.sync.channel-offset-ms." + channelId;
        if (offsetMs == null) {
            throw new IllegalArgumentException(
                    property + "에 값이 없다. ms 값을 적거나 그 줄을 지운다.");
        }
        requireInRange(offsetMs, property);
    }

    private static void requireInRange(long offsetMs, String property) {
        // Math.abs를 쓰지 않는다 — abs(Long.MIN_VALUE)는 오버플로로 「음수」를 돌려줘
        // 하필 가장 극단적인 값 하나가 그물을 그대로 통과한다.
        if (offsetMs > MAX_ABS_OFFSET_MS || offsetMs < -MAX_ABS_OFFSET_MS) {
            throw new IllegalArgumentException(property + "=" + offsetMs + "이(가) 크기 상한 "
                    + MAX_ABS_OFFSET_MS + "ms를 넘는다. 단위는 밀리초다 — 초·마이크로초·나노초와"
                    + " 헷갈렸는지 확인한다. 음수는 이 크기까지 허용한다.");
        }
    }

    /** 채널을 모르면(옛 경로처럼 {@code null}이면) 기본값이다 — 보정을 건너뛰지 않는다. */
    public long offsetFor(String channelId) {
        if (channelId == null) {
            return defaultOffsetMs;
        }
        return channelOffsetMs.getOrDefault(channelId, defaultOffsetMs);
    }
}
