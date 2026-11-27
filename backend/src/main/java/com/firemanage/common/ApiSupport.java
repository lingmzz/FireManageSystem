package com.firemanage.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ApiSupport {
    private ApiSupport() {}

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Page<T> {
        List<T> items;
        long total;
        int page;
        int size;
    }

    @Value
    public static class Paging {
        int page;
        int size;

        public Paging(int page, int size) {
            if (page < 1 || size < 1 || size > 100) {
                throw bad("请使用有效的页码与每页条数");
            }
            this.page = page;
            this.size = size;
        }

        public long offset() {
            return (page - 1L) * size;
        }

        public <T> Page<T> result(List<T> items, long total) {
            return new Page<>(items, total, page, size);
        }
    }

    public static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static ResponseStatusException invalidField(String field, String message) {
        return fieldError(HttpStatus.BAD_REQUEST, field, message);
    }

    public static ResponseStatusException duplicateField(String field, String message) {
        return fieldError(HttpStatus.CONFLICT, field, message);
    }

    private static ResponseStatusException fieldError(HttpStatus status, String field, String message) {
        return new FieldFailure(status, message, Collections.singletonMap(field, message));
    }

    public static ResponseStatusException missing(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "无权执行此操作");
    }

    public static ResponseStatusException unauthorized(String message) {
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
