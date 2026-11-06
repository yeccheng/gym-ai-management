package com.gym.web.dto;

import lombok.Data;

@Data
public class ForumCommentRequest {
    private Long postId;
    /** 0 或不传 = 顶层；传顶层评论 id = 楼中楼 */
    private Long parentId;
    private String content;
}
