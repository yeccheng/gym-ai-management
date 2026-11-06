package com.gym.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SmsSendRequest {

    @NotBlank(message = "手机号不能为空")
    private String phone;

    /** login | register | reset */
    @NotBlank(message = "场景不能为空")
    private String scene;
}
