package com.pokeclip.auth.profile;

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
        public void put(long userId, byte[] bytes, ImageType type) {
            throw new ProfileUpdateException(ProfileUpdateFailure.PHOTO_STORAGE_DISABLED, "사진 창고가 꺼져 있다");
        }

        @Override
        public Optional<StoredPhoto> get(long userId) {
            return Optional.empty();
        }

        @Override
        public void delete(long userId) {
        }
    };

    /** 회원마다 하나로 고정하고 덮어쓴다 — 주인 없는 파일이 생길 수가 없다(PRD). */
    static String keyOf(long userId) {
        return "profile-photos/" + userId;
    }

    void put(long userId, byte[] bytes, ImageType type);

    Optional<StoredPhoto> get(long userId);

    void delete(long userId);
}
