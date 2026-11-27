package com.firemanage.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonMappingException;
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

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException failure) {
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
