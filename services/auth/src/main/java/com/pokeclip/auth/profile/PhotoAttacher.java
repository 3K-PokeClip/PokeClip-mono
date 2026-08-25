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
     * 파일 이름이 하나로 고정이라 어느 쪽이 이겨도 표와 파일이 어긋나지 않는다
     * ({@code UserService.updateName}과 같은 판단).
     */
    @Transactional
    User attach(long userId, Instant now) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId));
        user.attachPhoto(PhotoStorage.keyOf(userId), now);
        return user;
    }
}
