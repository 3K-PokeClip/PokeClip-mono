package com.pokeclip.auth.profile;

import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.DataInconsistencyException;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 표 갱신만 담당한다. ProfilePhotoService에서 떼어낸 이유는 트랜잭션 경계다 —
 * Spring의 {@code @Transactional}은 프록시로 동작해서, 같은 클래스의 메서드를 직접 부르면
 * 프록시를 우회하고 어노테이션이 무시된다({@code UserCreator}가 떼어진 이유와 정확히 같다).
 *
 * <p><b>창고 호출은 여기 들어오지 않는다.</b> DB 커넥션을 쥔 채 외부 HTTP(최대 8초)를 기다리면
 * 동시 요청이 풀 크기(10)를 넘는 순간 사진과 무관한 로그인·토큰 회전까지 멈춘다
 * (「알려진 구멍」 9·10번 — 풀 10·동시 25에서 21/25 실패·30초 마비 실측).
 */
@Component
@RequiredArgsConstructor
class PhotoAttacher {

    private final UserRepository userRepository;

    /**
     * 락을 잡지 않는다. 같은 회원이 사진을 동시에 두 번 올리면 마지막이 이기고 그만이다 —
     * 🔴 <b>다만 「어느 쪽이 이겨도 표와 파일이 맞는다」고 쓰면 거짓이다</b>(PR #133 codex P2).
     * 두 업로드가 같은 순간에 표를 읽으면 <b>같은 반대 자리</b>를 고르고, 그러면 나중 쓰기가
     * 앞 쓰기를 덮은 뒤 앞 트랜잭션이 커밋할 수 있다 — 표에는 앞의 버전, 자리에는 뒤의 바이트다.
     * <b>자리를 가르기 전에도 같았다</b>(고정 이름이면 마지막 쓰기가 항상 이겼다). 새로 생긴 성질이
     * 아니라서 그대로 두지만, 어긋나지 않는다고 말하지는 않는다. 어느 쪽이든 <b>그 회원 자신의
     * 사진 둘 중 하나</b>라 남의 것이 새지는 않는다
     * ({@code UserService.updateName}과 같은 판단).
     */
    @Transactional
    User attach(long userId, long version) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId));
        user.attachPhoto(PhotoStorage.keyOf(userId, version), PhotoStorage.instantOf(version));
        return user;
    }

    /**
     * 지금 어느 자리를 쓰고 있나 — 없으면 {@code null}.
     *
     * <p>올리는 쪽이 <b>반대 자리</b>를 고르려면 이 값이 필요하다. 읽기 하나가 늘지만
     * 올리는 경로는 사람이 누르는 자리라 드물고, <b>꺼내는 경로는 이 조회를 안 탄다</b> —
     * 거기서 표를 읽으면 존재가 시간으로 새기 때문이다.
     */
    @Transactional(readOnly = true)
    Long currentVersion(long userId) {
        return userRepository.findById(userId)
                .map(User::getProfilePhotoUpdatedAt)
                .map(PhotoStorage::versionOf)
                .orElse(null);
    }
}
