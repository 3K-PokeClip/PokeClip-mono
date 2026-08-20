package com.pokeclip.chat.collector.probe;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 같은 {@code RestClient} 하나로 던진 동시 요청이 <b>실제로 겹쳐서 나가는가</b>를 잰다.
 * 가짜 서버로 도므로 실계정이 필요 없다.
 *
 * <p><b>단언이 없다. 일부러다.</b> {@link SessionLimitProbeTest}와 같은 이유다 —
 * 이건 지키는 검사가 아니라 적는 계측기다. 로그 접두어는 {@code [P4]}이고 결과는
 * {@code build/test-results/test/*.xml}의 {@code system-out}에 담긴다.
 *
 * <p>🔴 <b>이 값을 단독 근거로 쓰지 마라. 실물과 다를 수 있다.</b> 반납은 RestClient로
 * 나가고 이 서버는 {@code spring.http.clients.imperative.factory=jdk}라 JDK
 * {@code HttpClient}다. <b>상대가 HTTP/2면 커넥션 하나에 다 몰려 전부 겹치고,
 * HTTP/1.1이면 나눠 쓴다.</b> 가짜 서버(로컬 톰캣)와 치지직의 프로토콜이 다르면 잰 값이
 * 어긋난다 — 가짜에선 겹치는데 실물에선 배치인 경우 <b>테스트는 초록이고 운영에서
 * 유예를 넘긴다.</b> 판정은 {@code SessionLimitProbeTest}의 실계정 프로브(세션 셋)가 한다.
 * 여기서는 20개까지 늘렸을 때의 <b>모양만</b> 본다.
 *
 * <p><b>multi-session-test-reality 문항</b> — 단언이 없으므로 1·2·4·5는 해당 없다
 * (통과 여부가 값에 안 달려 있다). <b>문항 3만 이 프로브의 본체다</b>: 관측점을 우리 쪽이
 * 아니라 <b>상대 쪽</b>에 뒀다 — 가짜 서버가 <b>도착한</b> 시각을 적는다. 우리 쪽에서
 * "동시에 던졌다"를 세면 커넥션 풀이 배치로 쪼갠 것을 못 보고, 그러면 던진 수를 겹친
 * 수로 오해한다. 그 오해가 정확히 이 카드가 막으려는 것이다.
 */
@FakeChzzkTest
@TestPropertySource(properties = {
        // 운영값(5초)을 그대로 쓰면 측정 자체가 잘린다. 깊은 배치라면 뒤쪽 요청이
        // 커넥션을 기다리다 시한에 걸려 서버에 <b>도착조차 못 하고</b>, 그러면
        // 도착 시각이 모자라 "배치가 깊다"가 "요청이 실패했다"로 보인다.
        // 시한이 실제로 걸리는지는 HttpTimeoutTest가 잰다 — 여기서 잴 것은 모양뿐이다.
        "spring.http.clients.read-timeout=60s"
})
class ReleaseConcurrencyProbeTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ReleaseConcurrencyProbeTest.class);

    /** 실물 반납 왕복(약 1초)보다 짧게 잡아 검사가 오래 안 걸리게 한 값이다. */
    private static final Duration HOLD = Duration.ofMillis(300);

    /**
     * 뭉치를 가르는 간격. 한 뭉치 안의 요청은 수 ms 안에 몰려 오고 뭉치 사이는
     * {@link #HOLD}만큼 벌어지므로, 그 절반이면 양쪽 어디에도 안 걸린다.
     */
    private static final Duration CLUSTER_GAP = HOLD.dividedBy(2);

    private static final int REQUESTS = 20;

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    @AfterEach
    void tearDown() {
        behavior.reset();
    }

    @Test
    void 같은_RestClient에서_동시_요청이_실제로_겹치는지_잰다() throws Exception {
        behavior.unsubscribeDelay = HOLD;

        // 주입받은 빌더로 만든다. RestClient.create()를 쓰면 자동 설정을 우회해
        // 운영과 다른 스택을 재게 된다 — 이 서버가 이미 한 번 밟은 구덩이다.
        RestClient client = restClientBuilder.build();

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();

        for (int i = 0; i < REQUESTS; i++) {
            String sessionKey = "probe-" + i;
            outcomes.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    client.post()
                            .uri("http://localhost:" + port
                                    + "/open/v1/sessions/events/unsubscribe/chat?sessionKey=" + sessionKey)
                            .retrieve()
                            .toBodilessEntity();
                    return "ok";
                } catch (Exception e) {
                    return e.getClass().getSimpleName();
                }
            }));
        }

        // 스레드가 전부 출발선에 선 뒤에 총을 쏜다. 안 그러면 스레드 기동 시간이
        // 도착 시각에 섞여, 배치를 겹침으로 잘못 읽는다.
        ready.await(30, TimeUnit.SECONDS);
        long startedAt = System.nanoTime();
        go.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get(120, TimeUnit.SECONDS));
        }
        long totalMillis = (System.nanoTime() - startedAt) / 1_000_000;
        pool.shutdown();

        long ok = results.stream().filter("ok"::equals).count();
        log.info("[P4] 요청={} 성공={} 실패={} 전체={}ms 붙든시간={}ms",
                REQUESTS, ok, REQUESTS - ok, totalMillis, HOLD.toMillis());
        if (ok < REQUESTS) {
            log.info("[P4] 실패 내역 = {}", results.stream().filter(r -> !"ok".equals(r)).toList());
        }

        List<Long> arrivals = new ArrayList<>(behavior.unsubscribeArrivalNanos());
        if (arrivals.isEmpty()) {
            log.info("[P4] 🔴 서버에 도착한 요청이 0건이다. 아무것도 못 쟀다");
            return;
        }
        Collections.sort(arrivals);

        long base = arrivals.getFirst();
        log.info("[P4] 도착 오프셋 = {}", arrivals.stream()
                .map(at -> (at - base) / 1_000_000 + "ms")
                .collect(Collectors.joining(", ")));
        log.info("[P4] 도착={}건 뭉치={} (뭉치 경계 {}ms)",
                arrivals.size(), clusterSizes(arrivals), CLUSTER_GAP.toMillis());
        log.info("[P4] 서버가 관측한 프로토콜 = {}", new LinkedHashSet<>(behavior.unsubscribeProtocols()));

        log.info("[P4] 읽는 법 — 뭉치가 [{}] 하나면 겹치는 것, [5, 5, 5, 5]처럼 나뉘면 배치다",
                REQUESTS);
        log.info("[P4] 🔴 이것은 참고값이다. 실물 판정은 SessionLimitProbeTest의 세션 셋이 한다. "
                + "여기 프로토콜과 실물 프로토콜이 다르면 그 차이 자체를 CLAUDE.md에 남긴다");
    }

    /**
     * 정렬된 도착 시각을 {@link #CLUSTER_GAP}보다 큰 공백에서 끊어 뭉치 크기를 센다.
     * 20이 하나로 나오면 겹친 것, 여러 개로 갈리면 그 크기가 곧 한 번에 나가는 수다.
     *
     * <p><b>이 자가 정말 둘을 가르는지 재 봤다(2026-08-18).</b> 스레드 풀만 4로 줄여
     * 일부러 배치를 만들면 {@code [4, 4, 4, 4, 4]}·전체 1618ms가 나오고, 20으로
     * 되돌리면 {@code [20]}·전체 419ms가 나온다. 안 재 보면 이 함수는 <b>무엇이
     * 들어와도 [20]을 찍는 코드</b>와 구분되지 않는다 — 단언이 없는 프로브에서
     * 자를 검증하는 길은 이 양성 대조뿐이다.
     */
    private static List<Integer> clusterSizes(List<Long> sortedArrivals) {
        List<Integer> sizes = new ArrayList<>();
        int size = 1;
        for (int i = 1; i < sortedArrivals.size(); i++) {
            if (sortedArrivals.get(i) - sortedArrivals.get(i - 1) > CLUSTER_GAP.toNanos()) {
                sizes.add(size);
                size = 1;
            } else {
                size++;
            }
        }
        sizes.add(size);
        return sizes;
    }
}
