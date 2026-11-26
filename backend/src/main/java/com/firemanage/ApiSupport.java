package com.firemanage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

final class ApiSupport {
    private ApiSupport() {}

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Page<T> {
        List<T> items;
        long total;
        int page;
        int size;
    }

    @Value
    static class Paging {
        int page;
        int size;

        Paging(int page, int size) {
            if (page < 1 || size < 1 || size > 100) {
                throw bad("请使用有效的页码与每页条数");
            }
            this.page = page;
            this.size = size;
        }

        long offset() {
            return (page - 1L) * size;
        }

        <T> Page<T> result(List<T> items, long total) {
            return new Page<>(items, total, page, size);
        }
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
        return new FieldFailure(status, message, Collections.singletonMap(field, message));
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

    static final class FieldFailure extends ResponseStatusException {
        final Map<String, String> fields;

        private FieldFailure(HttpStatus status, String message, Map<String, String> fields) {
            super(status, message);
            this.fields = fields;
        }
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException failure) {
        Map<String, String> fields = failure instanceof ApiSupport.FieldFailure
                ? ((ApiSupport.FieldFailure) failure).fields : Collections.emptyMap();
        return problem(failure.getStatus(), failure.getReason(), fields);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException failure) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : failure.getBindingResult().getFieldErrors()) {
            String name = error.getField().replaceAll("\\[[^\\]]*]", "");
            fields.putIfAbsent(name, error.getDefaultMessage());
        }
        String detail = fields.isEmpty() ? "请检查表单内容" : fields.values().iterator().next();
        return problem(HttpStatus.BAD_REQUEST, detail, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException failure) {
        Map<String, String> fields = Collections.emptyMap();
        if (failure.getCause() instanceof JsonMappingException) {
            JsonMappingException mapping = (JsonMappingException) failure.getCause();
            List<String> path = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull).collect(Collectors.toList());
            if (!path.isEmpty()) fields = Collections.singletonMap(String.join(".", path), "请填写有效的值");
        }
        return problem(HttpStatus.BAD_REQUEST, "请求格式有误", fields);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> conflict() {
        return problem(HttpStatus.CONFLICT, "记录已存在或仍被其他记录使用", Collections.emptyMap());
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String detail, Map<String, String> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        if (!fields.isEmpty()) body.put("fieldErrors", fields);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
