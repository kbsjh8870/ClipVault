package com.clipvault.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 컨트롤러에서 던진 예외를 잡아서, 클라이언트에게 항상 같은 모양의 JSON 에러로 돌려주는 전역 처리기.
 *
 * <p>응답 형식: {@code {"status": 404, "message": "Clip not found"}}</p>
 *
 * <p>이 클래스 덕분에 컨트롤러에서는 {@code throw new ResponseStatusException(HttpStatus.NOT_FOUND, "...")}
 * 한 줄만 쓰면 되고, 에러 응답을 만드는 코드를 매번 반복할 필요가 없다.</p>
 *
 * <p>참고: 인증 실패(401)/권한 부족(403)은 컨트롤러에 도달하기 전에 시큐리티 필터에서 걸러지기 때문에
 * 여기가 아니라 SecurityConfig에서 같은 형식으로 응답한다.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 에러 응답 본문. status는 HTTP 상태 코드 숫자, message는 사람이 읽을 수 있는 설명. */
    public record ErrorResponse(int status, String message) {
    }

    /** 컨트롤러가 직접 던진 예외(404 없음, 409 중복, 401 로그인 실패 등)를 그대로 상태 코드와 메시지로 변환. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> handle(ResponseStatusException e) {
        return of(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    /**
     * 요청 본문의 검증 실패 → 400.
     * DTO에 붙인 {@code @NotBlank}, {@code @Email}, {@code @Size} 같은 조건을 어기면 발생한다.
     * 어떤 필드가 왜 틀렸는지 알려 준다. 예: "password: size must be between 8 and 100"
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException e) {
        var err = e.getBindingResult().getFieldError();
        return of(HttpStatus.BAD_REQUEST, err == null ? "Invalid request" : err.getField() + ": " + err.getDefaultMessage());
    }

    /**
     * 요청 형식 자체가 잘못된 경우 → 400.
     * <ul>
     *   <li>HttpMessageNotReadableException: JSON 문법이 깨졌거나 본문이 비어 있음</li>
     *   <li>MethodArgumentTypeMismatchException: URL의 {id} 자리에 UUID가 아닌 값이 들어옴 (예: /api/clips/abc)</li>
     * </ul>
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return of(HttpStatus.BAD_REQUEST, "Malformed request");
    }

    /** 공통 응답 생성. 메시지가 비어 있으면 표준 문구(예: "Not Found")로 채운다. */
    private static ResponseEntity<ErrorResponse> of(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), message == null ? status.getReasonPhrase() : message));
    }
}
