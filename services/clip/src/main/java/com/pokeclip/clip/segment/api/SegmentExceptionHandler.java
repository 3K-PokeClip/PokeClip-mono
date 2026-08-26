package com.pokeclip.clip.segment.api;

import com.pokeclip.clip.segment.SegmentErrors.AuthUnavailableException;
import com.pokeclip.clip.segment.SegmentErrors.InvalidRangeException;
import com.pokeclip.clip.segment.SegmentErrors.NotViewableException;
import com.pokeclip.clip.segment.SegmentErrors.VodExpiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 세그먼트 창 조회의 거절 넷을 상태 코드로 옮기는 유일한 자리.
 *
 * <p>🔴 <b>{@code assignableTypes}로 이 컨트롤러에만 걸지만, 그것이 우선권을 주지는 않는다.</b>
 * 이 서버에는 {@code JumpCardExceptionHandler}가 범위를 안 좁힌 채 전역으로 떠 있고, 그쪽도
 * {@code broadcast_not_found}라는 <b>같은 코드 문자열</b>을 쓴다.
 *
 * <p>좁힘이 막는 것은 <b>한 방향뿐이다</b> — 「내 조언이 남의 컨트롤러를 안 잡는다」.
 * 반대 방향, 즉 <b>전역 조언이 이 컨트롤러의 예외를 가로채는 것은 못 막는다.</b>
 * 감사 2회차가 일부러 겹치게 만들어 재 봤고(전역에 {@code NotViewableException} 핸들러 추가)
 * <b>전역 쪽이 이겼다</b> — 404가 403으로 바뀌며 시험 3건이 빨간불이 됐다.
 *
 * <p>그러니 지금 응답이 옳은 근거는 「좁혔다」가 아니라 <b>「두 조언의 예외 타입이 하나도
 * 안 겹친다」</b>이다(감사 2회차 전수 확인, POK-174에서 재확인).
 *
 * <p>🔴 <b>「겹치는 날 즉시 빨간불이 된다」고 적어 뒀던 것은 반만 맞았다 — 봉투가 다를 때만이다.</b>
 * POK-174가 파라미터 400 둘({@code MethodArgumentTypeMismatchException}·
 * {@code MissingServletRequestParameterException})을 <b>여기서 전역으로 옮기면서</b> 일부러
 * 양쪽에 같은 타입을 둔 채 재 봤는데 <b>세그먼트 시험이 하나도 안 깨졌다</b> — 두 조언의
 * {@code field(...)} 봉투가 한 바이트도 안 달랐기 때문이다. 전역이 이기고 있다는 것은
 * 전역 쪽 본문을 바꿔 보고서야 드러났다({@code 숫자가_아닌_startMs도_같은_400_봉투다} 빨강).
 * <b>겹침을 알려 주는 것은 시험이 아니라 봉투의 차이다.</b>
 *
 * <p><b>파라미터 400 둘은 여기 없다 — 전역으로 옮겼다</b>(POK-174). 좁힌 조언에 두면
 * <b>새 문에는 안 걸려</b> 스프링 기본 {@code /error} 봉투가 나가고, 양쪽에 두면 전역이 이겨
 * 이쪽이 죽은 코드가 된다. 이 문의 400 셋이 여전히 같은 봉투인 것은
 * {@code 숫자가_아닌_startMs도_같은_400_봉투다}·{@code 파라미터가_빠져도_같은_400_봉투다}가 잠근다.
 *
 * <p><b>{@code Exception}·{@code IllegalArgumentException}을 통째로 잡지 않는다</b>
 * ({@code JumpCardExceptionHandler}와 같은 이유) — 내부 버그로 나온 예외가 4xx로 둔갑하면
 * 부르는 쪽이 「내가 잘못 보냈다」로 읽고 재시도를 멈춘다.
 */
@RestControllerAdvice(assignableTypes = SegmentController.class)
public class SegmentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SegmentExceptionHandler.class);

    /**
     * 404. <b>{@code reason}이 무엇이든 본문이 같다</b> — 「없는 방송」과 「자격 없음」이 응답에서
     * 구분되면 남의 방송 번호를 넣어 보는 것만으로 그 방송의 실재를 알 수 있다(PRD 결정).
     *
     * <p>그래서 사유는 <b>로그로만</b> 간다. 이 INFO 한 줄이 「주인이 자기 방송을 못 본다」는
     * 조용한 사고를 발견하는 유일한 수단이다 — 응답으로는 영영 구분이 안 된다.
     * WARN이 아닌 이유는 <b>남남의 정상 거절이 대부분</b>이라 그 자체로는 이상이 아니어서다
     * (진짜 이상인 비숫자 식별자는 {@code SegmentQueryService}가 ERROR로 따로 찍는다).
     *
     * <p>🔴 <b>본문이 같은 것만으로는 안 갈렸다 — 시간이 갈렸다.</b> 「없는 방송」은 auth를 안 부르고
     * 「자격 없음」은 왕복을 태우므로 실측 중앙값이 1.488ms 대 4.422ms였고, 한 번만 재도 99.5%가
     * 구분됐다(1,240회). 그래서 이 404만 {@link NotFoundFloor#FLOOR} 뒤에 내보낸다 —
     * 왜 그 값인지와 <b>무엇을 못 막는지</b>는 {@link NotFoundFloor}에 적었다.
     */
    @ExceptionHandler(NotViewableException.class)
    ResponseEntity<Map<String, Object>> notViewable(NotViewableException e, HttpServletRequest request) {
        // 값은 우리 열거형에서 온 고정 문자열 넷뿐이다 — 외부 입력이 그대로 로그로 가지 않는다.
        log.info("clip.segment.not_viewable reason={}", e.reason());
        // 기다림이 로그 뒤인 것은 의도다 — 로그를 쓰는 시간까지 바닥 안에 들어간다.
        NotFoundFloor.awaitFloor(request);
        return json(HttpStatus.NOT_FOUND, error("broadcast_not_found"));
    }

    /**
     * 410. 이 응답은 <b>자격이 확인된 사람에게만</b> 도달한다 — 「있었는데 없어졌다」는 뜻이라
     * 그 자체로 방송의 실재를 말하기 때문이다. 순서를 지키는 것은 서비스 쪽이다.
     */
    @ExceptionHandler(VodExpiredException.class)
    ResponseEntity<Map<String, Object>> vodExpired(VodExpiredException e) {
        return json(HttpStatus.GONE, error("vod_expired"));
    }

    /**
     * 503. 404가 아닌 이유는 <b>사람이 다시 눌러야 하기 때문이다</b> — 404로 접으면 화면이
     * 「없는 방송」이라고 단정하고, auth가 살아난 뒤에도 편집자는 다시 시도하지 않는다.
     */
    @ExceptionHandler(AuthUnavailableException.class)
    ResponseEntity<Map<String, Object>> authUnavailable(AuthUnavailableException e) {
        return json(HttpStatus.SERVICE_UNAVAILABLE, error("authorization_unavailable"));
    }

    /** 400. 여기엔 감출 것이 없다 — 어느 칸을 고쳐야 하는지 말해 준다(감추는 것은 404 쪽이다). */
    @ExceptionHandler(InvalidRangeException.class)
    ResponseEntity<Map<String, Object>> invalidRange(InvalidRangeException e) {
        return json(HttpStatus.BAD_REQUEST, field(e.field()));
    }

    /**
     * 🔴 <b>Content-Type을 명시한다</b> — {@code JumpCardExceptionHandler}가 같은 자리에서 실측한
     * 함정이다(협상에 실패하면 404·503이 500으로 둔갑한다).
     *
     * <p><b>「이 문은 {@code produces}가 없어 그 함정이 안 열린다」는 반만 맞다.</b>
     * {@code produces}는 협상을 좁히는 <b>한 가지 방법</b>일 뿐이고,
     * <b>클라이언트가 JSON을 안 받겠다는 {@code Accept}를 보내도</b> 똑같이 좁혀진다.
     * 그 상태에서 이 줄이 없으면 {@code HttpMediaTypeNotAcceptableException}이 조언 안에서
     * 삼켜지고 <b>원래 예외가 그대로 500으로 나간다</b> — 재현으로 확인함.
     * 명시하면 협상 자체를 건너뛴다.
     *
     * <p>{@code SegmentControllerTest.오류_응답은_Accept가_JSON이_아니어도_JSON으로_나간다}가
     * 그 조건을 만들어 이 줄을 지킨다. 그 갈래가 없던 동안에는 이 줄을 지워도 313건이 전부
     * 초록이었다(감사 2회차 E2) — 평범한 {@code Accept: *}{@code /*} 요청은 협상이 어차피
     * JSON으로 끝나기 때문이다.
     */
    private ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> error(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        return body;
    }

    /** 400 셋이 <b>칸 순서까지</b> 같은 봉투를 내게 하는 자리. {@code LinkedHashMap}이라 순서가 곧 본문이다. */
    private Map<String, Object> field(String name) {
        Map<String, Object> body = error("invalid_request");
        body.put("field", name);
        return body;
    }
}
