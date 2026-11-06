package com.gym.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.system.entity.ForumComment;
import com.gym.system.entity.ForumFavorite;
import com.gym.system.entity.ForumLike;
import com.gym.system.entity.ForumPost;
import com.gym.system.mapper.ForumCommentMapper;
import com.gym.system.mapper.ForumFavoriteMapper;
import com.gym.system.mapper.ForumLikeMapper;
import com.gym.system.mapper.ForumPostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 论坛领域：帖子 / 评论 / 点赞 / 收藏的持久化。
 * 浏览量与热门榜的 Redis 热路径在 gym-web 侧编排。
 */
@Service
@RequiredArgsConstructor
public class ForumService extends ServiceImpl<ForumPostMapper, ForumPost> {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PASS = 1;
    public static final int STATUS_REJECT = 2;
    public static final int STATUS_DELETED = 3;

    public static final int LIKE_POST = 1;
    public static final int LIKE_COMMENT = 2;

    public static final int CAT_HELP = 5;

    private final ForumCommentMapper commentMapper;
    private final ForumLikeMapper likeMapper;
    private final ForumFavoriteMapper favoriteMapper;

    public Page<ForumPost> pagePosts(int page, int size, Integer category, Integer status, String sort) {
        QueryWrapper<ForumPost> qw = new QueryWrapper<>();
        if (status != null) {
            qw.eq("status", status);
        } else {
            qw.eq("status", STATUS_PASS);
        }
        if (category != null && category > 0) {
            qw.eq("category", category);
        }
        if ("hot".equalsIgnoreCase(sort)) {
            qw.orderByDesc("like_count").orderByDesc("comment_count").orderByDesc("view_count");
        } else {
            qw.orderByDesc("create_time");
        }
        return page(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 50)), qw);
    }

    public Page<ForumPost> pageMine(long userId, int page, int size) {
        return page(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 50)),
                new QueryWrapper<ForumPost>()
                        .eq("user_id", userId)
                        .ne("status", STATUS_DELETED)
                        .orderByDesc("create_time"));
    }

    public ForumPost requirePost(Long id) {
        ForumPost post = getById(id);
        if (post == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "帖子不存在");
        }
        return post;
    }

    public ForumPost createPost(long userId, String title, String content, Integer category) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标题和内容不能为空");
        }
        ForumPost post = new ForumPost();
        post.setUserId(userId);
        post.setTitle(title.trim());
        post.setContent(content.trim());
        post.setCategory(category == null || category < 1 || category > 5 ? 4 : category);
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setFavoriteCount(0);
        post.setStatus(STATUS_PENDING);
        save(post);
        return post;
    }

    public ForumPost updatePost(long userId, Long id, String title, String content, Integer category, boolean admin) {
        ForumPost post = requirePost(id);
        if (!admin && !post.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能编辑自己的帖子");
        }
        if (StringUtils.hasText(title)) {
            post.setTitle(title.trim());
        }
        if (StringUtils.hasText(content)) {
            post.setContent(content.trim());
        }
        if (category != null && category >= 1 && category <= 5) {
            post.setCategory(category);
        }
        if (!admin) {
            post.setStatus(STATUS_PENDING);
        }
        updateById(post);
        return post;
    }

    public void softDeletePost(long userId, Long id, boolean admin) {
        ForumPost post = requirePost(id);
        if (!admin && !post.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除自己的帖子");
        }
        post.setStatus(STATUS_DELETED);
        updateById(post);
        removeById(id);
    }

    public void audit(Long id, int status, String msg) {
        if (status != STATUS_PASS && status != STATUS_REJECT) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "审核状态只能是通过或拒绝");
        }
        ForumPost post = requirePost(id);
        post.setStatus(status);
        if (StringUtils.hasText(msg)) {
            post.setAiAuditMsg(msg);
        }
        updateById(post);
    }

    public List<ForumComment> listComments(Long postId) {
        return commentMapper.selectList(new QueryWrapper<ForumComment>()
                .eq("post_id", postId)
                .eq("status", 1)
                .orderByAsc("create_time")
                .last("LIMIT 200"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ForumComment addComment(long userId, Long postId, Long parentId, String content, boolean ai) {
        ForumPost post = requirePost(postId);
        if (post.getStatus() != STATUS_PASS && !ai) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "帖子未通过审核，暂不可评论");
        }
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "评论内容不能为空");
        }
        long pid = parentId == null ? 0L : parentId;
        if (pid > 0) {
            ForumComment parent = commentMapper.selectById(pid);
            if (parent == null || !parent.getPostId().equals(postId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "父评论不存在");
            }
            // 仅两层：父评论若已是楼中楼，禁止再嵌套
            if (parent.getParentId() != null && parent.getParentId() > 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "评论仅支持两层（顶层 + 楼中楼）");
            }
        }
        ForumComment c = new ForumComment();
        c.setPostId(postId);
        c.setParentId(pid);
        c.setUserId(userId);
        c.setContent(content.trim());
        c.setLikeCount(0);
        c.setIsAi(ai ? 1 : 0);
        c.setStatus(1);
        commentMapper.insert(c);
        baseMapper.incrCommentCount(postId, 1);
        return c;
    }

    /**
     * @return true 点赞成功；false 取消点赞
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleLike(long userId, int targetType, Long targetId) {
        if (targetType != LIKE_POST && targetType != LIKE_COMMENT) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "targetType 无效");
        }
        ForumLike exist = likeMapper.selectOne(new QueryWrapper<ForumLike>()
                .eq("user_id", userId)
                .eq("target_type", targetType)
                .eq("target_id", targetId)
                .last("LIMIT 1"));
        if (exist != null) {
            likeMapper.deleteById(exist.getId());
            applyLikeDelta(targetType, targetId, -1);
            return false;
        }
        assertLikeTargetExists(targetType, targetId);
        ForumLike like = new ForumLike();
        like.setUserId(userId);
        like.setTargetType(targetType);
        like.setTargetId(targetId);
        try {
            likeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            return true;
        }
        int updated = applyLikeDelta(targetType, targetId, 1);
        if (updated <= 0) {
            likeMapper.delete(new QueryWrapper<ForumLike>()
                    .eq("user_id", userId)
                    .eq("target_type", targetType)
                    .eq("target_id", targetId));
            throw new BusinessException(ResultCode.NOT_FOUND, "点赞目标不存在");
        }
        return true;
    }

    private void assertLikeTargetExists(int targetType, Long targetId) {
        if (targetType == LIKE_POST) {
            ForumPost post = requirePost(targetId);
            if (post.getStatus() == null || post.getStatus() != STATUS_PASS) {
                throw new BusinessException(ResultCode.BIZ_ERROR, "帖子未公开，暂不可点赞");
            }
            return;
        }
        ForumComment comment = commentMapper.selectById(targetId);
        if (comment == null || comment.getStatus() == null || comment.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "评论不存在");
        }
    }

    private int applyLikeDelta(int targetType, Long targetId, int delta) {
        if (targetType == LIKE_POST) {
            return baseMapper.incrLikeCount(targetId, delta);
        }
        return commentMapper.incrLikeCount(targetId, delta);
    }

    /** @return true 收藏；false 取消 */
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(long userId, Long postId) {
        ForumPost post = requirePost(postId);
        ForumFavorite exist = favoriteMapper.selectOne(new QueryWrapper<ForumFavorite>()
                .eq("user_id", userId)
                .eq("post_id", postId)
                .last("LIMIT 1"));
        if (exist != null) {
            favoriteMapper.deleteById(exist.getId());
            baseMapper.incrFavoriteCount(postId, -1);
            return false;
        }
        if (post.getStatus() == null || post.getStatus() != STATUS_PASS) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "帖子未公开，暂不可收藏");
        }
        ForumFavorite fav = new ForumFavorite();
        fav.setUserId(userId);
        fav.setPostId(postId);
        try {
            favoriteMapper.insert(fav);
        } catch (DuplicateKeyException e) {
            return true;
        }
        baseMapper.incrFavoriteCount(postId, 1);
        return true;
    }

    /** 求助帖是否已有 AI 教练顶层回复（审核反复通过时防重复） */
    public boolean hasAiTopComment(Long postId) {
        if (postId == null) {
            return false;
        }
        return commentMapper.selectCount(new QueryWrapper<ForumComment>()
                .eq("post_id", postId)
                .eq("is_ai", 1)
                .eq("status", 1)
                .and(w -> w.eq("parent_id", 0).or().isNull("parent_id"))) > 0;
    }

    public boolean liked(long userId, int targetType, Long targetId) {
        return likeMapper.selectCount(new QueryWrapper<ForumLike>()
                .eq("user_id", userId)
                .eq("target_type", targetType)
                .eq("target_id", targetId)) > 0;
    }

    public boolean favorited(long userId, Long postId) {
        return favoriteMapper.selectCount(new QueryWrapper<ForumFavorite>()
                .eq("user_id", userId)
                .eq("post_id", postId)) > 0;
    }

    public Page<ForumPost> pageFavorites(long userId, int page, int size) {
        List<ForumFavorite> favs = favoriteMapper.selectList(new QueryWrapper<ForumFavorite>()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .last("LIMIT 200"));
        if (favs.isEmpty()) {
            return new Page<>(page, size, 0);
        }
        List<Long> ids = favs.stream().map(ForumFavorite::getPostId).toList();
        Page<ForumPost> p = page(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 50)),
                new QueryWrapper<ForumPost>().in("id", ids).eq("status", STATUS_PASS).orderByDesc("create_time"));
        return p;
    }

    public void syncViewCount(Long id, int viewCount) {
        baseMapper.syncViewCount(id, viewCount);
    }
}
