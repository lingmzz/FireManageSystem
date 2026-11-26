package com.firemanage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonMappingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

final class ApiSupport {
    private ApiSupport() {}

    record Page<T>(List<T> items, long total, int page, int size) {}

    record Paging(int page, int size) {
        Paging {
            if (page < 1 || size < 1 || size > 100) {
                throw bad("请使用有效的页码与每页条数");
            }
        }

        long offset() {
            return (page - 1L) * size;
        }

        <T> Page<T> result(List<T> items, long total) {
            return new Page<>(items, total, page, size);
        }
    }

    static long insert(JdbcClient.StatementSpec statement) {
        var key = new GeneratedKeyHolder();
        statement.update(key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    static ResponseStatusException invalidField(String field, String message) {
        return fieldError(HttpStatus.BAD_REQUEST, field, message);
    }

    static ResponseStatusException duplicateField(String field, String message) {
        return fieldError(HttpStatus.CONFLICT, field, message);
    }

    private static ResponseStatusException fieldError(HttpStatus status, String field, String message) {
        var failure = new ResponseStatusException(status, message);
        failure.getBody().setProperty("fieldErrors", Map.of(field, message));
        return failure;
    }

    static ResponseStatusException missing(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "无权执行此操作");
    }

    static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException failure) {
        return failure.getBody();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException failure) {
        var fields = new LinkedHashMap<String, String>();
        for (var error : failure.getBindingResult().getFieldErrors()) {
            String name = error.getField().replaceAll("\\[[^\\]]*]", "");
            fields.putIfAbsent(name, error.getDefaultMessage());
        }
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                fields.isEmpty() ? "请检查表单内容" : fields.sequencedValues().getFirst());
        if (!fields.isEmpty()) problem.setProperty("fieldErrors", fields);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail malformed(HttpMessageNotReadableException failure) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求格式有误");
        if (failure.getCause() instanceof JsonMappingException mapping) {
            var path = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull).toList();
            if (!path.isEmpty()) problem.setProperty("fieldErrors", Map.of(String.join(".", path), "请填写有效的值"));
        }
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "记录已存在或仍被其他记录使用");
    }
}
