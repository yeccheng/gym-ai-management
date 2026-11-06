package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 论坛帖子。
 *
 * <p>status：0 待审 / 1 通过 / 2 拒绝 / 3 删除。
 * category：1 增肌 / 2 减脂 / 3 饮食 / 4 闲聊 / 5 求助（求助帖可触发 AI 教练回帖）。
 * view_count 以 Redis INCR 为准，定时刷库；列表热度走 ZSET。
 */
@Data
@TableName("forum_post")
public class ForumPost {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    private String content;
    private String summary;

    /** 1增肌 2减脂 3饮食 4闲聊 5求助 */
    private Integer category;

    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private Integer favoriteCount;

    /** 0待审 1通过 2拒绝 3删除 */
    private Integer status;

    private String aiAuditMsg;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
