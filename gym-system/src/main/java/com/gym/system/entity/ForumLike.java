package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 点赞：uk(user_id, target_type, target_id) 防重复；Redis SET 做热路径去重。 */
@Data
@TableName("forum_like")
public class ForumLike {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1帖子 2评论 */
    private Integer targetType;

    private Long targetId;
    private LocalDateTime createTime;
}
