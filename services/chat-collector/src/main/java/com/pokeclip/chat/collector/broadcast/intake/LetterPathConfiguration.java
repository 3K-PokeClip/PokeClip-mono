package com.pokeclip.chat.collector.broadcast.intake;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.BroadcastSessions;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.broadcast.LinkedSessionStarter;
import com.pokeclip.chat.collector.broadcast.StoppedStreamRecorder;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.chat.collector.link.ChzzkLinkClient;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * <b>편지 한 통이 세션이 되기까지의 부품 전부.</b> 큐가 꺼져 있으면 하나도 안 만든다 —
 * 편지가 안 오면 판정할 것도, 열쇠를 물을 일도, 돌 루프도 없다.
 *
 * <p><b>{@link ChzzkLinkClient}까지 여기 있는 이유</b>: 그 클라이언트는 생성자에서
 * {@code INTERNAL_API_TOKEN}을 검증하며 죽는다. 항상 만들면 <b>CI와 팀원 로컬이 쓰지도 않는
 * 토큰이 없다는 이유로 매번 부팅에 실패한다.</b> 반대로 켜져 있는데 토큰이 없으면 여전히
 * 부팅이 죽는다({@code services/CLAUDE.md}의 "{@code ${VAR:}} + 검증" 규칙이 겨누는 것은
 * "서버는 뜨고 그 기능만 조용히 실패하는 것"이고, 여기서는 그 기능이 아예 없다).
 *
 * <p><b>{@link IntakeConfiguration}과 갈라 둔다.</b> 거기는 큐 클라이언트와 상태만 두는
 * 자리이고, 검사가 그것만 떼어 띄운다({@code ApplicationContextRunner}). 이 설정을 그 안에
 * 중첩하면 그 컨텍스트에도 같이 등록돼 <b>등록부·설정 빈이 없는 곳에서 부팅이 깨진다</b>
 * (2026-08-19에 실제로 그 검사가 빨간불이 됐다).
 */
@Configuration
@ConditionalOnProperty(prefix = "pokeclip.broadcast.intake", name = "enabled", havingValue = "true")
public class LetterPathConfiguration {

    /**
     * 🔴 <b>두 시작 경로를 같이 켜면 부팅을 거부한다.</b>
     *
     * <p>옛 경로(설정으로 한 채널만 붙이는 길)의 세션이 재시도로 안 풀리는 사유
     * (REVOKED·401·403)를 만나면 {@code CollectorRunner}가 최종 판정을 내고
     * <b>프로세스를 exit 1로 내린다.</b> 그 결말은 「이 프로세스가 수집할 것이 그 하나뿐」일
     * 때만 맞다 — 편지로 연 세션이 백 개 떠 있는데 옛 경로 하나의 동의 철회로 전원이 끊기면,
     * 그 방송들을 되살릴 STARTED 편지는 <b>이미 소비돼 큐에 없다.</b>
     * ({@code SessionRegistry.stopOne}이 같은 사건을 「그 세션만 닫는다」로 다루는 것과
     * 결말이 다른 것은 실수가 아니다 — 등록부는 exit 손잡이를 아예 안 받는다.)
     *
     * <p><b>「둘 다 켜지면 옛 경로를 조용히 무시」로 풀지 않았다.</b> 그러면
     * {@code CHZZK_ENABLED=true}가 아무 일도 안 하는 스위치가 되는데, 이 서버가 반복해서
     * 데인 자리가 정확히 「설정은 켰는데 그 기능만 조용히 죽어 있고 health는 초록」이다.
     * 옛 경로는 실측·디버깅용으로 <b>살아 있다</b> — 다만 혼자 켠다.
     */
    public LetterPathConfiguration(ChzzkProperties chzzk) {
        if (chzzk.enabled()) {
            throw new IllegalStateException(
                    "pokeclip.chzzk.enabled=true와 pokeclip.broadcast.intake.enabled=true를 "
                    + "같이 켤 수 없다. 옛 경로 세션의 영구 정지가 프로세스를 내려 편지로 연 "
                    + "세션 전부를 끊는다. CHZZK_ENABLED=false로 두고 편지로 붙이거나, "
                    + "BROADCAST_INTAKE_ENABLED=false로 두고 한 채널만 붙여라.");
        }
    }

    @Bean
    public ChzzkLinkClient chzzkLinkClient(RestClient.Builder restClientBuilder, LinkProperties properties) {
        return new ChzzkLinkClient(restClientBuilder, properties);
    }

    /**
     * <b>레코더를 같이 문다.</b> auth가 열쇠를 영구히 거절하면 세션이 서 보지도 못해 등록부의
     * 포기 알림이 울리지 않는데, 그때도 메모는 남아야 한다 — 안 남기면 편지를 지운 뒤
     * 그 방송이 영원히 {@code unknown}이다(되돌아올 트리거가 없다).
     */
    @Bean
    public BroadcastSessions broadcastSessions(ChzzkLinkClient link, SessionRegistry registry,
                                               StoppedStreamRecorder recorder) {
        return new LinkedSessionStarter(link, registry, recorder::record);
    }

    @Bean
    public BroadcastEventProcessor broadcastEventProcessor(EndedStreamStore store, BroadcastSessions sessions) {
        return new BroadcastEventProcessor(store, sessions);
    }

    /** 편지 경로가 켜진 프로세스에서만 뜻이 있다 — 옛 경로는 등록부를 안 탄다. */
    @Bean
    public StoppedStreamRecorder stoppedStreamRecorder(SessionRegistry registry, EndedStreamStore store) {
        return new StoppedStreamRecorder(registry, store, Instant::now);
    }

    /**
     * <b>{@code ObjectProvider<SqsClient>}로 받는다.</b> 빈손이면 {@link SqsIntakeRunner}
     * 생성자가 거부해 <b>부팅이 죽어서 드러난다</b> — 껍데기를 물고 조용히 안 도는 것보다 낫다.
     * 큐가 켜져 있는데 클라이언트가 없는 상태는 조립 실수이지 정상이 아니다.
     *
     * <p><b>{@code Optional<SqsClient>}로 받아도 여기서는 실제로 주입된다</b>(2026-08-19 실측:
     * 파라미터 타입만 바꿔 돌렸더니 폴링이 정상으로 돌고 배선 검사 7개가 전부 초록이었다).
     * 그러니 <b>「{@code Optional}은 주입 지점이 가로채여 늘 빈손」이라는 설명은 이 모양에서는
     * 틀렸다.</b> 위험한 모양은 <b>{@code @Bean}이 {@code Optional<T>}를 돌려주는 것</b>이다 —
     * 그러면 컨테이너에 {@code T} 타입 빈이 없고, {@code Optional<T>} 주입 지점은 {@code T}를
     * optional로 찾으므로 <b>그 빈을 영영 못 만난다.</b> 그 모양은
     * {@code OptionalBeanShapeProbeTest}가 실물 컨텍스트로 재서 못박아 뒀다.
     *
     * <p>편지를 읽는 {@code ObjectMapper}는 스프링 것을 그대로 쓴다. 웹 설정이 바뀌어도 봉투가
     * 깨지지 않는 것은 {@code LifecycleEnvelope}의 {@code ignoreUnknown}이 지킨다.
     */
    @Bean
    public SqsIntakeRunner sqsIntakeRunner(ObjectProvider<SqsClient> sqs, IntakeProperties properties,
                                           IntakeStatus status, BroadcastEventProcessor processor,
                                           ObjectMapper mapper, StreamerSerialExecutor lanes) {
        return new SqsIntakeRunner(sqs.getIfAvailable(), properties, status, processor, mapper, lanes);
    }

    @Bean
    public SqsIntakeLoop sqsIntakeLoop(SqsIntakeRunner runner) {
        return new SqsIntakeLoop(runner);
    }
}
