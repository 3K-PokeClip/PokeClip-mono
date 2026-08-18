package com.pokeclip.auth.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * 계정 생성만 담당한다. UserService에서 떼어낸 이유는 트랜잭션 경계다 —
 * Spring의 @Transactional은 프록시로 동작해서, 같은 클래스의 메서드를 직접
 * 부르면 프록시를 우회하고 어노테이션이 무시된다.
 *
 * 제약 위반 예외를 여기서 잡지 않는다. 잡으면 이 트랜잭션 안에서 재조회하게
 * 되는데, 제약 위반이 난 트랜잭션은 rollback-only로 표시되고 Hibernate 세션도
 * 오염돼 더 이상 쓸 수 없다. 예외를 밖으로 던져 이 트랜잭션을 끝내고,
 * 호출한 쪽이 새 트랜잭션에서 재조회한다.
 */
@Component
@RequiredArgsConstructor
class UserCreator {

    private final UserRepository userRepository;

    /**
     * 이메일을 소문자로 통일해 저장한다. users.email의 유일 제약(V108)이 대소문자를
     * 구분하므로, 통일하지 않으면 Foo@a.com과 foo@a.com이 둘 다 통과해 초대가
     * 계정을 정확히 하나 찾지 못한다. 로캘을 ROOT로 못박는 이유는 터키어 로캘에서
     * I가 점 없는 ı로 바뀌어 같은 주소가 다른 값이 되기 때문이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    User create(String googleSub, String email, String name, String profileImageUrl) {
        return userRepository.saveAndFlush(
                User.of(googleSub, email.toLowerCase(Locale.ROOT), name, profileImageUrl, Instant.now()));
    }
}
