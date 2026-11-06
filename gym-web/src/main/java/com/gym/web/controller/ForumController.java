package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.common.security.UserContext;
import com.gym.system.entity.ForumComment;
import com.gym.web.dto.ForumCommentRequest;
import com.gym.web.dto.ForumLikeRequest;
import com.gym.web.dto.ForumPostRequest;
import com.gym.web.forum.ForumFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会员侧社区接口（不走 /api/**，避免被 ADMIN 拦截）。
 */
@Tag(name = "健身社区")
@RestController
@RequestMapping("/forum")
@RequiredArgsConstructor
public class ForumController {

    private final ForumFacade forumFacade;

    @Operation(summary = "帖子分页（最新/热门 + 分类）")
    @GetMapping("/posts")
    public R<Map<String, Object>> posts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer category,
            @RequestParam(defaultValue = "new") String sort) {
        return R.ok(forumFacade.pagePosts(page, size, category, sort));
    }

    @Operation(summary = "我的帖子")
    @GetMapping("/posts/mine")
    public R<Map<String, Object>> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(forumFacade.pageMine(UserContext.requireUserId(), page, size));
    }

    @Operation(summary = "我的收藏")
    @GetMapping("/favorites")
    public R<Map<String, Object>> favorites(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(forumFacade.pageFavorites(UserContext.requireUserId(), page, size));
    }

    @Operation(summary = "帖子详情（浏览量 Redis INCR）")
    @GetMapping("/posts/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(forumFacade.detail(UserContext.requireUserId(), id));
    }

    @Operation(summary = "发帖（AI 审核 + 摘要 + 求助帖教练回帖）")
    @PostMapping("/posts")
    public R<Map<String, Object>> create(@RequestBody ForumPostRequest req) {
        return R.ok(forumFacade.create(UserContext.requireUserId(),
                req.getTitle(), req.getContent(), req.getCategory()));
    }

    @Operation(summary = "编辑帖子")
    @PutMapping("/posts/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody ForumPostRequest req) {
        return R.ok(forumFacade.update(UserContext.requireUserId(), id,
                req.getTitle(), req.getContent(), req.getCategory(), false));
    }

    @Operation(summary = "删除帖子")
    @DeleteMapping("/posts/{id}")
    public R<Void> delete(@PathVariable Long id) {
        forumFacade.delete(UserContext.requireUserId(), id, false);
        return R.ok();
    }

    @Operation(summary = "发表评论（仅两层）")
    @PostMapping("/comments")
    public R<ForumComment> comment(@RequestBody ForumCommentRequest req) {
        return R.ok(forumFacade.comment(UserContext.requireUserId(),
                req.getPostId(), req.getParentId(), req.getContent()));
    }

    @Operation(summary = "点赞 / 取消（toggle）")
    @PostMapping("/likes")
    public R<Map<String, Object>> like(@RequestBody ForumLikeRequest req) {
        return R.ok(forumFacade.toggleLike(UserContext.requireUserId(),
                req.getTargetType() == null ? 1 : req.getTargetType(), req.getTargetId()));
    }

    @Operation(summary = "收藏 / 取消（toggle）")
    @PostMapping("/favorites")
    public R<Map<String, Object>> favorite(@RequestParam Long postId) {
        return R.ok(forumFacade.toggleFavorite(UserContext.requireUserId(), postId));
    }
}
