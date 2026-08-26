package com.pokeclip.clip.support;

import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 가짜 SQS(LocalStack)를 static 블록에서 한 번만 띄우는 <b>정적 픽스처</b> —
 * 상속하지 않고 이름으로 부른다(chat-collector의 LocalStackFixture와 같은 모양).
 *
 * <p>이미지 태그는 <b>4.14.0</b>으로 고정한다 — 커뮤니티(무료) 이미지의 마지막
 * SemVer다. CalVer {@code 2026.x}는 통합(유료 인증) 이미지라
 * {@code LOCALSTACK_AUTH_TOKEN} 없이는 즉시 종료한다
 * ("No credentials were found … LocalStack has quit", 2026-08-16 실측).
 *
 * <p>자격증명은 SDK 표준 체인의 시스템 프로퍼티 자리에 LocalStack이 준 더미 값을
 * 넣는다 — 운영 코드에는 키를 받는 자리가 없다.
 */
public final class LocalStackFixture {

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.14.0").withServices("sqs");

    private static final SqsClient SQS;

    static {
        LOCALSTACK.start();
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        // httpClient를 여기서도 명시한다. sqs가 apache5-client를 runtime으로 딸려오므로
        // SPI에 후보가 둘이고, 안 박으면 어느 쪽이 쓰일지 클래스패스 순서에 달린다.
        SQS = SqsClient.builder()
                .region(Region.of(LOCALSTACK.getRegion()))
                .endpointOverride(LOCALSTACK.getEndpoint())
                .httpClient(UrlConnectionHttpClient.builder()
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }

    private LocalStackFixture() {
    }

    /** 대조·준비용 클라이언트 하나. 컨테이너가 JVM 끝까지 살고 이것도 그만큼 산다. */
    public static SqsClient client() {
        return SQS;
    }

    public static String region() {
        return LOCALSTACK.getRegion();
    }

    public static String endpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    /** 이름이 .fifo로 끝나야 한다 — 아니면 InvalidParameterValue로 거부된다. */
    public static String createFifoQueue(String name) {
        return SQS.createQueue(CreateQueueRequest.builder()
                .queueName(name)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                .build()).queueUrl();
    }

    /** 그룹·중복제거 ID가 <b>필수다</b> — 빠지면 SQS가 거부한다. 값은 ADR-016이 정한 것. */
    public static void sendStarted(String queueUrl, String eventId, String streamId, long sequence) {
        SQS.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(startedJson(eventId, streamId, sequence))
                .messageGroupId(streamId)          // ADR-016: 같은 방송의 순서를 지킨다
                .messageDeduplicationId(eventId)   // ADR-016: 재시도가 같은 편지임을 알린다
                .build());
    }

    /** 종류만 다른 봉투. 러너가 모르는 종류를 어떻게 다루는지 재는 데 쓴다. */
    public static void sendRaw(String queueUrl, String body, String groupId, String dedupId) {
        SQS.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .messageGroupId(groupId)
                .messageDeduplicationId(dedupId)
                .build());
    }

    /**
     * 큐에 남은 편지 수 — <b>보이는 것과 처리 중인 것을 더한다.</b>
     *
     * <p>{@code ApproximateNumberOfMessages}만 세면 안 된다. 러너가 안 지운 편지는
     * 가시성 타임아웃(기본 30초)이 지나기 전까지 <b>보이지 않는 상태</b>로 큐에 남는데,
     * 그 값만 읽으면 0이라 "지웠다"와 구분이 안 된다 — 삭제를 통째로 지워도 초록인
     * 시험이 된다. 둘을 더해야 "큐에서 사라졌다"를 실제로 잰다.
     */
    public static int approximateMessageCount(String queueUrl) {
        Map<QueueAttributeName, String> attributes = SQS.getQueueAttributes(
                        GetQueueAttributesRequest.builder()
                                .queueUrl(queueUrl)
                                .attributeNames(List.of(
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                                .build())
                .attributes();
        return Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Integer.parseInt(
                        attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    public static String startedJson(String eventId, String streamId, long sequence) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.started",
                 "occurredAt":"2026-08-18T00:00:00Z","streamId":"%s","streamerId":"%s",
                 "sequence":%d,"traceId":"trace-1","payload":{"trackManifest":{"manifestVersion":3}}}
                """.formatted(eventId, streamId, TestIds.STREAMER, sequence);
    }
}
