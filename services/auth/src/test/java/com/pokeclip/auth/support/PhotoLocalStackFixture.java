package com.pokeclip.auth.support;

import com.pokeclip.auth.profile.PhotoProperties;
import com.pokeclip.auth.profile.PhotoS3Clients;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Optional;

/**
 * 가짜 S3(LocalStack)를 static 블록에서 한 번만 띄우는 <b>정적 픽스처</b> — 상속하지 않고 이름으로 부른다
 * (chat-collector의 LocalStackFixture와 같은 모양). 자격증명은 <b>SDK 표준 체인의 시스템 프로퍼티 자리</b>에
 * LocalStack이 준 더미 값을 넣는다 — 운영 코드에는 키를 받는 자리가 없다.
 *
 * <p>이미지 태그는 <b>4.14.0</b>으로 고정한다 — 커뮤니티(무료) 이미지의 마지막 SemVer다.
 * CalVer {@code 2026.x}는 통합(유료 인증) 이미지라 {@code LOCALSTACK_AUTH_TOKEN} 없이는 즉시 종료한다
 * ("No credentials were found … LocalStack has quit", 2026-08-16 실측).
 *
 * <p><b>파일 이름을 여기서 다시 짓는다</b> — {@code PhotoStorage.keyOf}를 부르지 않는다. 운영 코드가
 * 이름 짓는 법을 바꾸면 이 픽스처로 꺼내는 검사가 빨간불이어야 한다. 같은 함수를 쓰면 둘이 함께
 * 움직여 「덮어쓴다」가 아무것도 안 재게 된다.
 */
public final class PhotoLocalStackFixture {

    public static final String BUCKET = "pokeclip-auth-photo-test";

    /** 로그인 토큰과 <b>다른 값</b>이어야 한다 — PhotoToken이 그 분리에 기대고 있다. */
    public static final String TOKEN_SECRET = "test-only-photo-secret-32bytes-long!!";

    /** 사진 주소의 앞부분. 회원 정보가 <b>절대 주소</b>를 준다는 것을 재려면 검사가 이 값을 알아야 한다. */
    public static final String BASE_URL = "http://localhost:8082";

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.14.0").withServices("s3");

    private static final S3Client S3;

    static {
        LOCALSTACK.start();
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        S3 = PhotoS3Clients.create(properties());
        createBucketWithRetry();
    }

    private PhotoLocalStackFixture() { }

    /**
     * 사진을 켠 컨텍스트가 필요한 곳이 둘이다(MockMvc 쪽 PhotoTestSupport · 톰캣 쪽 크기 상한 검사).
     * <b>한 자리에서만 등록하면 다른 쪽이 자리표시로 뜬다</b> — IntegrationTestSupport와
     * RealServerTestSupport가 유튜브 주소로 이미 겪은 쌍둥이 자리라 여기 한 곳에 모은다.
     */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.profile-photo.bucket", () -> BUCKET);
        registry.add("pokeclip.profile-photo.region", PhotoLocalStackFixture::region);
        registry.add("pokeclip.profile-photo.endpoint", PhotoLocalStackFixture::endpoint);
        // LocalStack은 가상 호스트 이름을 못 푼다 — path-style이 아니면 붙지 못한다.
        registry.add("pokeclip.profile-photo.force-path-style", () -> "true");
        registry.add("pokeclip.profile-photo.token-secret", () -> TOKEN_SECRET);
        registry.add("pokeclip.profile-photo.base-url", () -> BASE_URL);
    }

    public static String region() {
        return LOCALSTACK.getRegion();
    }

    public static String endpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    /**
     * 창고에 실제로 들어갔는지 우리 코드를 안 거치고 확인한다. 없으면 빈손이다.
     *
     * <p><b>자리 번호를 받는다</b> — 파일 이름이 회원마다 하나가 아니라 <b>둘을 번갈아 쓴다</b>
     * (버전의 홀짝). 표 갱신이 실패했을 때 옛 주소가 새 그림을 주지 않게 하려는 것이고,
     * 확인하는 쪽도 어느 자리인지 말해야 한다.
     */
    public static Optional<byte[]> downloadPhoto(long userId, int slot) {
        try {
            return Optional.of(S3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(BUCKET).key("profile-photos/" + userId + "/" + slot).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /** 자리 둘 중 아무 데나 있으면 그것. 「저장이 됐나/안 됐나」만 볼 때 쓴다. */
    public static Optional<byte[]> downloadAnyPhoto(long userId) {
        return downloadPhoto(userId, 0).or(() -> downloadPhoto(userId, 1));
    }

    private static PhotoProperties properties() {
        return new PhotoProperties(BUCKET, LOCALSTACK.getRegion(), LOCALSTACK.getEndpoint().toString(),
                true, TOKEN_SECRET, BASE_URL);
    }

    /** LocalStack 기동 직후 첫 요청이 401로 튄 적이 있다(선례 1/4회, 재현 못 함) — 3회·500ms 재시도. */
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
}
