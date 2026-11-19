package com.omsweb.fees;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(AppException.class)
    ResponseEntity<Map<String, String>> businessError(AppException error) {
        return ResponseEntity.status(error.status()).body(message(error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidInput(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(message(field == null ? "请检查输入内容" : field.getDefaultMessage()));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestValueException.class
    })
    ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().body(message("请求格式有误，请核对文件、日期和选项"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> largeUpload() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(message("文件超过上传大小上限"));
    }

    private Map<String, String> message(String message) {
        return Map.of("message", ApiMessages.translate(message));
    }
}
