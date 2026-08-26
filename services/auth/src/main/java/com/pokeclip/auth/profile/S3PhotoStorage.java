package com.pokeclip.auth.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * S3 구현. <b>창고는 공개하지 않는다</b> — 어느 편집자가 어느 스트리머와 일하는지가 주소 하나로
 * 새면 안 되므로 파일을 auth가 직접 내보낸다(PRD). 그래서 여기에 ACL·공개 설정이 없다.
 *
 * <p>형식은 <b>우리가 판정한 값</b>을 못박아 넣는다. 올린 쪽이 밝힌 이름표를 그대로 실으면
 * 나중에 꺼내 내보낼 때 그 글자가 브라우저의 판단 기준이 된다.
 */
class S3PhotoStorage implements PhotoStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3PhotoStorage.class);

    private final S3Client s3;
    private final String bucket;

    S3PhotoStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(long userId, byte[] bytes, ImageType type) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(PhotoStorage.keyOf(userId))
                        .contentType(type.contentType())
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    /**
     * 없는 것은 예외가 아니라 빈손이다 — 그대로 흘리면 「아직 안 올렸다」가 500이 된다.
     *
     * <p><b>창고가 못 답하는 것도 빈손이다.</b> 이 경로는 로그인 없이 닿으므로(그림 태그가 부른다)
     * 창고 예외가 그대로 올라가면 <b>설정 하나가 틀린 배포에서 사진 요청이 전부 500</b>이 되고
     * 그 500은 아무나 만들 수 있다. {@code NoSuchBucketException}은 {@code NoSuchKeyException}의
     * 하위가 아니라 형제라 위 catch에 안 걸린다(LocalStack 실측 404 "The specified bucket does not exist").
     * 이름이 틀린 것뿐 아니라 자격증명 거부·연결 실패·시한 초과가 전부 같은 자리로 온다.
     *
     * <p><b>부재와 장애를 로그로 가른다.</b> 「아직 안 올렸다」까지 찍으면 사진을 올린 회원 수 ×
     * 폴링 빈도로 늘어나 진짜 신호가 묻힌다. 반대로 둘 다 조용하면 창고가 통째로 안 붙는 배포를
     * 아무도 모른다. <b>건수로 알람을 걸지 마라</b> — 한 줄이라도 뜨는 것 자체가 신호다
     * (PhotoUrls의 꺼진-창고 경고와 같은 원칙).
     *
     * <p><b>올리는 쪽({@link #put})은 삼키지 않는다.</b> 저장 실패를 빈손으로 바꾸면 사용자가
     * 「저장됐다」고 믿는다. 여기만 삼기는 이유는 최악이 「그림이 안 보인다」여서다.
     */
    @Override
    public Optional<StoredPhoto> get(long userId) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(PhotoStorage.keyOf(userId))
                    .build());
            return Optional.of(new StoredPhoto(object.asByteArray(), object.response().contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            // 값은 userId만 찍는다 — 이 서버의 로깅 규약이고 PhotoConfiguration이 켜짐 로그에서
            // 창고 이름을 일부러 뺀 것과 같은 결정이다. 원인은 예외 종류로 충분하다
            // (NoSuchBucketException이면 창고 이름, SdkClientException이면 그물).
            // 메시지 본문은 안 옮긴다 — 응답 본문이 거기 붙어 오는 경로가 있다.
            log.warn("auth.profile.photo.read_failed userId={} causeType={}",
                    userId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 없는 것을 지워도 S3는 성공으로 답한다 — 멱등이라 탈퇴(POK-171)가 재시도해도 된다. */
    @Override
    public void delete(long userId) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(PhotoStorage.keyOf(userId))
                .build());
    }

    /** 종료할 때 커넥션 풀을 닫는다. 스프링이 빈 파괴 시 close()를 찾아 부른다. */
    @Override
    public void close() {
        s3.close();
    }
}
