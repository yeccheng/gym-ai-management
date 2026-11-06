package com.gym.web.dto;

import lombok.Data;

@Data
public class ForumPostRequest {
    private String title;
    private String content;
    /** 1增肌 2减脂 3饮食 4闲聊 5求助 */
    private Integer category;
}
