package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_favorite")
public class ForumFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long postId;
    private LocalDateTime createTime;
}
