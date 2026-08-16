package com.pokeclip.chat.collector.support;

import com.pokeclip.chat.collector.archive.ArchiveProperties;
import com.pokeclip.chat.collector.archive.S3Clients;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * 가짜 S3(LocalStack)를 static 블록에서 한 번만 띄우는 <b>정적 픽스처</b> — 상속하지 않고 {@code import static}으로
 * 쓴다. 자격증명은 <b>SDK 표준 체인의 시스템 프로퍼티 자리</b>에 LocalStack이 준 더미 값을 넣는다 — 운영 코드에는
 * 키를 받는 자리가 없다.
 *
 * <p>상속형(IntegrationTestSupport를 잇는 LocalStackSupport)이었다가 정적으로 바꿨다(/code-review 1라운드 K09·K14·K28):
 * ① {@code @DynamicPropertySource}로 archive 프로퍼티를 켜 두면 스프링 컨텍스트가 하나 더 뜨고 그 안의 진짜
 * ChatArchiver 빈이 빈 바구니를 매 초 퍼갈 뿐 아무 검사도 그 빈을 안 봤다 ② PG가 필요 없는 S3 단위 검사까지 PG를
 * 띄웠다 ③ 도우미마다 S3Client를 새로 만들었다. 지금은 프로퍼티 주입이 없고(스프링 배선은 {@code ArchiveWiringTest}가
 * LocalStack 없이 잰다), 대조용 클라이언트는 하나를 공유한다.
 *
 * <p>이미지 태그는 <b>4.14.0</b>으로 고정한다 — 커뮤니티(무료) 이미지의 마지막 SemVer다.
 * CalVer {@code 2026.x}는 통합(유료 인증) 이미지라 {@code LOCALSTACK_AUTH_TOKEN} 없이는 즉시 종료한다
 * (plan-critic 실측 2026-08-16: "No credentials were found … LocalStack has quit"). 4.14.0은
 * Testcontainers 준비 대기까지 2.0~2.4초(3회).
 */
public final class LocalStackFixture {

    public static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.14.0").withServices("s3");
    public static final String BUCKET = "pokeclip-chat-test";

    /**
     * 대조용 클라이언트 <b>하나</b> — 운영과 같은 팩토리({@link S3Clients#create}, 시한까지 같다). 닫지 않는다:
     * 컨테이너가 JVM 끝까지 살고 이 클라이언트도 그만큼 산다 — 검사마다 열고 닫으면 커넥션 풀·리퍼 스레드를 그만큼
     * 만든다. 새 연결이 필요하면(wire 로깅은 연결 생성 시점에 붙는다) {@link #freshS3Client()}, 반개방 시한을 잰다면
     * {@link #stallingProxy()} + {@link #s3ClientVia(StallingTcpProxy)}로 따로 만든다.
     */
    public static final S3Client S3;

    static {
        LOCALSTACK.start();
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        S3 = freshS3Client();
        createBucketWithRetry();
    }

    private LocalStackFixture() { }

    /** LocalStack 기동 직후 첫 요청이 401로 튄 적이 있다(plan-critic 1/4회, 재현 못 함) — 3회·500ms 재시도. */
    private static void createBucketWithRetry() {
        RuntimeException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                S3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
                return;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new IllegalStateException("LocalStack 버킷 생성 3회 실패", last);
    }

    /** 이 버킷·리전·path-style로, 엔드포인트만 바꿔서(프록시 등). */
    public static ArchiveProperties propertiesFor(String endpoint) {
        return new ArchiveProperties(BUCKET, LOCALSTACK.getRegion(), endpoint, true, 60, 10_000);
    }

    /** LocalStack에 직접 닿는 설정 — 운영 조립(ArchiveConfiguration)에 그대로 넘긴다. */
    public static ArchiveProperties localStackProperties() {
        return propertiesFor(LOCALSTACK.getEndpoint().toString());
    }

    /** LocalStack에 직접 닿는 <b>새</b> 클라이언트 — 부른 쪽이 닫는다. */
    public static S3Client freshS3Client() {
        return S3Clients.create(localStackProperties());
    }

    /** LocalStack 앞에 세우는 반개방 중계기 — 응답을 삼켜 시한을 재는 검사용. 부른 쪽이 닫는다. */
    public static StallingTcpProxy stallingProxy() throws IOException {
        URI real = LOCALSTACK.getEndpoint();
        return new StallingTcpProxy(real.getHost(), real.getPort());
    }

    /** 그 중계기를 거쳐 LocalStack에 닿는 새 클라이언트 — 부른 쪽이 닫는다. */
    public static S3Client s3ClientVia(StallingTcpProxy proxy) {
        return S3Clients.create(propertiesFor("http://" + LOCALSTACK.getEndpoint().getHost() + ":" + proxy.port()));
    }

    public static byte[] download(String key) {
        return S3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build()).asByteArray();
    }

    public static List<String> listKeys(String prefix) {
        return S3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build())
                .contents().stream().map(S3Object::key).toList();
    }
}
