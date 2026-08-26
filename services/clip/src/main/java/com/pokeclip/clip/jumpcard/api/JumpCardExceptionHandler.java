package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.ClaimedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.JumpCardNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.NotClaimOwnerException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.StreamLimitExceededException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.TokenAlreadyExpiredException;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.paging.InvalidCursorException;
import com.pokeclip.clip.paging.InvalidListParamException;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 카드 관련 예외를 상태 코드로 옮기는 유일한 자리.
 *
 * <p><b>{@code IllegalArgumentException}을 통째로 잡지 않는다.</b> 잡으면 내부 버그로 나온
 * 예외가 400으로 둔갑하고, 판별기는 그것을 「내가 잘못 보냈다」로 읽어 재시도를 멈춘다.
 * 400을 줄 자리는 컨트롤러가 {@code InvalidHighlightException}으로 좁혀 던진다.
 *
 * <p>🔴 <b>이 조언은 범위를 안 좁혔다 — 이 서버의 모든 문이 여기를 탄다.</b> 카드 밖의 것도
 * 여기 사는 이유가 그것이다(POK-174의 목록 문 둘·자격 판정). 좁힌 조언을 새로 만들면
 * 어느 쪽이 이길지가 배치 순서에 달리는데, {@code assignableTypes}는 <b>우선권을 주지 않고
 * 전역이 이긴다</b>(감사 2회차 실측).
 *
 * <p>🔴 <b>{@code AccessErrors}의 예외 둘은 {@code SegmentErrors}에 같은 단순 이름이 있다</b>
 * ({@code NotViewableException}·{@code AuthUnavailableException}). 그래서 여기서는
 * <b>바깥 클래스까지 적는다</b> — 단순 이름으로 import하면 잘못된 쪽을 골라도 <b>조용히
 * 컴파일된다</b>(둘 다 {@code RuntimeException} 하위다). 잘못 고르면 두 가지가 한꺼번에
 * 무너진다: 전역이 세그먼트 타입을 가로채 그 문의 봉투가 갈리고, {@code AccessErrors} 쪽은
 * 아무도 안 다뤄 <b>404가 500으로</b> 나간다.
 */
@RestControllerAdvice
public class JumpCardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JumpCardExceptionHandler.class);

    /**
     * 404. <b>이 갈래는 사람 문과 내부 문이 함께 쓴다</b> — 통로 열기(사람)와 판별기가 카드를 넣는
     * 문({@code POST /internal/…/highlights})이 같은 예외를 던진다. 그래서 바닥은
     * {@link NotFoundFloor#awaitFloorIfMarked}로 <b>기준이 찍힌 요청에만</b> 건다.
     * 내부 문에는 감출 존재가 없고(서버 간 토큰) 판별기는 404를 재시도 상한으로 세므로,
     * 거기에 25ms를 무는 것은 순수한 비용이다(계획 검증 m5).
     */
    @ExceptionHandler(BroadcastNotFoundException.class)
    ResponseEntity<Map<String, Object>> broadcastNotFound(BroadcastNotFoundException e,
                                                          HttpServletRequest request) {
        NotFoundFloor.awaitFloorIfMarked(request);
        return json(HttpStatus.NOT_FOUND, error("broadcast_not_found"));
    }

    /**
     * 404. 위와 같은 바닥을 탄다 — <b>카드 번호를 훑어 보는 것도 같은 종류의 탐색</b>이라
     * 「없는 카드」와 「자격 없어 못 보는 카드」가 시간으로 갈리면 안 된다. 그 두 갈래를 실제로
     * 만드는 것은 문 넷에 판정을 붙이는 태스크 8이고, 바닥은 지금 미리 자리를 잡아 둔다.
     */
    @ExceptionHandler(JumpCardNotFoundException.class)
    ResponseEntity<Map<String, Object>> jumpCardNotFound(JumpCardNotFoundException e,
                                                         HttpServletRequest request) {
        NotFoundFloor.awaitFloorIfMarked(request);
        return json(HttpStatus.NOT_FOUND, error("jump_card_not_found"));
    }

    /**
     * 409의 본문은 오류 봉투가 아니라 <b>현재 카드 스냅샷</b>이다 — 웹이 "누가 잡고 있는지"를
     * 새로고침 없이 바로 띄워야 편집자가 상황을 안다.
     */
    @ExceptionHandler(ClaimedByOtherException.class)
    ResponseEntity<JumpCardSnapshot> claimedByOther(ClaimedByOtherException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON).body(e.current());
    }

    @ExceptionHandler(NotClaimOwnerException.class)
    ResponseEntity<Map<String, Object>> notClaimOwner(NotClaimOwnerException e) {
        return json(HttpStatus.FORBIDDEN, error("not_claim_owner"));
    }

    /**
     * 503. {@code scope}를 실어야 웹이 "탭을 닫아라"(user)와 "잠시 뒤 다시"(total)를 구분해 안내한다.
     * SSE 경로에서 나가지만 본문은 JSON이다 — 연결이 서기 전에 끝나므로 event-stream이 아니다.
     */
    @ExceptionHandler(StreamLimitExceededException.class)
    ResponseEntity<Map<String, Object>> streamLimit(StreamLimitExceededException e) {
        Map<String, Object> body = error("stream_limit");
        body.put("scope", e.scope());
        return json(HttpStatus.SERVICE_UNAVAILABLE, body);
    }

    /** 401. 다른 401(체인이 내는 것)과 상태 코드가 같아야 프론트가 한 갈래로 처리한다. */
    @ExceptionHandler(TokenAlreadyExpiredException.class)
    ResponseEntity<Map<String, Object>> tokenExpired(TokenAlreadyExpiredException e) {
        return json(HttpStatus.UNAUTHORIZED, error("token_expired"));
    }

    @ExceptionHandler(InvalidHighlightException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidHighlightException e) {
        Map<String, Object> body = error("invalid_request");
        body.put("field", e.field());
        return json(HttpStatus.BAD_REQUEST, body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> beanValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = error("invalid_request");
        if (e.getFieldError() != null) {
            body.put("field", e.getFieldError().getField());
        }
        return json(HttpStatus.BAD_REQUEST, body);
    }

    // ── POK-174: 자격 판정과 목록 문 둘이 나눠 쓰는 갈래 ──────────

    /**
     * 404. <b>사유가 무엇이든 본문이 같다</b> — 「없는 방송」과 「자격 없음」이 응답에서 갈리면
     * 남의 방송 이름을 넣어 보는 것만으로 그 방송의 실재를 알 수 있다(PRD 결정).
     * 그래서 사유는 <b>로그로만</b> 간다. 값은 우리 코드가 정한 고정 문자열이라 외부 입력이
     * 그대로 로그로 가지 않는다.
     *
     * <p>WARN이 아닌 이유는 <b>남남의 정상 거절이 대부분</b>이라 그 자체로는 이상이 아니어서다
     * ({@code SegmentExceptionHandler}와 같은 판단).
     *
     * <p>🔴 <b>본문이 같은 것만으로는 안 갈린다 — 시간이 갈린다.</b> 「없는 방송」은 auth를
     * 안 부르고 「자격 없음」은 왕복을 태운다(세그먼트 문에서 1.5ms 대 4.4ms 실측). 그래서
     * {@link NotFoundFloor}가 두 갈래를 같은 바닥 뒤로 민다 — 그 클래스는 이 카드에서
     * {@code segment.api}를 떠나 {@code support}로 옮겨 왔고, 사람 문 둘이 나눠 쓴다.
     */
    @ExceptionHandler(AccessErrors.NotViewableException.class)
    ResponseEntity<Map<String, Object>> notViewable(AccessErrors.NotViewableException e,
                                                    HttpServletRequest request) {
        // 값은 우리 코드가 정한 고정 문자열이다 — 외부 입력이 그대로 로그로 가지 않는다.
        log.info("clip.access.not_viewable reason={}", e.reason());
        // 기다림이 로그 뒤인 것은 의도다 — 로그를 쓰는 시간까지 바닥 안에 들어간다.
        NotFoundFloor.awaitFloorIfMarked(request);
        return json(HttpStatus.NOT_FOUND, error("broadcast_not_found"));
    }

    /**
     * 503. <b>404로 접지 않는다</b> — 화면이 「없는 방송」이라고 단정하면 auth가 살아난 뒤에도
     * 편집자는 다시 시도하지 않는다. 목록 문에서는 <b>빈 목록으로도 접지 않는다</b>(같은 이유).
     */
    @ExceptionHandler(AccessErrors.AuthUnavailableException.class)
    ResponseEntity<Map<String, Object>> authUnavailable(AccessErrors.AuthUnavailableException e) {
        return json(HttpStatus.SERVICE_UNAVAILABLE, error("authorization_unavailable"));
    }

    /**
     * 400. 어느 칸인지만 말한다 — 표시는 <b>불투명</b>이라 「태그가 틀렸다」·「칸이 모자란다」를
     * 알려 줘도 웹이 고칠 수 있는 것이 아니다. 받은 표시를 되돌려주지도 않는다.
     */
    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<Map<String, Object>> invalidCursor(InvalidCursorException e) {
        return json(HttpStatus.BAD_REQUEST, field("cursor"));
    }

    /** 400. 목록 문의 요청 칸({@code state}·{@code limit})이 범위 밖이다. */
    @ExceptionHandler(InvalidListParamException.class)
    ResponseEntity<Map<String, Object>> invalidListParam(InvalidListParamException e) {
        return json(HttpStatus.BAD_REQUEST, field(e.field()));
    }

    /**
     * 400. <b>{@code limit=abc}처럼 값이 안 바뀌면 컨트롤러 메서드에 들어오기 전에 끝난다</b> —
     * 그러면 위 갈래를 못 지나고 스프링 기본 {@code /error} 봉투로 나가, 웹이 같은 400에
     * <b>모양이 다른 본문 둘</b>을 받는다.
     *
     * <p>🔴 <b>이 타입은 {@code SegmentExceptionHandler}에서 옮겨 왔다</b>(POK-174).
     * 그쪽에 두면 좁힌 조언이라 <b>새 문에는 안 걸린다</b> — 그리고 양쪽에 두면 전역이 이겨
     * 좁힌 쪽이 죽은 코드가 된다({@code assignableTypes}는 우선권을 안 준다). 한 자리에 모은다.
     *
     * <p>값 자체는 안 싣는다 — 자유 입력을 그대로 되돌려주는 자리가 되면 안 된다.
     * 칸 이름은 우리 시그니처에서 온 고정 문자열이다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return json(HttpStatus.BAD_REQUEST, field(e.getName()));
    }

    /** 400. 위와 같은 뿌리의 다른 갈래 — 값이 안 바뀌는 것이 아니라 아예 없는 경우다. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> missingParameter(MissingServletRequestParameterException e) {
        return json(HttpStatus.BAD_REQUEST, field(e.getParameterName()));
    }

    /**
     * <b>Content-Type을 명시해야 한다.</b> SSE 문은 {@code produces=text/event-stream}이고
     * 브라우저 EventSource는 {@code Accept: text/event-stream}을 보낸다 — 그 상태로 JSON을 돌려주면
     * 협상에 실패해 {@code HttpMediaTypeNotAcceptableException}이 나고 <b>404·503이 500으로 둔갑한다</b>
     * (실측: 없는 방송 → 500, 상한 초과 → 500). 명시하면 협상을 건너뛴다.
     */
    private ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> error(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        return body;
    }

    /**
     * 400 갈래들이 <b>칸 순서까지</b> 같은 봉투를 내게 하는 자리.
     * {@code LinkedHashMap}이라 순서가 곧 본문이고, {@code SegmentExceptionHandler}의
     * 같은 이름 도우미와 <b>한 글자도 다르면 안 된다</b> — 두 조언이 같은 문의 400을 나눠 낸다.
     */
    private Map<String, Object> field(String name) {
        Map<String, Object> body = error("invalid_request");
        body.put("field", name);
        return body;
    }
}
