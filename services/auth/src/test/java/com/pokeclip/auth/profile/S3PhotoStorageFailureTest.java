package com.pokeclip.auth.profile;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 창고가 못 답할 때 <b>사진을 내보내는 쪽</b>이 어떻게 되는지만 본다.
 *
 * <p>이 경로는 로그인 없이 닿는다({@code permitAll}). 창고 예외를 그대로 흘리면 <b>설정 하나가
 * 틀린 배포에서 사진 요청이 전부 500</b>이 되고, 그 500은 아무나 만들 수 있다.
 * 같은 카드의 감사 2라운드가 「창고가 <b>꺼진</b> 배포」에 대해 같은 모양을 이미 한 번 닫았는데
 * (그래서 {@code ProfilePhotoService.read}가 꺼짐 판정을 먼저 한다), 그 처방은
 * 「이름이 비었을 때」만 덮고 <b>「이름이 틀렸을 때」는 안 덮었다</b>.
 *
 * <p><b>부재와 장애를 가르는 것이 이 검사의 핵심이다.</b> 둘을 뭉쳐 빈손으로만 만들면
 * 「아직 안 올렸다」가 매번 WARN을 찍어 진짜 신호가 묻히고, 반대로 둘 다 조용하면
 * 창고가 통째로 안 붙는 배포를 아무도 모른다. 그래서 갈래 둘을 나란히 잰다.
 */
class S3PhotoStorageFailureTest {

    private static final String EVENT = "auth.profile.photo.read_failed";

    /**
     * <b>표 서명키와 주소 앞부분은 이 검사와 무관하다</b> — 창고를 직접 부르므로 표를 만들지도
     * 주소를 짓지도 않는다. 값을 넣는 이유는 창고 이름이 차 있으면 {@link PhotoProperties}가
     * 둘을 요구하기 때문뿐이라, <b>픽스처의 상수를 빌려오지 않고 여기서 못박는다</b> —
     * 빌려오면 이 검사가 안 쓰는 값에 매여 그 상수가 생기기 전 커밋에서 컴파일되지 않는다.
     */
    private static PhotoProperties propertiesFor(String bucket) {
        return new PhotoProperties(bucket, PhotoLocalStackFixture.region(),
                PhotoLocalStackFixture.endpoint(), true,
                "unused-by-this-test", "http://unused.invalid");
    }

    /**
     * {@code NoSuchBucketException}은 {@code NoSuchKeyException}의 하위가 아니다 — 둘 다
     * {@code S3Exception}에서 갈라진 형제다. 실물로 확인했다(LocalStack, 404
     * "The specified bucket does not exist").
     */
    @Test
    void 창고_이름이_틀리면_500이_아니라_빈손이다() {
        String missing = "pokeclip-auth-photo-does-not-exist";
        try (S3Client s3 = PhotoS3Clients.create(propertiesFor(missing));
             LogCaptor logs = new LogCaptor()) {
            S3PhotoStorage storage = new S3PhotoStorage(s3, missing);

            assertThat(storage.get(777L, 0L))
                    .as("창고 예외가 그대로 올라가면 로그인 없이 닿는 경로가 500이 된다")
                    .isEmpty();

            assertThat(logs.messages())
                    .as("빈손을 조용히 내면 창고가 안 붙는 배포를 아무도 모른다")
                    .anyMatch(line -> line.startsWith(EVENT));
            assertThat(logs.levelOf(EVENT))
                    .as("한 줄이라도 뜨는 것 자체가 신호다 — 건수로 알람을 걸 값이 아니다")
                    .isEqualTo(Level.WARN);
        }
    }

    /** 「아직 안 올렸다」는 정상이다. 여기서 WARN을 찍으면 회원 수 × 폴링 빈도로 늘어나 신호가 묻힌다. */
    @Test
    void 그런_사진이_없는_것은_장애가_아니라_조용한_빈손이다() {
        try (S3Client s3 = PhotoS3Clients.create(propertiesFor(PhotoLocalStackFixture.BUCKET));
             LogCaptor logs = new LogCaptor()) {
            S3PhotoStorage storage = new S3PhotoStorage(s3, PhotoLocalStackFixture.BUCKET);

            assertThat(storage.get(999_777L, 0L))
                    .as("올린 적 없는 회원 — 예외가 아니라 빈손이다")
                    .isEmpty();

            assertThat(logs.messages())
                    .as("부재까지 장애로 찍으면 진짜 장애가 그 안에 묻힌다")
                    .noneMatch(line -> line.startsWith(EVENT));
        }
    }
}
