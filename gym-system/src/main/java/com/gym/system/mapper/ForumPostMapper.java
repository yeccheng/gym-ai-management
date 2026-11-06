package com.gym.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.system.entity.ForumPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ForumPostMapper extends BaseMapper<ForumPost> {

    @Update("UPDATE forum_post SET view_count = GREATEST(IFNULL(view_count,0), #{viewCount}) WHERE id = #{id} AND deleted = 0")
    int syncViewCount(@Param("id") Long id, @Param("viewCount") int viewCount);

    @Update("UPDATE forum_post SET like_count = GREATEST(IFNULL(like_count,0) + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int incrLikeCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE forum_post SET comment_count = comment_count + #{delta} WHERE id = #{id} AND deleted = 0")
    int incrCommentCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE forum_post SET favorite_count = GREATEST(IFNULL(favorite_count,0) + #{delta}, 0) WHERE id = #{id} AND deleted = 0")
    int incrFavoriteCount(@Param("id") Long id, @Param("delta") int delta);
}
