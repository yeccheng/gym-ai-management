package com.gym.web.dto;

import lombok.Data;

@Data
public class ForumLikeRequest {
    /** 1帖子 2评论 */
    private Integer targetType;
    private Long targetId;
}
