package com.pokeclip.chat.collector.broadcast.reattach;

import com.pokeclip.chat.collector.broadcast.BroadcastSessions;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>재부착 배선이 실제로 물리는가, 그리고 반쯤 물린 채로는 뜨지 않는가.</b>
 *
 * <p>이 카드에서 「설정은 켰는데 그 기능만 조용히 죽어 있고 health는 초록」을 막는 자리다 —
 * 이 서버가 반복해서 데인 모양이라 <b>부팅을 거부하는 쪽</b>으로 전부 풀었다.
 *
 * <p>🔴 <b>양성 대조가 없으면 아래 셋이 아무것도 안 잰다</b>(계획 검증 I2).
 * {@link ReattachConfiguration}만 떼어 띄우면 필요한 빈이 하나도 없어 <b>아무 이유로나</b>
 * 실패하고, 그러면 「주소가 비어서 죽었다」가 아니라 그냥 초록이다. 그래서
 * {@link #runnerWithDeps()}가 필요한 것을 전부 넣고
 * {@link #값이_다_있으면_실제로_뜬다()}가 그 컨텍스트가 실제로 뜨는 것을 먼저 못박는다.
 */
class ReattachWiringTest {

    private static final String CLIP = "http://clip:8081";

    /**
     * 재부착이 켜졌을 때 실제로 필요한 빈 전부. <b>{@code GapMeasurer}는 여기서 넣는다</b> —
     * 그것은 {@code @Component}라 운영에서는 재부착과 무관하게 만들어지고,
     * {@link ReattachConfiguration}이 그것을 <b>만들지 않고 주입만 받는다</b>(두 곳에서 만들면
     * 같은 이름의 빈 정의가 둘이라 부팅이 죽는다).
     *
     * <p>{@code pokeclip.broadcast.intake}의 값 넷은 <b>재부착과 무관한데도 필요하다</b> —
     * {@code IntakeProperties}가 {@code @Validated}라 region·wait-time·max-messages가 없으면
     * 바인딩에서 죽고, 그러면 아래 단언들이 「무엇 때문에 죽었는지」를 못 가른다.
     */
    private ApplicationContextRunner runnerWithDeps() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ReattachConfiguration.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(GapMeasurer.class, () -> new GapMeasurer(mock(JdbcTemplate.class)))
                .withBean(SessionRegistry.class, () -> mock(SessionRegistry.class))
                .withBean(BroadcastSessions.class, () -> mock(BroadcastSessions.class))
                .withBean(EndedStreamStore.class, () -> mock(EndedStreamStore.class))
                .withBean(StreamerSerialExecutor.class, () -> new StreamerSerialExecutor(10))
                .withBean(ReattachStatus.class, () -> new ReattachStatus(true))
                .withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=true",
                        "pokeclip.broadcast.intake.queue-url=http://localhost:4566/000000000000/b.fifo",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10",
                        "pokeclip.reattach.interval=PT1M",
                        "pokeclip.reattach.initial-delay=PT5S");
    }

    /**
     * 🔴 <b>양성 대조.</b> 이것이 없으면 아래 세 시험의 {@code hasFailed()}가 무엇 때문인지 모른다.
     */
    @Test
    void 값이_다_있으면_실제로_뜬다() {
        runnerWithDeps()
                .withPropertyValues("pokeclip.reattach.enabled=true",
                        "pokeclip.reattach.clip-base-url=" + CLIP,
                        "pokeclip.link.auth-base-url=http://localhost:8082",
                        "pokeclip.link.internal-token=secret-1")
                .run(context -> {
                    assertThat(context).hasSingleBean(LiveBroadcastClient.class);
                    assertThat(context).hasSingleBean(Reattacher.class);
                    // 스케줄러가 없으면 재부착은 만들어만 놓고 아무도 안 부른다.
                    assertThat(context).hasSingleBean(ReattachScheduler.class);
                });
    }

    /**
     * 꺼져 있으면 clip을 부르는 부품이 아예 안 생긴다.
     *
     * <p><b>값은 다 주고 {@code enabled}만 내린다.</b> 그래야 이 단언이 재는 것이
     * {@code @ConditionalOnProperty} 하나로 좁혀진다 — 값을 같이 비우면
     * 「조건이 막았다」와 「어차피 못 만든다」가 섞인다.
     */
    @Test
    void 꺼져_있으면_clip을_부르는_부품이_아예_안_생긴다() {
        runnerWithDeps()
                .withPropertyValues("pokeclip.reattach.enabled=false",
                        "pokeclip.reattach.clip-base-url=" + CLIP,
                        "pokeclip.link.auth-base-url=http://localhost:8082",
                        "pokeclip.link.internal-token=secret-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LiveBroadcastClient.class);
                    assertThat(context).doesNotHaveBean(Reattacher.class);
                    assertThat(context).doesNotHaveBean(ReattachScheduler.class);
                });
    }

    /** 서버는 뜨고 재부착만 조용히 실패하는 상태를 만들지 않는다({@code services/CLAUDE.md} 규칙). */
    @Test
    void 켜졌는데_clip_주소가_비면_부팅이_죽는다() {
        runnerWithDeps()
                .withPropertyValues("pokeclip.reattach.enabled=true",
                        "pokeclip.reattach.clip-base-url=",
                        "pokeclip.link.auth-base-url=http://localhost:8082",
                        "pokeclip.link.internal-token=secret-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 「죽었다」가 아니라 「이것 때문에 죽었다」를 잰다.
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "pokeclip.reattach.clip-base-url이(가) 비어 있다. "
                            + "CLIP_BASE_URL 환경변수를 준다.");
                });
    }

    /**
     * 토큰은 {@code pokeclip.link}에서 온다(계획 검증 C4). 그것이 비어도 죽어야 한다 —
     * 안 죽으면 clip 창구가 전부 401인데 서버는 뜨고 health는 초록이다.
     */
    @Test
    void 켜졌는데_내부_토큰이_비면_부팅이_죽는다() {
        runnerWithDeps()
                .withPropertyValues("pokeclip.reattach.enabled=true",
                        "pokeclip.reattach.clip-base-url=" + CLIP,
                        "pokeclip.link.auth-base-url=http://localhost:8082",
                        "pokeclip.link.internal-token=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("pokeclip.link.internal-token이(가) 비어 있다. "
                                    + "INTERNAL_API_TOKEN 환경변수를 준다.");
                });
    }

    /**
     * 🔴 <b>계획 검증도 못 본 자리(I1).</b> {@code Reattacher}는 {@code BroadcastSessions}가
     * 필요한데 그 빈은 {@code LetterPathConfiguration}
     * ({@code pokeclip.broadcast.intake.enabled=true})만 만든다.
     *
     * <p><b>조용히 안 도는 쪽으로 풀지 않는다</b> — 이 서버가 반복해서 데인 자리가 정확히
     * 「설정은 켰는데 그 기능만 조용히 죽어 있고 health는 초록」이다.
     * 옛 경로 둘을 같이 켜면 거부하는 것({@code LetterPathConfiguration} 생성자)과 같은 모양이다.
     *
     * <p><b>의미도 맞다</b>: 재부착이 줍는 것은 「알림으로 붙었어야 하는데 못 붙은 방송」이라
     * 알림 경로가 꺼져 있으면 애초에 주울 것이 없다.
     */
    @Test
    void 알림_경로가_꺼진_채_재부착만_켜면_부팅이_죽는다() {
        runnerWithDeps()
                .withPropertyValues("pokeclip.reattach.enabled=true",
                        // runnerWithDeps의 true를 덮는다
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.reattach.clip-base-url=" + CLIP,
                        "pokeclip.link.auth-base-url=http://localhost:8082",
                        "pokeclip.link.internal-token=secret-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "pokeclip.reattach.enabled=true는 "
                            + "pokeclip.broadcast.intake.enabled=true 없이 켤 수 없다. "
                            + "재부착은 알림으로 붙었어야 하는데 못 붙은 방송을 줍는 것이라, "
                            + "알림 경로가 꺼져 있으면 주울 것이 없다.");
                });
    }

    /**
     * 🔴 <b>알림 경로와 재부착이 같은 줄을 쓴다.</b> 따로 두면 부팅 직후 큐에 남은 알림과
     * 재부착이 같은 방송에 동시에 붙으려 한다 — 그것을 막는 것이 줄의 일이다.
     *
     * <p><b>왜 이 단언이 그것을 재나</b>: {@link ReattachConfiguration}이 자기 실행기를 만들면
     * 이 컨텍스트에 {@code StreamerSerialExecutor} 빈이 <b>둘</b>이 되고,
     * {@code Reattacher}의 주입 지점이 그 둘 사이에서 갈린다. 하나뿐이면서 재부착이 떠 있다는
     * 것은 <b>재부착이 밖에서 받은 그 하나를 쓴다</b>는 뜻이다.
     * 운영에서 그 하나를 만드는 자리는 {@code CollectorApplication}이다.
     *
     * <p>줄 이름이 실제로 같은 함수({@code LaneKey})에서 오는지는 부품 층에서 잰다 —
     * {@code ReattacherTest.재부착은_알림_경로와_같은_줄에_들어간다}.
     */
    @Test
    void 재부착이_켜져도_줄_실행기는_하나뿐이다() {
        runnerWithDeps()
                .withPropertyValues("pokeclip.reattach.enabled=true",
                        "pokeclip.reattach.clip-base-url=" + CLIP,
                        "pokeclip.link.auth-base-url=http://localhost:8082",
                        "pokeclip.link.internal-token=secret-1")
                .run(context -> {
                    assertThat(context).hasSingleBean(Reattacher.class);
                    assertThat(context).getBeans(StreamerSerialExecutor.class).hasSize(1);
                });
    }

    /**
     * 부팅 직후 붙어야 배포로 잃는 시간이 짧다. <b>첫 지연이 주기와 같으면 1분을 그냥 잃는다</b> —
     * {@code EndedStreamSweeper}가 같은 값을 두 자리에 쓰는 모양이라 베끼기 쉬운 함정이다.
     *
     * <p>🔴 <b>손으로 만든 record를 재지 않는다</b>(문항 8). 그러면 검사가 붙드는 것이 운영이
     * 쓰는 값이 아니라 사본이라, 애노테이션을 통째로 바꿔도 초록이다. 여기서는
     * <b>{@code @Scheduled}가 실제로 가리키는 두 열쇠</b>를 읽어, <b>{@code application.yml}에
     * 적힌 그 두 값</b>을 비교한다 — 운영이 밟는 경로 그대로다.
     */
    @Test
    void 첫_회차가_주기보다_먼저_돈다() throws Exception {
        Scheduled scheduled = ReattachScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled가 없으면 재부착은 한 번도 안 돈다").isNotNull();

        Duration initialDelay = yamlDuration(placeholderKey(scheduled.initialDelayString()));
        Duration interval = yamlDuration(placeholderKey(scheduled.fixedDelayString()));

        assertThat(initialDelay)
                .as("첫 지연 %s이(가) 주기 %s보다 짧아야 배포로 잃는 시간이 그만큼 준다",
                        initialDelay, interval)
                .isLessThan(interval);
    }

    /** {@code ${pokeclip.reattach.interval}} → {@code pokeclip.reattach.interval} */
    private static String placeholderKey(String placeholder) {
        assertThat(placeholder).as("설정에서 읽지 않고 상수를 박으면 yml을 고쳐도 안 바뀐다")
                .startsWith("${").endsWith("}");
        return placeholder.substring(2, placeholder.length() - 1);
    }

    /**
     * {@code application.yml}의 값을 읽는다. {@code ${VAR:기본값}} 모양이면 기본값을 쓴다 —
     * 환경변수를 안 준 프로세스가 실제로 받는 값이 그것이다.
     */
    private static Duration yamlDuration(String key) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).as("application.yml을 읽지 못했다").isNotNull();

        String raw = properties.getProperty(key);
        assertThat(raw).as("application.yml에 %s가 없다 — @Scheduled가 못 읽으면 부팅이 죽는다", key)
                .isNotNull();
        if (raw.startsWith("${")) {
            int colon = raw.indexOf(':');
            assertThat(colon).as("%s의 환경변수에 기본값이 없다", key).isPositive();
            raw = raw.substring(colon + 1, raw.length() - 1);
        }
        return Duration.parse(raw);
    }
}
