package com.gym.common.exception;

import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return new R<Void>().setCode(e.getCode()).setMessage(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return new R<Void>().setCode(ResultCode.PARAM_ERROR.getCode()).setMessage(msg);
    }

    /** 缺静态资源（如未配置的 favicon）只回 404，不打 ERROR 堆栈。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    /**
     * 排查期：所有异常的 message 一律透传给前端。
     * 不要再用关键词白名单过滤——否则浏览器 Network 里永远只能看到「开小差」这种遮羞布。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getName() + "（无 message）";
        }
        return new R<Void>()
                .setCode(ResultCode.SYSTEM_ERROR.getCode())
                .setMessage(msg);
    }
}
