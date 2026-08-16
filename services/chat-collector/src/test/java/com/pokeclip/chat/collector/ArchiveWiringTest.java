package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveProperties;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.archive.ChatArchiver;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스프링 배선 — 창고 이름이 있으면 {@code pokeclip.chzzk.archive.*} 키가 {@link ArchiveProperties}에 <b>그대로 바인딩</b>되고
 * {@link ChatArchive} 빈이 <b>진짜 {@link ChatArchiver}</b>이며 <b>이 빈의 틱이 돈다</b>(offer 한 건이 archived로 센다).
 * S3에 닿을 필요가 없어 LocalStack을 안 띄운다 — 엔드포인트는 죽은 포트({@code 127.0.0.1:1})다. 클라이언트는 만들 때
 * 접속하지 않는다. offer한 채팅의 창이 닫히면(분 경계에 걸리면 검사 도중에도, 아니면 아래 컨텍스트 파괴 때) 그 포트로
 * PUT이 <b>실제로 나가지만</b> RST로 즉시 거부되고(3~19ms 실측) 단언과는 무관하다 — 여기서 재는 것은 바인딩 값과
 * archived뿐이다.
 *
 * <p>바인딩 값 넷은 기본값이 아닌 값으로 넣어 잰다 — 키 이름이 어긋나면 기본값이 조용히 남고 endpoint가 비면 진짜 AWS로
 * 간다(S3Clients). {@code @DirtiesContext(AFTER_CLASS)}가 컨텍스트를 버리면 러너 빈의 {@code @PreDestroy stop()}이 closeSinks로
 * 아카이버를 닫는다 — 캐시에 남는 컨텍스트의 빈을 여기서 영구히 닫아 두는 순서 의존을 안 만든다.
 */
@SpringBootTest(properties = {
        "pokeclip.chzzk.archive.bucket=wiring-test",
        "pokeclip.chzzk.archive.endpoint=http://127.0.0.1:1",
        "pokeclip.chzzk.archive.force-path-style=true",
        "pokeclip.chzzk.archive.pending-max=7",
        "pokeclip.chzzk.archive.buffer-capacity=123"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArchiveWiringTest extends IntegrationTestSupport {

    @Autowired ArchiveProperties properties;
    @Autowired ChatArchive archive;

    @Test
    void 프로퍼티_키_넷이_그대로_바인딩된다() {
        assertThat(properties.endpoint()).isEqualTo("http://127.0.0.1:1");
        assertThat(properties.forcePathStyle()).isTrue();
        assertThat(properties.pendingMax()).isEqualTo(7);
        assertThat(properties.bufferCapacity()).isEqualTo(123);
    }

    @Test
    void 창고_이름이_있으면_ChatArchive_빈은_진짜_아카이버이고_그_틱이_돈다() throws Exception {
        assertThat(archive).isInstanceOf(ChatArchiver.class);
        assertThat(archive.counters().runId()).as("UUID 앞 8자 hex").matches("[0-9a-f]{8}");
        archive.offer(new ArchivableChat("wiring", System.currentTimeMillis(), "{}"));
        awaitUntil(Duration.ofSeconds(5), () -> archive.counters().archivedCount() == 1);
        assertThat(archive.counters().archivedCount()).as("start()가 안 불렸으면 바구니에서 창으로 아무것도 안 간다").isEqualTo(1);
    }
}
