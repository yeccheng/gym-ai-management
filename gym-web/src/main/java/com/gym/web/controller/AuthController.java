package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.web.dto.BindPhoneRequest;
import com.gym.web.dto.ChangePasswordRequest;
import com.gym.web.dto.LoginRequest;
import com.gym.web.dto.RegisterRequest;
import com.gym.web.dto.ResetPasswordRequest;
import com.gym.web.dto.SmsLoginRequest;
import com.gym.web.dto.SmsSendRequest;
import com.gym.web.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "认证", description = "登录、注册、验证码、找回密码、绑手机、改密与登出")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "密码登录", description = "用户名或手机号 + 密码；返回 JWT")
    @PostMapping("/login")
    public R<String> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request.getUsername(), request.getPassword()));
    }

    @Operation(summary = "发送短信验证码", description = "scene=login|register|reset|bind；演示模式固定码 123456")
    @PostMapping("/sms/send")
    public R<Map<String, Object>> sendSms(@Valid @RequestBody SmsSendRequest request,
                                          HttpServletRequest http) {
        return R.ok(authService.sendSms(request.getPhone(), request.getScene(), clientIp(http)));
    }

    @Operation(summary = "验证码登录", description = "手机号 + 验证码，无需密码")
    @PostMapping("/login/sms")
    public R<String> loginSms(@Valid @RequestBody SmsLoginRequest request) {
        return R.ok(authService.loginBySms(request));
    }

    @Operation(summary = "注册", description = "创建会员并直接登录；手机号注册需验证码")
    @PostMapping("/register")
    public R<String> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(authService.register(request));
    }

    @Operation(summary = "找回/重置密码", description = "手机号 + 验证码 + 新密码；成功后直接登录")
    @PostMapping("/password/reset")
    public R<String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return R.ok(authService.resetPassword(request));
    }

    @Operation(summary = "账号安全摘要", description = "是否已绑手机、掩码手机号等（需登录）")
    @GetMapping("/account")
    public R<Map<String, Object>> account() {
        return R.ok(authService.accountInfo());
    }

    @Operation(summary = "绑定/换绑手机号", description = "需登录 + 短信验证码 scene=bind")
    @PostMapping("/phone/bind")
    public R<Map<String, Object>> bindPhone(@Valid @RequestBody BindPhoneRequest request) {
        return R.ok(authService.bindPhone(request));
    }

    @Operation(summary = "修改密码", description = "需登录 + 当前密码")
    @PostMapping("/password/change")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return R.ok();
    }

    @Operation(summary = "登出", description = "将当前 Token 写入 Redis 黑名单，立即失效")
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(stripBearer(authorization));
        return R.ok();
    }

    private String stripBearer(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return authorization;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
