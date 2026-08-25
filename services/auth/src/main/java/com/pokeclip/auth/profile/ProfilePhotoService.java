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

@Service
@RequiredArgsConstructor
public class ProfilePhotoService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    private final PhotoStorage storage;
    private final PhotoAttacher attacher;

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
