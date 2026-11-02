package com.pokeclip.chat.collector.soop;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.ChatLogLeakTest;
import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.platforms.PlatformNotifier;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import static com.pokeclip.chat.collector.soop.FakeSoopHttp.granted;
import static com.pokeclip.chat.collector.soop.FakeSoopHttp.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOOP 토큰과 SOOP 아이디가 로그에 안 남는다(계획 검증 L11). 실제 클라이언트 둘(가짜 auth·가짜 SOOP)을 지켜보기에 물려 세 길을 지난다:
 * 연동 조회 · 상태 확인 · 거부 뒤 다시 조회. 진단 스위치도 켠다(키 이름·status만 남겨야 한다). 루트를 TRACE로 내려 예외 사슬까지 편다.
 *
 * <p>🔴 {@code org.springframework.web}은 운영 yml처럼 INFO로 묶는다. 그 로거는 DEBUG에서 나가는 form 본문({@code access_token=…})을 통째로 찍는데,
 * 운영은 yml이 그 로거를 INFO로 박아 막는다(root를 TRACE로 내려도 안 뚫린다). 이 시험은 스프링 없이 돌아 yml이 안 먹으므로 같은 방어선을
 * 손으로 건다. 묶지 않으면 앞서 뜬 컨텍스트에 따라 결과가 갈렸다(전체 시험에서만 빨간불).
 */
class SoopSecretLeakTest {

    private static final String OLD_TOKEN = "soop-old-access-7f3a";
    private static final String NEW_TOKEN = "soop-new-access-91bc";
    private static final String SOOP_ID = "soopleakid77";
    private static final String TITLE = "제목-누설-확인";

    private FakeSoopHttp fake;

    @BeforeEach
    void start() {
        fake = FakeSoopHttp.start();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void 연동_조회와_상태_확인과_거부_재조회에서_토큰과_아이디가_안_샌다() {
        String expires = Instant.now().plusSeconds(8 * 3600).toString();
        fake.resolveAnswers(ok(granted(SOOP_ID, OLD_TOKEN, expires)), ok(granted(SOOP_ID, NEW_TOKEN, expires)),
                new FakeSoopHttp.Response(500, "{\"accessToken\":\"" + NEW_TOKEN + "\"}"));
        fake.statusAnswers(ok("{\"result\":-1,\"msg\":\"" + OLD_TOKEN + "\"}"),
                ok("{\"result\":1,\"data\":{\"status\":\"streaming\",\"user_id\":\"" + SOOP_ID + "\",\"broad_title\":\"" + TITLE
                        + "\",\"current_view_cnt\":3}}"),
                new FakeSoopHttp.Response(401, "{\"token\":\"" + NEW_TOKEN + "\"}"));
        RestClient.Builder jdk = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory());
        SoopLinkClient links = new SoopLinkClient(jdk, new LinkProperties(fake.baseUrl(), "internal-token"));
        SoopLiveClient live = new SoopLiveClient(jdk, fake.baseUrl(), true);
        SoopBroadcastWatcher watcher = new SoopBroadcastWatcher(links::resolve, live::check, info -> { }, RelaySink.NONE,
                new PlatformNotifier() {
                    @Override
                    public void seen(String streamId, Platform platform, Instant seenAt) {
                    }

                    @Override
                    public void retain(Platform platform, Set<String> streamIds) {
                    }
                }, Set::of, 10, Clock.systemUTC());

        ch.qos.logback.classic.Logger springWeb =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework.web");
        Level springWebBefore = springWeb.getLevel();
        String logs;
        try (LogCaptor captor = new LogCaptor()) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.TRACE);
            springWeb.setLevel(Level.INFO);
            watcher.watch("s-leak", 42L, Instant.now());
            watcher.tick();   // 옛 토큰 거부 → 다시 조회 → 새 토큰으로 방송 중
            watcher.tick();   // 401 거부 → 다시 조회가 500 → 다음 틱으로
            logs = ChatLogLeakTest.renderAll(captor);
        } finally {
            springWeb.setLevel(springWebBefore);
        }

        assertThat(fake.requests(FakeSoopHttp.STATUS)).as("세 길을 다 지났다").hasSize(3);
        assertThat(fake.requests(FakeSoopHttp.RESOLVE)).hasSize(3);
        assertThat(logs).contains("soop.live.diagnose").contains("soop.link.resolved");
        assertThat(logs).doesNotContain(OLD_TOKEN).doesNotContain(NEW_TOKEN).doesNotContain(SOOP_ID).doesNotContain(TITLE);
    }
}
