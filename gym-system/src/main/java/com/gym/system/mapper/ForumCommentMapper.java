package com.gym.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.system.entity.ForumComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ForumCommentMapper extends BaseMapper<ForumComment> {

    @Update("UPDATE forum_comment SET like_count = GREATEST(IFNULL(like_count,0) + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int incrLikeCount(@Param("id") Long id, @Param("delta") int delta);
}
