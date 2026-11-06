package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 论坛评论：仅两层。parentId=0 顶层，&gt;0 楼中楼；禁止再嵌套。
 */
@Data
@TableName("forum_comment")
public class ForumComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;
    private Long parentId;
    private Long userId;
    private String content;
    private Integer likeCount;

    /** 1 = AI 教练自动回帖 */
    private Integer isAi;

    /** 1正常 2隐藏 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
