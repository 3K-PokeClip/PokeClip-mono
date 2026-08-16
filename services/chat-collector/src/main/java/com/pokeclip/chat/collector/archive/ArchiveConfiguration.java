package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class ArchiveConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ArchiveConfiguration.class);
    /** 창 닫기 유예 — 틱 1초 + 수신→drain 지연 여유. */
    static final Duration CLOSE_GRACE = Duration.ofSeconds(2);
    /** 업로드 백오프 — 재연결·Flyway 재시도와 같은 모양(1초 → 두 배 → 60초). */
    static final Duration BACKOFF_FIRST = Duration.ofSeconds(1);
    static final Duration BACKOFF_MAX = Duration.ofSeconds(60);

    /**
     * bucket이 비면 <b>아무 부품도 만들지 않는다</b> — @ConditionalOnProperty로는 못 가른다(빈 문자열도
     * "값이 있음"으로 매치된다). 러너는 켜짐/꺼짐을 모르고 ChatArchive만 본다.
     * destroyMethod를 끈다 — 닫기는 러너의 stop()이 persister와 나란히 한다(순서가 거기 있다).
     * 스프링 파괴가 또 부르면 완료-대기 멱등이라 무해하지만, 굳이 두 번 부를 이유가 없다.
     */
    @Bean(destroyMethod = "")
    public ChatArchive chatArchive(ArchiveProperties p) {
        if (!p.enabled()) {
            log.info("chat.archive.disabled reason=no_bucket");   // key=value ASCII — 기존 로그 관례
            return ChatArchive.NONE;
        }
        String runId = UUID.randomUUID().toString().substring(0, 8);
        S3Client s3 = S3Clients.create(p);
        ChatArchiver archiver = new ChatArchiver(
                new ArchiveBuffer(p.bufferCapacity()),
                new MinuteBatcher(runId, CLOSE_GRACE),
                new PendingUploads(p.pendingMax()),
                new S3ArchiveUploader(s3, p.bucket()),
                new ReconnectPolicy(BACKOFF_FIRST, BACKOFF_MAX),
                System::currentTimeMillis);
        archiver.start();
        // 버킷 이름은 안 찍는다 — 운영 식별자라 비밀은 아니지만 필요 없다.
        log.info("chat.archive.enabled runId={} pendingMax={} bufferCapacity={} endpointOverride={}",
                runId, p.pendingMax(), p.bufferCapacity(), p.hasEndpoint());
        return archiver;
    }
}
