package com.pokeclip.chat.collector;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveBuffer;
import com.pokeclip.chat.collector.archive.ArchiveObject;
import com.pokeclip.chat.collector.archive.ChatArchiver;
import com.pokeclip.chat.collector.archive.JsonLinesEncoder;
import com.pokeclip.chat.collector.archive.MinuteBatcher;
import com.pokeclip.chat.collector.archive.MutableClock;
import com.pokeclip.chat.collector.archive.PendingUploads;
import com.pokeclip.chat.collector.archive.S3ArchiveUploader;
import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.pokeclip.chat.collector.ChatLogLeakTest.assertNoSecretsIn;
import static com.pokeclip.chat.collector.ChatLogLeakTest.levelOf;
import static com.pokeclip.chat.collector.ChatLogLeakTest.needle;
import static com.pokeclip.chat.collector.ChatLogLeakTest.renderAll;
import static com.pokeclip.chat.collector.ChatLogLeakTest.renderFully;
import static com.pokeclip.chat.collector.ChatLogLeakTest.setLevel;
import static com.pokeclip.chat.collector.support.LocalStackFixture.BUCKET;
import static com.pokeclip.chat.collector.support.LocalStackFixture.S3;
import static com.pokeclip.chat.collector.support.LocalStackFixture.freshS3Client;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아카이브 경로(POK-116)의 유출 검사 — 실제 S3 PUT까지 나가는 경로다: 우리 코드 + AWS SDK + Apache 5 전부를
 * 지난다. {@link ChatLogLeakTest}에서 떼어 냈다(/code-review 1라운드 K09) — 그쪽 검사가 LocalStack에 매이지 않게.
 * 탐지기(바늘·렌더·단언)는 그쪽 것을 그대로 쓴다 — 자기검사 셋이 거기 있다.
 *
 * <p>스프링은 {@code environment}(yml에 박힌 레벨을 읽는 두 겹 검사) 때문에만 필요하다 — 가짜 치지직도, 아카이브
 * 프로퍼티도 없다. 컨텍스트는 같은 애노테이션의 다른 검사(CollectorConfigTest 등)와 캐시를 공유한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ArchiveLogLeakTest extends IntegrationTestSupport {

    private static final String CONTENT = needle("chat-content");
    private static final String SENDER = needle("sender-channel-id");

    /** application.yml이 info로 박아 둔 AWS 쪽 로거 셋. */
    private static final List<String> PINNED_AWS_LOGGERS =
            List.of("software.amazon.awssdk", "org.apache.hc.client5.http", "io.netty");

    @Autowired Environment environment;

    /**
     * 아카이브 경로가 새로 연 로그 자리 — 틱·업로드·닫기 — 를 바늘이 지나가게 하고 무유출을 단언한다.
     */
    @Test
    void 아카이브_경로가_돌아도_raw와_본문이_로그에_안_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            long now = System.currentTimeMillis();     // 시계와 받은 시각을 같은 값으로 — 둘이 분 경계를 사이에 두면 창이 영영 안 닫힌다(F5)
            MutableClock clock = new MutableClock(now);
            ChatArchiver archiver = new ChatArchiver(new ArchiveBuffer(100), new MinuteBatcher("leak1", Duration.ofSeconds(2)),
                    new PendingUploads(10), new S3ArchiveUploader(S3, BUCKET),
                    new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), clock);
            String raw = "{\"senderChannelId\":\"" + SENDER + "\",\"content\":\"" + CONTENT + "\",\"messageTime\":1754300000000}";
            // tick()은 archive 패키지 안에서만 보인다(이 테스트는 collector 패키지) — 실제 스케줄러로 돌린다.
            archiver.start();
            archiver.offer(new ArchivableChat("leak-ch", now, raw));
            clock.advance(Duration.ofSeconds(62));
            awaitUntil(Duration.ofSeconds(5), () -> archiver.uploadedCount() == 1);
            assertThat(archiver.uploadedCount()).as("실제로 올라가야 유출 검사가 그 경로를 본 것이다").isEqualTo(1);
            archiver.beginClose();
            archiver.awaitClosed(Duration.ofSeconds(2));
            assertNoSecretsIn(captor, List.of(CONTENT, SENDER));
            assertThat(renderAll(captor)).doesNotContain("ArchivableChat[").doesNotContain("ArchiveObject[");
        }
    }

    /**
     * ChatLogLeakTest의 JDBC_파라미터_로거 검사와 같은 구조 — ① root TRACE로도 안 샌다 ② 로거를 직접 TRACE로 밀면
     * 새는지(양성 대조). application.yml이 AWS 쪽 로거 셋(SDK · Apache 5 · netty)을 info로 박은 근거가 이것이다 —
     * Apache 5의 wire 로거는 TRACE에서 요청 본문을 바이트째 찍는다.
     */
    @Test
    void AWS_로거는_root를_TRACE로_내려도_본문을_안_찍는다() throws Exception {
        for (String pinned : PINNED_AWS_LOGGERS) {
            String level = environment.getProperty("logging.level." + pinned);
            assertThat(level).as("application.yml에 " + pinned + " 레벨이 박혀 있어야 root를 내려도 버틴다").isNotNull();
            assertThat(Level.toLevel(level, Level.TRACE).toInt()).isGreaterThanOrEqualTo(Level.INFO.toInt());
        }
        // <b>클라이언트는 레벨을 올린 뒤에 새로 만든다.</b> Apache 5의 wire 로깅은 연결을 만드는 순간에 붙는다
        // (ManagedHttpClientConnectionFactory가 그때의 isDebugEnabled를 본다) — 풀에 이미 있는 연결로는 레벨을
        // 올려도 안 찍힌다. 첫 실행에서 ①의 연결을 ②가 재사용해 양성 대조가 이벤트 0개로 빨강이었다. 그래서 여기는
        // 공유 클라이언트(LocalStackFixture.S3)를 쓰지 않고 둘 다 새로 만든다.
        try (LogCaptor captor = new LogCaptor()) {
            Level rootBefore = levelOf(Logger.ROOT_LOGGER_NAME);
            setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
            try (S3Client fresh = freshS3Client()) {
                uploadOneWithNeedles(fresh, "root");
            } finally {
                setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
            }
            assertNoSecretsIn(captor, List.of(CONTENT, SENDER));

            // ② 양성 대조 — wire 로거를 직접 TRACE로 밀면 본문이 찍혀야 한다. 안 찍히면 yml에 박은
            // 근거(이 로거가 본문을 찍는다)가 사라진 것이니 다시 볼 때다.
            String wire = "org.apache.hc.client5.http.wire";
            Level before = levelOf(wire);
            setLevel(wire, Level.TRACE);
            try (S3Client fresh = freshS3Client()) {
                uploadOneWithNeedles(fresh, "wire");
            } finally {
                setLevel(wire, before);
            }
            assertThat(captor.events())
                    .as(wire + "를 TRACE로 밀어도 본문이 안 새면 yml에 박은 근거를 다시 볼 때다")
                    .anyMatch(e -> e.getLoggerName().startsWith(wire) && renderFully(e).contains(CONTENT));
        }
    }

    private static void uploadOneWithNeedles(S3Client s3, String tag) throws Exception {
        byte[] body = JsonLinesEncoder.encodeLine(new ArchivableChat("leak-ch", 1L,
                "{\"senderChannelId\":\"" + SENDER + "\",\"content\":\"" + CONTENT + "\"}"));
        new S3ArchiveUploader(s3, BUCKET).upload(new ArchiveObject("chat/leak/" + tag + "-" + UUID.randomUUID() + ".jsonl", body, 1));
    }
}
