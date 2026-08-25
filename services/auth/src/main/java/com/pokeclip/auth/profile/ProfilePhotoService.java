package com.pokeclip.auth.profile;

import com.pokeclip.auth.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProfilePhotoService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    private final PhotoStorage storage;
    private final PhotoAttacher attacher;
    private final PhotoProperties properties;

    /**
     * <b>창고 먼저, 표 나중이다.</b> 뒤집으면 표는 새 사진을 가리키는데 파일이 없는 상태가 생긴다.
     * 이 순서면 최악이 「파일만 새것, 주소는 잠시 옛것」이고 다음 시도에 맞춰진다.
     *
     * <p><b>창고 호출은 트랜잭션 밖이다.</b> 표 갱신만 {@link PhotoAttacher}가 트랜잭션 안에서 한다 —
     * DB 커넥션을 쥔 채 외부 HTTP를 기다리면 풀이 마른다(「알려진 구멍」 9·10번).
     *
     * <p><b>형식은 내용의 앞머리로 가른다</b> — 밝힌 이름표를 믿지 않는다. 판정이 저장보다 먼저라
     * 거부한 파일은 창고에 닿지도 않는다.
     */
    public User upload(long userId, MultipartFile file) {
        byte[] bytes = readAll(file);
        ImageType type = ImageType.of(bytes)
                .orElseThrow(() -> new ProfileUpdateException(
                        ProfileUpdateFailure.PHOTO_NOT_AN_IMAGE, "그림이 아니다"));

        storage.put(userId, bytes, type);
        User user = attacher.attach(userId, Instant.now());

        // 커밋이 끝난 뒤라 이 줄은 「실제로 일어났다」를 뜻한다. 값은 userId만 — 파일 이름·형식은
        // 사람을 특정하지 않지만 남길 이유도 없다.
        log.info("auth.profile.photo.uploaded userId={}", userId);
        return user;
    }

    /**
     * <b>빈손 하나로 거절 넷을 뭉친다.</b> 표가 틀렸든 만료됐든 남의 번호든 그런 사진이 없든
     * 그런 회원이 없든 — 부르는 쪽이 받는 것은 같은 {@code Optional.empty()}다. 갈라 주면
     * 「그 회원이 사진을 올렸는가」가 표 없이도 새어 나가고, 사진을 비공개로 둔 이유
     * (편집자 프라이버시)가 그 자리에서 무너진다.
     *
     * <p><b>표(表)를 읽지 않는다.</b> 「이 회원이 사진을 올렸나」를 DB로 먼저 물어 거르면
     * 「안 올린 회원」은 창고에 안 가고 「올렸는데 파일이 없는 회원」은 창고에 가서, 본문이 같아도
     * <b>걸리는 시간이 갈린다</b>(POK-117에서 404 두 갈래가 1.5ms 대 4.4ms로 갈렸고 한 번만 재도
     * 구분됐다). 창고에 바로 묻는 지금 모양은 그 둘이 같은 길을 간다.
     *
     * <p>꺼짐 판정만 앞에 둔다 — 회원별 값이 아니라 서버 전체의 설정이라 갈래를 만들지 않는다.
     * 이 줄이 없으면 창고가 꺼진 배포에서 서명키가 빈 문자열이고, {@code SecretKeySpec}이 던지는
     * {@code IllegalArgumentException}이 <b>아무나 부를 수 있는 이 경로의 500</b>이 된다.
     *
     * <p>여기에 {@code @Transactional}이 없는 것도 의도다 — 창고 호출은 외부 HTTP다.
     */
    public Optional<StoredPhoto> read(long userId, String token) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        if (!PhotoToken.verify(properties.tokenSecret(), token, userId, Instant.now())) {
            return Optional.empty();
        }
        return storage.get(userId);
    }

    /**
     * 크기는 서블릿 층이 이미 잘랐다(spring.servlet.multipart.max-file-size) — 여기 오는 것은
     * 2MB 이하다. 읽기 자체가 실패하는 것은 우리 쪽 디스크·임시파일 문제라 사용자가 고칠 수 없다:
     * 사유 코드로 바꾸지 않고 500으로 보낸다.
     */
    private static byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("올라온 파일을 읽지 못했다", e);
        }
    }
}
