package com.gym.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BindPhoneRequest {

    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    private String code;
}
