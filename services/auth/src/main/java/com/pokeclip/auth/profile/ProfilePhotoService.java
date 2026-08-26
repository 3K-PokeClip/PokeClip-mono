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
     * <p>🔴 <b>그 「최악」이 화면에서 어떻게 보이는지가 결함이었다</b>(PR #127 codex P1, 재현함).
     * 표 갱신이 실패하면 사용자는 오류를 받는데 <b>파일은 이미 바뀌어 있었다.</b> 옛 주소는
     * 안 깨지므로({@link PhotoToken#verify}가 버전 값을 안 본다) 그 주소를 들고 있던 화면이
     * <b>새 그림을 봤다</b> — 「실패했다는데 바뀌었다」로 보인다.
     *
     * <p><b>파일 자리를 둘로 갈라 닫았다</b>({@link PhotoStorage#keyOf}). 실패하면 표가 안 바뀌므로
     * 주소도 안 바뀌고, 그 주소는 <b>옛 자리</b>를 가리킨다. 새 파일은 반대 자리에 남지만 아무도
     * 안 가리키고 <b>다음 업로드가 그 자리를 다시 골라 덮어쓴다</b> — 청소 작업이 필요 없다.
     * 처음에는 「고아 청소가 딸려온다」를 이유로 미뤘는데, <b>자리를 둘로만 두면 그 대가가 없다.</b>
     */
    public User upload(long userId, MultipartFile file) {
        byte[] bytes = readAll(file);
        ImageType type = ImageType.of(bytes)
                .orElseThrow(() -> new ProfileUpdateException(
                        ProfileUpdateFailure.PHOTO_NOT_AN_IMAGE, "그림이 아니다"));

        long version = nextVersion(userId);
        storage.put(userId, version, bytes, type);
        User user = attacher.attach(userId, version);

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
     * <p>🔴 <b>「꺼짐 판정 → 표 검증 → 창고」 이 순서가 위 문단의 전제다.</b> 감사자가 갈래 일곱을
     * 라운드로빈으로 섞어 각 200회 재서 「표 없이 접근하는 쪽에게는 거절이 안 갈린다」를 확정했는데
     * (A·B·C p50 0.144/0.138/0.138ms — 같은 요청이 자리만 달라도 0.047ms 벌어지는 잡음 안이다),
     * 그 측정은 <b>표를 통과하기 전에는 창고에 안 간다</b>는 것에 통째로 기대고 있다.
     * <b>표 없이도 창고에 가는 갈래가 하나라도 생기면 그 판정이 즉시 무효가 된다</b> — 순서를 바꾸거나
     * 앞에 조회를 끼우지 마라.
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
        // 자리는 표가 아니라 <b>주소에 실린 버전</b>에서 얻는다 — 표를 읽으면 위 문단의 전제가 무너진다.
        return storage.get(userId, PhotoToken.versionOf(token));
    }

    /**
     * <b>지금 쓰는 자리의 반대를 고른다.</b> 버전의 홀짝이 자리를 정하는데(PhotoStorage.keyOf)
     * 버전은 시각이라 우연히 같은 자리가 될 수 있다 — 그러면 <b>덮어쓰게 되어 이 설계가 무의미해진다.</b>
     * 같으면 한 칸(1마이크로초) 밀어 반대로 만든다. 그 어긋남은 무해하다 — 이 값이 하는 일은
     * 자리를 정하는 것과 캐시를 비우는 것뿐이다.
     *
     * <p>사진을 올린 적이 없으면 아무 자리나 된다.
     */
    private long nextVersion(long userId) {
        long version = PhotoStorage.versionOf(Instant.now());
        Long current = attacher.currentVersion(userId);
        if (current != null && Math.floorMod(version, PhotoStorage.SLOTS)
                == Math.floorMod(current, PhotoStorage.SLOTS)) {
            version++;
        }
        return version;
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
