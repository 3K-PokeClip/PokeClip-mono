package com.pokeclip.auth.profile;

import java.time.Instant;
import java.util.Optional;

/**
 * 사진 창고. 꺼졌을 때는 {@link #NONE}이 자리를 지킨다 — 부르는 쪽이 켜짐/꺼짐을 몰라도 된다.
 * SecretStore와 같은 계열의 포트다: 지금 구현이 S3이고 운영에서 다른 창고로 갈아탈 수 있다.
 *
 * <p><b>이 인터페이스 뒤는 전부 외부 HTTP다.</b> 부르는 쪽은 트랜잭션 밖에서 부른다 —
 * DB 커넥션을 쥔 채 최대 8초를 기다리면 풀이 마른다(「알려진 구멍」 9·10번).
 */
public interface PhotoStorage {

    /**
     * 창고가 없을 때의 자리. {@code @ConditionalOnProperty}로는 「비었음」을 못 가르므로
     * (빈 문자열도 「값이 있음」으로 매치된다) 조립부가 코드로 갈라 이것을 넣는다.
     */
    PhotoStorage NONE = new PhotoStorage() {
        @Override
        public void put(long userId, long version, byte[] bytes, ImageType type) {
            throw new ProfileUpdateException(ProfileUpdateFailure.PHOTO_STORAGE_DISABLED, "사진 창고가 꺼져 있다");
        }

        @Override
        public Optional<StoredPhoto> get(long userId, long version) {
            return Optional.empty();
        }

        @Override
        public void deleteAll(long userId) {
        }
    };

    /** 자리가 둘뿐이라는 것을 부르는 쪽이 셀 수 있어야 한다(탈퇴가 둘 다 지운다). */
    int SLOTS = 2;

    /**
     * 사진 주소와 파일 이름이 <b>함께 쓰는 값</b>. 한 곳에서 계산해야 둘이 안 갈린다.
     *
     * <p><b>마이크로초가 상한이다</b> — 표의 칸이 {@code TIMESTAMPTZ}라 거기까지만 저장된다.
     * 나노초를 쓰면 올린 직후 응답(메모리 값)과 다음 조회(표에서 읽은 값)가 갈려
     * 캐시 전제가 반대로 무너진다.
     */
    static long versionOf(Instant at) {
        return at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000;
    }

    /** {@link #versionOf}의 역방향. 표에 넣을 시각과 버전이 어긋나지 않게 한다. */
    static Instant instantOf(long version) {
        return Instant.ofEpochSecond(Math.floorDiv(version, 1_000_000L),
                Math.floorMod(version, 1_000_000L) * 1_000L);
    }

    /**
     * 🔴 <b>자리가 둘이고 버전의 홀짝으로 번갈아 쓴다.</b>
     *
     * <p>원래는 회원마다 하나로 고정했는데, 그러면 <b>창고에 쓴 뒤 표 갱신이 실패했을 때</b>
     * 파일만 새것이 되고 옛 주소가 그 새 그림을 준다 — 「실패했다」는 응답을 받은 사용자의
     * 화면에 새 사진이 뜬다(PR #127 codex, 재현함).
     *
     * <p><b>자리를 둘로 가르면 그 창이 닫힌다</b> — 실패하면 표가 안 바뀌므로 주소도 안 바뀌고,
     * 그 주소는 <b>옛 자리</b>를 가리킨다. 새 파일은 반대 자리에 남지만 아무도 안 가리키고,
     * <b>다음 업로드가 그 자리를 다시 고르므로 덮어쓴다</b>(자리가 둘뿐이라 그렇게 된다).
     * 그래서 청소 작업이 필요 없고 파일은 회원당 최대 둘이다.
     *
     * <p>🔴 <b>자리를 표에서 읽지 않는다</b> — 주소에 실린 버전에서 곧바로 얻는다.
     * 표를 읽으면 「사진을 올렸는가」로 걸리는 시간이 갈려 존재가 샌다
     * ({@link ProfilePhotoService#read}가 기대고 있는 성질이다).
     */
    static String keyOf(long userId, long version) {
        return "profile-photos/" + userId + "/" + Math.floorMod(version, SLOTS);
    }

    void put(long userId, long version, byte[] bytes, ImageType type);

    Optional<StoredPhoto> get(long userId, long version);

    /** 자리 둘을 <b>모두</b> 지운다 — 어느 쪽이 살아 있는지 부르는 쪽이 몰라도 되게. */
    void deleteAll(long userId);
}
