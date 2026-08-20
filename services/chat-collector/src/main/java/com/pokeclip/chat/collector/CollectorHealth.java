package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.broadcast.BroadcastCounters;
import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.intake.IntakeStatus;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;
import java.time.Instant;
import java.time.Duration;

/**
 * 수집이 멈춘 것을 헬스체크에 드러낸다.
 *
 * <p><b>수집이 죽었는데 health가 UP인 상태를 만들지 않는다.</b> 그러면 배포도
 * 헬스체크도 통과하는데 채팅만 안 들어오고, 원인을 가리키는 신호가 아무 데도
 * 안 남는다 — 이 서비스의 유일한 치명적 실패다.
 *
 * <p>꺼져 있는 것은 실패가 아니라 설정이라 UP이다. 다만 상세에 적어 둔다 —
 * "왜 채팅이 안 들어오지"의 첫 번째 답이 대개 이것이다.
 *
 * <h2>🔴 스트리머가 여럿이 되면서 「전체 DOWN」의 뜻이 바뀌었다</h2>
 *
 * <p>세션이 하나뿐이던 시절에는 <b>그 세션이 안 걷히면 이 프로세스는 아무것도 안 하는
 * 것</b>이라 통째로 DOWN이 맞았다. 편지로 세션을 여럿 여는 지금은 성립하지 않는다 —
 * 열 명 중 한 명이 재연결 중이라고 전체를 DOWN으로 두면 <b>나머지 아홉이 멀쩡한데
 * 배포가 막힌다.</b> 그래서 개별 세션 상태는 {@code activeSessions}·
 * {@code reconnectingSessions} 상세로 내리고, 전체 DOWN은 <b>「편지를 아예 못 꺼내는
 * 상태」</b>가 진다({@link IntakeStatus}). 그 상태라야 새 방송이 하나도 안 붙는다.
 *
 * <p><b>세션이 하나도 없는 것은 DOWN이 아니다.</b> 방송 없는 시간대가 정상이라,
 * 여기서 DOWN을 주면 밤마다 알람이 운다.
 *
 * <p><b>옛 경로({@code CHZZK_ENABLED})의 DOWN은 그대로 둔다.</b> 그쪽은 설정으로 채널
 * 하나를 붙이는 디버깅 전용이고 편지 경로와 <b>같이 켤 수 없어</b>
 * ({@code LetterPathConfiguration}이 부팅을 거부한다), 그 프로세스에서는 「세션 하나 =
 * 이 프로세스가 할 일 전부」라는 옛 전제가 여전히 참이다. 위 문단이 겨냥하는
 * 「나머지 아홉」이 그 프로세스에는 존재하지 않는다.
 */
@Component
public class CollectorHealth implements HealthIndicator {

    private final CollectionStatus status;
    private final SessionRegistry registry;
    private final IntakeStatus intake;

    /**
     * 편지 경로가 꺼져 있으면 <b>null이다</b> — 판정기 빈 자체가 없다.
     *
     * <p>주입 지점이 {@link ObjectProvider}인 이유는 「없을 수 있다」를 컨테이너가 아는
     * 유일한 모양이어서다. <b>{@code @Bean}이 {@code Optional<T>}를 돌려주게 하지 마라</b> —
     * 그러면 컨테이너에 {@code T} 타입 빈이 없어 받는 쪽이 영영 빈손인데 아무도 안 죽는다
     * ({@code OptionalBeanShapeProbeTest}가 실물 컨텍스트로 재 뒀다).
     *
     * <p>생성자에서 한 번 풀어 둔다. health 응답마다 컨테이너를 뒤질 이유가 없고,
     * 이 빈의 유무는 부팅 뒤에 안 바뀐다.
     */
    private final BroadcastEventProcessor processor;

    /**
     * <b>편지를 이만큼 못 꺼내면 아프다고 말한다.</b> 롱폴링이 20초라 정상이면 그 안에
     * 한 번은 성공한다 — 2분은 여섯 회차를 놓친 뒤다. `services/README.md`가 밖에
     * 약속한 값이므로 <b>고치려면 그 문서도 같이 고친다.</b>
     */
    static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final Supplier<Instant> clock;

    /**
     * <b>아직 한 번도 못 꺼냈을 때의 기준점.</b> 부팅 직후에는 마지막 성공 시각이 없어
     * 「얼마나 오래」를 잴 상대가 없다 — 그때는 이 부품이 생긴 시각부터 센다.
     * 이것이 없으면 부팅하자마자 DOWN이거나(기준을 EPOCH로 두면), 큐를 <b>한 번도</b>
     * 못 잡은 프로세스가 영영 UP이다(창을 아예 안 재면).
     */
    private final Instant createdAt;

    /**
     * <b>{@code @Autowired}가 필요하다.</b> 생성자가 둘이면 스프링은 어느 것으로 만들지
     * 모르고 「기본 생성자 없음」으로 부팅이 죽는다 — 실제로 그렇게 죽였다.
     */
    @Autowired
    public CollectorHealth(CollectionStatus status, SessionRegistry registry, IntakeStatus intake,
                           ObjectProvider<BroadcastEventProcessor> processor) {
        this(status, registry, intake, processor, Instant::now);
    }

    CollectorHealth(CollectionStatus status, SessionRegistry registry, IntakeStatus intake,
                    ObjectProvider<BroadcastEventProcessor> processor, Supplier<Instant> clock) {
        this.status = status;
        this.registry = registry;
        this.intake = intake;
        this.processor = processor.getIfAvailable();
        this.clock = clock;
        this.createdAt = clock.get();
    }

    /**
     * <b>네 곳을 각각 한 번씩만 읽는다.</b> 낱개 getter를 이어 부르면 갈래를 고른 뒤에
     * 값이 바뀌어, 상세만 다음 순간의 것이 실린다 — "재연결 중인데 사유는 없음"이 그
     * 모양이고, 널 검사와 렌더가 각각 읽으면 아예 500으로 터진다. 하필 재연결이
     * 성공하는 그 순간에만 일어나 원인을 찾기가 가장 어렵다. 세션 수와 재연결 수를
     * 따로 세면 <b>재연결 중인 수가 붙어 있는 수보다 큰</b> 응답이 나간다.
     */
    @Override
    public Health health() {
        CollectionStatus.Snapshot now = status.snapshot();
        SessionRegistry.Counts sessions = registry.counts();
        IntakeStatus.Snapshot letters = intake.snapshot();
        // 판정기가 없으면(편지 경로 꺼짐) 셋 다 0이다. 「꺼져서 0」과 「켜졌는데 버린 게
        // 없어서 0」은 아래 letterIntake가 가른다 — 항을 아예 빼면 대시보드 쪽에서
        // 「그런 값은 원래 없다」와 「오늘은 0이다」가 같아진다.
        BroadcastCounters dropped = processor == null ? BroadcastCounters.NONE : processor.counters();

        boolean stalled = letterStalled(letters);
        Health.Builder builder = letters.healthy() && !stalled && !legacyDown(now.state())
                ? Health.up() : Health.down();
        legacyDetail(builder, now);
        return builder
                // <b>몇이 붙어 있고 그중 몇이 안 걷히나.</b> 전체가 UP이어도 여기가
                // "activeSessions=10 reconnectingSessions=7"이면 사고다 — 「전체 UP」이
                // 곧 「아무 문제 없음」으로 읽히는 길을 이 두 항이 막는다.
                .withDetail("activeSessions", sessions.active())
                .withDetail("reconnectingSessions", sessions.reconnecting())
                .withDetail("letterIntake", letterIntake(letters, stalled))
                .withDetail("lastLetterPollAt", letters.lastPollSucceededAt() == null
                        ? "none" : letters.lastPollSucceededAt().toString())
                // 예외 <b>타입 이름만</b> 실린다. 메시지에는 큐 주소·계정 번호가 들어 있고
                // 이 응답은 밖으로 나간다(IntakeStatus.Snapshot 주석).
                .withDetail("letterFailure", letters.lastFailureReason() == null
                        ? "none" : letters.lastFailureReason())
                // <b>버린 편지 셋을 합치지 않는다.</b> 1번이 고칠 자리가 셋 다 다르다 —
                // unreadableStreamerIds는 「식별자 체계가 바뀌었다」, unknownTypes는
                // 「우리가 모르는 종류를 보낸다」, malformedEnvelopes는 「봉투의 칸이
                // 비었거나 너무 길다」다. 합치면 셋 중 무엇인지 물으러 로그를 뒤져야 한다.
                //
                // <b>이 값들은 DOWN을 만들지 않는다.</b> 편지는 계속 오고 폴링도 성공하므로
                // 「못 받는 상태」가 아니고, 임계를 여기서 정하면 정상 운영에서도 빨간불이
                // 뜬다(계약 밖 종류가 하나 섞이는 것은 흔하다). 판단은 이 값을 보는 쪽이 한다.
                .withDetail("unreadableStreamerIds", dropped.unreadableStreamerIds())
                .withDetail("unknownTypes", dropped.unknownTypes())
                .withDetail("malformedEnvelopes", dropped.malformedEnvelopes())
                .build();
    }

    /**
     * 옛 경로가 <b>못 걷고 있는가.</b> 그 경로는 세션이 하나뿐이고 편지 경로와 같이 못 켜므로,
     * 여기가 참이면 이 프로세스는 실제로 아무것도 안 걷고 있다.
     */
    private static boolean legacyDown(CollectionStatus.State state) {
        return state == CollectionStatus.State.RECONNECTING || state == CollectionStatus.State.STOPPED;
    }

    private static void legacyDetail(Health.Builder builder, CollectionStatus.Snapshot now) {
        switch (now.state()) {
            case DISABLED -> builder.withDetail("status", "disabled");
            case ESTABLISHING -> builder.withDetail("status", "establishing");
            case COLLECTING -> builder.withDetail("status", "collecting");
            case RECONNECTING -> builder
                    .withDetail("status", "reconnecting")
                    .withDetail("reason", reasonOf(now))
                    // 언제부터 못 받고 있는지가 없으면 "방금 끊겼다"와 "10분째 못 붙는다"가 같아 보인다.
                    .withDetail("disconnectedAt", now.disconnectedAt() == null
                            ? "unknown" : now.disconnectedAt().toString())
                    .withDetail("attempt", now.attempt());
            case STOPPED -> builder
                    .withDetail("status", "stopped")
                    .withDetail("reason", reasonOf(now));
        }
    }

    private static String reasonOf(CollectionStatus.Snapshot now) {
        return now.reason() == null ? "UNKNOWN" : now.reason().name();
    }

    /**
     * 「꺼짐」·「아직 한 번도 못 돌았다」·「도는 중」·「못 닿는다」 넷을 가른다.
     *
     * <p>가운데 둘을 뭉쳐 놓으면 <b>부팅 직후의 창</b>과 <b>정상 폴링</b>이 같아 보인다 —
     * 큐를 처음부터 못 잡고 있는 프로세스가 「아직 안 돌았을 뿐」으로 읽힌다.
     * {@code lastLetterPollAt}이 그 판단의 재료이고 여기는 그 요약이다.
     */
    private static String letterIntake(IntakeStatus.Snapshot letters, boolean stalled) {
        if (!letters.enabled()) {
            return "disabled";
        }
        if (letters.lastFailureReason() != null) {
            return "failing";
        }
        if (stalled) {
            return "stalled";
        }
        return letters.lastPollSucceededAt() == null ? "starting" : "ok";
    }

    /**
     * <b>편지를 너무 오래 못 꺼내고 있는가.</b> {@link IntakeStatus}가 이 판단을 일부러
     * 안 하고 시각만 내주는 이유가 여기 있다 — 임계를 정하는 것은 health의 일이다.
     *
     * <p><b>이것이 없으면 「UP인데 수집 없음」의 갈래가 하나 열린다.</b> 폴링 스레드가
     * {@code pollFailed}를 안 거치고 죽으면(잡히지 않은 {@code Error}·행) 마지막 성공
     * 기록이 그대로 남아 <b>영원히 건강하다고 말한다.</b> 그동안 새 방송은 하나도 안 붙는다
     * (codex P1, 재현함: 1시간 전에 성공한 뒤 멈춰도 {@code healthy()}가 true였다).
     *
     * <p>꺼져 있거나 이미 실패로 아픈 갈래는 여기서 안 본다 — 그쪽은 다른 항이 이미 말한다.
     */
    private boolean letterStalled(IntakeStatus.Snapshot letters) {
        if (!letters.enabled() || letters.lastFailureReason() != null) {
            return false;
        }
        Instant since = letters.lastPollSucceededAt() == null
                ? createdAt : letters.lastPollSucceededAt();
        return clock.get().isAfter(since.plus(STALE_AFTER));
    }
}
