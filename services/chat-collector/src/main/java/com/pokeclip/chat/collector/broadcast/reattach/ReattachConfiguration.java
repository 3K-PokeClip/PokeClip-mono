package com.pokeclip.chat.collector.broadcast.reattach;

import com.pokeclip.chat.collector.broadcast.BroadcastSessions;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.chat.collector.broadcast.intake.IntakeProperties;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * <b>재부착이 clip을 부르기까지의 부품 전부.</b> 꺼져 있으면 하나도 안 만든다 —
 * 안 물어보면 목록도, 잴 공백도, 돌 주기도 없다({@code LetterPathConfiguration}과 같은 규칙).
 *
 * <p><b>{@code GapMeasurer}는 여기서 안 만든다.</b> 그것은 {@code @Component}라 컨테이너에
 * 이미 있고, 여기서 {@code @Bean}으로 또 만들면 <b>같은 이름의 빈 정의가 둘</b>이 되어
 * 부팅이 죽는다(Boot는 정의 덮어쓰기를 기본으로 막는다). 재부착이 꺼진 프로세스에 그것
 * 하나가 남는 값은 {@code JdbcTemplate} 참조 하나뿐이다.
 *
 * <p>🔴 <b>내부 토큰은 {@code pokeclip.link}에서 온다</b>(계획 검증 C4). 서버 넷이 공유하는
 * 비밀 하나라 프로퍼티를 새로 만들면 같은 값을 두 곳에서 읽게 되고 한쪽만 고쳐지는 날 갈라진다.
 * {@code status/InternalApiConfiguration}이 수집 상태 창구에서 이미 그렇게 한다.
 */
@Configuration
// 운영 컨텍스트는 @ConfigurationPropertiesScan이 이미 잡지만, 검사가 이 설정만 떼어 띄울 때는
// 그것이 없다. 둘 다 같은 빈 이름이라 겹쳐도 문제없다(IntakeConfiguration과 같은 이유).
@EnableConfigurationProperties({ReattachProperties.class, LinkProperties.class, IntakeProperties.class})
@ConditionalOnProperty(prefix = "pokeclip.reattach", name = "enabled", havingValue = "true")
public class ReattachConfiguration {

    /**
     * 🔴 <b>알림 경로가 꺼진 채로는 켤 수 없다</b>(계획 검증 I1). {@link Reattacher}가
     * {@code BroadcastSessions}를 필요로 하는데 그 빈은 {@code LetterPathConfiguration}
     * (즉 {@code pokeclip.broadcast.intake.enabled=true})만 만든다.
     *
     * <p><b>「조용히 무시」로 풀지 않았다.</b> 이 서버가 반복해서 데인 자리가 정확히
     * 「설정은 켰는데 그 기능만 조용히 죽어 있고 health는 초록」이다
     * ({@code LetterPathConfiguration} 생성자가 옛 경로 둘을 같이 켜는 것을 막는 것과
     * 같은 모양이자 같은 이유). <b>여기서 안 막으면 스프링이 「no qualifying bean of type
     * BroadcastSessions」로 죽는데</b>, 그 메시지는 무엇을 켜야 하는지를 안 알려 준다.
     *
     * <p><b>의미도 맞다</b> — 재부착이 줍는 것은 「알림으로 붙었어야 하는데 못 붙은 방송」이라,
     * 알림 경로가 꺼져 있으면 애초에 주울 것이 없다.
     */
    public ReattachConfiguration(IntakeProperties intake) {
        if (!intake.enabled()) {
            throw new IllegalStateException(
                    "pokeclip.reattach.enabled=true는 pokeclip.broadcast.intake.enabled=true "
                    + "없이 켤 수 없다. 재부착은 알림으로 붙었어야 하는데 못 붙은 방송을 "
                    + "줍는 것이라, 알림 경로가 꺼져 있으면 주울 것이 없다.");
        }
    }

    /**
     * <b>주입받은 빌더를 쓴다.</b> {@code RestClient.create()}는 자동 설정을 우회해
     * {@code spring.http.clients.*}의 시한이 어디에도 안 걸리고, clip이 연결만 받고 답을
     * 안 하면 재부착 회차가 무기한 매달린다(이 서버가 이미 데인 자리).
     */
    @Bean
    public LiveBroadcastClient liveBroadcastClient(RestClient.Builder restClientBuilder,
                                                   ReattachProperties reattach, LinkProperties link) {
        return new LiveBroadcastClient(restClientBuilder, reattach, link);
    }

    /**
     * <b>줄 실행기를 여기서 만들지 않는다 — 밖에서 받는다.</b> 알림 경로와 재부착이 같은 줄을
     * 써야 같은 방송에 두 길이 동시에 붙는 것을 막는데, 각자 하나씩 만들면 그 보호가 통째로
     * 사라진다. 만드는 자리는 {@code CollectorApplication} 하나다.
     */
    @Bean
    public Reattacher reattacher(LiveBroadcastClient client, SessionRegistry registry,
                                 EndedStreamStore store, GapMeasurer measurer,
                                 StreamerSerialExecutor lanes, BroadcastSessions sessions) {
        return new Reattacher(client, registry, store, measurer, lanes, sessions, Instant::now);
    }

    @Bean
    public ReattachScheduler reattachScheduler(Reattacher reattacher, ReattachStatus status) {
        return new ReattachScheduler(reattacher, status);
    }
}
