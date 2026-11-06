package com.gym.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度 3～32 位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码至少 6 位")
    private String password;

    /** 界面招呼用的名字，可空，空则用用户名 */
    @Size(max = 32, message = "昵称最多 32 字")
    private String realName;

    /**
     * 手机号：用手机号注册时与 username 相同即可；
     * 用字母用户名注册时建议另填，便于验证码登录/找回密码。
     */
    private String phone;

    /** 手机号注册时必填（演示验证码 123456） */
    private String smsCode;
}
