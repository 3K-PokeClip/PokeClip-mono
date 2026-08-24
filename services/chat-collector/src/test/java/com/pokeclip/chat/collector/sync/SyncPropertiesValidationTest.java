package com.pokeclip.chat.collector.sync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>보정값 설정의 자릿수 착각이 조용한 오답이 되지 않는지 잰다.</b>
 *
 * <h2>왜 이 그물이 필요한가</h2>
 * {@link VideoPositionController}는 들어온 {@code messageTime}을 1970~2200으로 검사하지만
 * <b>그 검사는 보정을 빼기 전 값에 걸린다.</b> 보정 후 값에는 아무 그물이 없어서, 운영자가
 * 1.5초를 나노초로 착각해 {@code CHAT_SYNC_OFFSET_MS=1500000000}(=17일)을 넣으면 입력은
 * 400에 안 걸리고 보정된 시각이 그 방송 첫 조각보다 17일 이르러 <b>{@code no_footage}</b>가
 * 나간다. {@code no_footage}는 「영영 없음」이라 부르는 쪽이 재시도를 그만두고, 채팅에는
 * 백필이 없어 그 방송의 하이라이트가 전부 사라진다. <b>서버는 정상으로 뜨고 로그도 조용하다.</b>
 *
 * <h2>왜 부팅에서 막나</h2>
 * {@link LinkProperties}는 같은 {@code @ConfigurationPropertiesScan} 위에 있으면서도 검증을
 * 애노테이션이 아니라 {@code validate()} 메서드로 뺐다 — <b>비어 있는 것이 정상인 컨텍스트가
 * 많아서</b>, 전역에 걸면 그 값을 쓰지도 않는 부팅까지 죽기 때문이다. <b>여기서는 그 부작용이
 * 구조적으로 안 생긴다</b>: 값을 안 주면 0이고 0은 유효하다. 거절되는 것은 <b>사람이 일부러
 * 넣은 자릿수 착각 값 하나뿐</b>이고, 그런 값이 들어 있는 프로세스는 이 값을 쓰든 안 쓰든
 * 뜨면 안 된다. 그래서 compact 생성자에 둔다 — {@code new SyncProperties(…)}로 직접 만드는
 * 검사 경로에도 같은 그물이 걸린다(그물을 한쪽에만 두면 「같은 뿌리인데 한 자리만」이 된다).
 */
class SyncPropertiesValidationTest {

    @EnableConfigurationProperties(SyncProperties.class)
    static class OnlySyncProperties {
    }

    // ---------------------------------------------------------------- 생성자 그물

    @Test
    void 자릿수를_착각한_기본_보정값을_거부한다() {
        assertThatThrownBy(() -> new SyncProperties(1_500_000_000L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pokeclip.sync.default-offset-ms")
                .hasMessageContaining(String.valueOf(SyncProperties.MAX_ABS_OFFSET_MS));
    }

    /**
     * <b>음수 허용은 이 카드의 결정이다</b>(보정값의 부호가 환경에 따라 뒤집힌다 —
     * {@link SyncProperties} javadoc). 그물이 크기만 보고 부호를 안 보는지 양쪽에서 잰다.
     */
    @Test
    void 음수도_같은_크기까지_받는다() {
        long limit = SyncProperties.MAX_ABS_OFFSET_MS;

        assertThatCode(() -> new SyncProperties(-limit, Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> new SyncProperties(limit, Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SyncProperties(-limit - 1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncProperties(limit + 1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * <b>{@code Math.abs(Long.MIN_VALUE)}는 오버플로로 음수를 돌려준다</b> — 크기를
     * {@code Math.abs}로 재면 하필 가장 극단적인 값 하나가 그물을 통과한다. 위
     * {@code 음수도_같은_크기까지_받는다}는 −600001에서 멈추므로 이 갈래를 안 밟는다.
     */
    @Test
    void 오버플로로_그물을_빠져나가는_값이_없다() {
        assertThatThrownBy(() -> new SyncProperties(Long.MIN_VALUE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncProperties(Long.MAX_VALUE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 기본값은 멀쩡한데 채널 덮어쓰기만 틀린 갈래. <b>같은 뿌리에 두 자리가 있고 한 자리만
     * 고치면 여기가 빨갛다.</b> 메시지가 어느 채널 줄인지 가리켜야 고칠 수 있다.
     */
    @Test
    void 채널별_보정값도_같은_그물에_걸린다() {
        assertThatThrownBy(() -> new SyncProperties(0, Map.of("streamer-a", 1_500_000_000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pokeclip.sync.channel-offset-ms.streamer-a");
    }

    /**
     * {@code Map.copyOf}는 값이 {@code null}이면 <b>{@code Map.copyOf}를 가리키는 NPE</b>를 던진다 —
     * 원인이 설정의 어느 줄인지가 안 드러난다. 바인딩 경로로는 이 상태가 안 만들어지지만
     * (아래 {@code 채널_키만_쓰고_값을_빠뜨리면…} 참고) 직접 생성 경로에는 남아 있다.
     */
    @Test
    void 채널별_값이_없으면_어느_줄인지_말한다() {
        Map<String, Long> withNull = new HashMap<>();
        withNull.put("streamer-a", null);

        assertThatThrownBy(() -> new SyncProperties(0, withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pokeclip.sync.channel-offset-ms.streamer-a");
    }

    /** null 맵을 빈 맵으로 받는 기존 계약은 그대로다 — yml에 절 자체를 안 쓰는 것이 정상이다. */
    @Test
    void 맵이_없으면_빈_맵이다() {
        assertThat(new SyncProperties(0, null).channelOffsetMs()).isEmpty();
    }

    // ---------------------------------------------------------------- 바인딩 경로

    /**
     * <b>생성자 단언만으로는 「부팅이 실제로 막히나」를 안 잰다.</b> Binder가 compact 생성자를
     * 지나가는지, 그 예외가 컨텍스트 시작 실패로 올라오는지를 행동으로 본다.
     */
    @Test
    void 자릿수를_착각한_값은_부팅을_막는다() {
        new ApplicationContextRunner()
                .withUserConfiguration(OnlySyncProperties.class)
                .withPropertyValues("pokeclip.sync.default-offset-ms=1500000000")
                .run(context -> assertThat(context)
                        .as("그물이 없으면 이 값이 그대로 바인딩되고 컨텍스트가 뜬다(주입으로 확인)")
                        .hasFailed());
    }

    @Test
    void 채널별_자릿수_착각도_부팅을_막는다() {
        new ApplicationContextRunner()
                .withUserConfiguration(OnlySyncProperties.class)
                .withPropertyValues("pokeclip.sync.channel-offset-ms.streamer-a=1500000000")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 실측으로 정한 범위 안의 값은 통과해야 한다 — 그물이 운영을 막으면 더 나쁜 실패다. */
    @Test
    void 실측_크기의_보정값은_통과한다() {
        new ApplicationContextRunner()
                .withUserConfiguration(OnlySyncProperties.class)
                .withPropertyValues("pokeclip.sync.default-offset-ms=-3200",
                        "pokeclip.sync.channel-offset-ms.streamer-a=4500")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * <b>스프링의 동작을 못박는 검사다.</b> yml에 {@code channel-offset-ms:} 아래 키만 쓰고
     * 값을 빠뜨리면({@code streamer-a:}) YAML 평탄화가 그것을 <b>빈 문자열</b>로 만들고
     * Binder가 <b>그 엔트리를 통째로 버린다</b> — 부팅은 멀쩡하고 그 채널만 조용히 기본
     * 보정값으로 떨어진다(2026-08-24 실측). <b>NPE로 죽지 않는다.</b>
     *
     * <p>여기 그물을 놓을 수는 없다 — 엔트리가 사라진 뒤라 {@link SyncProperties}에 남는
     * 정보가 없다. 잡으려면 Environment의 property source를 직접 훑어야 하고, 그 대가에 비해
     * 결말이 가볍다(그 채널이 기본 보정값을 쓴다 = 위치가 덜 정확하다. 위 자릿수 착각처럼
     * {@code no_footage}로 가지 않는다). <b>그래서 대신 이 검사가 그 동작을 못박는다</b> —
     * 스프링이 언젠가 NPE 쪽으로 바뀌면 여기가 빨개져 알게 된다.
     */
    @Test
    void 채널_키만_쓰고_값을_빠뜨리면_그_줄이_조용히_사라진다() {
        new ApplicationContextRunner()
                .withUserConfiguration(OnlySyncProperties.class)
                .withPropertyValues("pokeclip.sync.channel-offset-ms.streamer-a=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SyncProperties.class).channelOffsetMs())
                            .as("엔트리가 살아 있으면 값이 null이라 생성자가 잡을 수 있다는 뜻이다")
                            .isEmpty();
                });
    }
}
