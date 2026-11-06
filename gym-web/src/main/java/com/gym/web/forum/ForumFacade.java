package com.gym.web.forum;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gym.system.entity.ForumComment;
import com.gym.system.entity.ForumPost;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.SysUserMapper;
import com.gym.system.service.ForumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 论坛编排：DB CRUD + Redis 热路径 + AI 三件套。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumFacade {

    private final ForumService forumService;
    private final ForumRedisSupport redisSupport;
    private final ForumAiService forumAiService;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final SysUserMapper sysUserMapper;

    private final ExecutorService aiPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "forum-ai");
        t.setDaemon(true);
        return t;
    });

    public Map<String, Object> pagePosts(int page, int size, Integer category, String sort) {
        if ("hot".equalsIgnoreCase(sort)) {
            return pageByHot(page, size, category);
        }
        Page<ForumPost> p = forumService.pagePosts(page, size, category, ForumService.STATUS_PASS, "new");
        return pageResult(p);
    }

    private Map<String, Object> pageByHot(int page, int size, Integer category) {
        // 热榜以 ZSET 顺序取 id，再查库；ZSET 未热身时退回 DB hot 排序
        var tuples = redisSupport.topHot(200);
        if (tuples == null || tuples.isEmpty()) {
            Page<ForumPost> p = forumService.pagePosts(page, size, category, ForumService.STATUS_PASS, "hot");
            return pageResult(p);
        }
        List<Long> ordered = new ArrayList<>();
        for (var t : tuples) {
            if (t.getValue() != null) {
                ordered.add(Long.valueOf(t.getValue()));
            }
        }
        List<ForumPost> all = ordered.isEmpty() ? List.of() : forumService.listByIds(ordered);
        Map<Long, ForumPost> byId = new HashMap<>();
        for (ForumPost post : all) {
            byId.put(post.getId(), post);
        }
        List<ForumPost> filtered = new ArrayList<>();
        for (Long id : ordered) {
            ForumPost post = byId.get(id);
            if (post == null || post.getStatus() == null || post.getStatus() != ForumService.STATUS_PASS) {
                continue;
            }
            if (category != null && category > 0 && !category.equals(post.getCategory())) {
                continue;
            }
            filtered.add(post);
        }
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(filtered.size(), from + size);
        List<ForumPost> slice = from >= filtered.size() ? List.of() : filtered.subList(from, to);
        Map<String, Object> out = new HashMap<>();
        out.put("records", toVoList(slice));
        out.put("total", filtered.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    public Map<String, Object> pageMine(long userId, int page, int size) {
        return pageResult(forumService.pageMine(userId, page, size));
    }

    public Map<String, Object> pageFavorites(long userId, int page, int size) {
        return pageResult(forumService.pageFavorites(userId, page, size));
    }

    public Map<String, Object> pageAdmin(int page, int size, Integer status) {
        Page<ForumPost> p = forumService.pagePosts(page, size, null, status, "new");
        return pageResult(p);
    }

    public Map<String, Object> detail(long userId, Long id) {
        ForumPost post = forumService.requirePost(id);
        // 非通过帖：仅作者或后续由 admin 接口查看；会员详情只暴露已通过 + 自己的
        boolean owner = post.getUserId() != null && post.getUserId() == userId;
        if (post.getStatus() != ForumService.STATUS_PASS && !owner) {
            // 仍允许看自己的待审/拒绝
            throw new com.gym.common.exception.BusinessException(
                    com.gym.common.result.ResultCode.NOT_FOUND, "帖子不存在或未公开");
        }
        long views = redisSupport.incrView(id, post.getViewCount() == null ? 0 : post.getViewCount());
        post.setViewCount((int) Math.min(views, Integer.MAX_VALUE));
        redisSupport.refreshHotScore(post, views);

        Map<String, Object> vo = toVo(post);
        vo.put("liked", forumService.liked(userId, ForumService.LIKE_POST, id)
                || redisSupport.hasLiked(ForumService.LIKE_POST, id, userId));
        vo.put("favorited", forumService.favorited(userId, id));
        List<ForumComment> comments = forumService.listComments(id);
        vo.put("comments", toCommentTree(comments, userId));
        return vo;
    }

    public Map<String, Object> create(long userId, String title, String content, Integer category) {
        ForumPost post = forumService.createPost(userId, title, content, category);
        // 同步：DFA+AI 审核；通过后再异步摘要与教练回帖
        ForumAiService.AuditResult audit = forumAiService.audit(post.getTitle(), post.getContent());
        post.setAiAuditMsg(audit.reason());
        if (audit.pass()) {
            post.setStatus(ForumService.STATUS_PASS);
            forumService.updateById(post);
            redisSupport.refreshHotScore(post, 0);
            Long postId = post.getId();
            aiPool.execute(() -> {
                try {
                    ForumPost latest = forumService.getById(postId);
                    if (latest == null) {
                        return;
                    }
                    String summary = forumAiService.summarize(latest.getTitle(), latest.getContent());
                    if (summary != null) {
                        latest.setSummary(summary);
                        forumService.updateById(latest);
                    }
                    forumAiService.maybeCoachReply(latest);
                    redisSupport.refreshHotScore(forumService.getById(postId),
                            redisSupport.currentView(postId, latest.getViewCount() == null ? 0 : latest.getViewCount()));
                } catch (Exception e) {
                    log.warn("[forum] 异步 AI 失败 postId={}: {}", postId, e.getMessage());
                }
            });
        } else {
            post.setStatus(ForumService.STATUS_REJECT);
            forumService.updateById(post);
        }
        return toVo(post);
    }

    public Map<String, Object> update(long userId, Long id, String title, String content, Integer category, boolean admin) {
        ForumPost post = forumService.updatePost(userId, id, title, content, category, admin);
        if (!admin) {
            ForumAiService.AuditResult audit = forumAiService.audit(post.getTitle(), post.getContent());
            post.setAiAuditMsg(audit.reason());
            post.setStatus(audit.pass() ? ForumService.STATUS_PASS : ForumService.STATUS_REJECT);
            forumService.updateById(post);
            if (audit.pass()) {
                redisSupport.refreshHotScore(post, redisSupport.currentView(id, post.getViewCount() == null ? 0 : post.getViewCount()));
            } else {
                redisSupport.refreshHotScore(post, 0);
            }
        }
        return toVo(post);
    }

    public void delete(long userId, Long id, boolean admin) {
        forumService.softDeletePost(userId, id, admin);
        redisSupport.removeHot(id);
    }

    public void audit(Long id, int status, String msg) {
        forumService.audit(id, status, msg);
        ForumPost post = forumService.getById(id);
        if (post != null && status == ForumService.STATUS_PASS) {
            redisSupport.refreshHotScore(post, redisSupport.currentView(id, post.getViewCount() == null ? 0 : post.getViewCount()));
            aiPool.execute(() -> forumAiService.maybeCoachReply(post));
        } else if (post != null) {
            redisSupport.refreshHotScore(post, 0);
        }
    }

    public ForumComment comment(long userId, Long postId, Long parentId, String content) {
        String hit = sensitiveWordFilter.findFirst(content);
        if (hit != null) {
            throw new com.gym.common.exception.BusinessException(
                    com.gym.common.result.ResultCode.PARAM_ERROR, "评论含敏感词：" + hit);
        }
        ForumComment c = forumService.addComment(userId, postId, parentId, content, false);
        ForumPost post = forumService.getById(postId);
        if (post != null) {
            redisSupport.refreshHotScore(post, redisSupport.currentView(postId, post.getViewCount() == null ? 0 : post.getViewCount()));
        }
        return c;
    }

    public Map<String, Object> toggleLike(long userId, int targetType, Long targetId) {
        boolean liked = forumService.toggleLike(userId, targetType, targetId);
        if (liked) {
            redisSupport.tryMarkLike(targetType, targetId, userId);
        } else {
            redisSupport.unmarkLike(targetType, targetId, userId);
        }
        if (targetType == ForumService.LIKE_POST) {
            ForumPost post = forumService.getById(targetId);
            if (post != null) {
                redisSupport.refreshHotScore(post,
                        redisSupport.currentView(targetId, post.getViewCount() == null ? 0 : post.getViewCount()));
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("liked", liked);
        return out;
    }

    public Map<String, Object> toggleFavorite(long userId, Long postId) {
        boolean fav = forumService.toggleFavorite(userId, postId);
        Map<String, Object> out = new HashMap<>();
        out.put("favorited", fav);
        return out;
    }

    private Map<String, Object> pageResult(Page<ForumPost> p) {
        Map<String, Object> out = new HashMap<>();
        List<ForumPost> records = p.getRecords();
        for (ForumPost post : records) {
            long v = redisSupport.currentView(post.getId(), post.getViewCount() == null ? 0 : post.getViewCount());
            post.setViewCount((int) Math.min(v, Integer.MAX_VALUE));
        }
        out.put("records", toVoList(records));
        out.put("total", p.getTotal());
        out.put("page", p.getCurrent());
        out.put("size", p.getSize());
        return out;
    }

    private List<Map<String, Object>> toVoList(List<ForumPost> posts) {
        Map<Long, String> names = loadNames(posts.stream().map(ForumPost::getUserId).toList());
        List<Map<String, Object>> list = new ArrayList<>();
        for (ForumPost post : posts) {
            Map<String, Object> vo = toVo(post);
            vo.put("authorName", names.getOrDefault(post.getUserId(), "用户" + post.getUserId()));
            list.add(vo);
        }
        return list;
    }

    private Map<String, Object> toVo(ForumPost post) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", post.getId());
        vo.put("userId", post.getUserId());
        vo.put("title", post.getTitle());
        vo.put("content", post.getContent());
        vo.put("summary", post.getSummary());
        vo.put("category", post.getCategory());
        vo.put("viewCount", post.getViewCount());
        vo.put("likeCount", post.getLikeCount());
        vo.put("commentCount", post.getCommentCount());
        vo.put("favoriteCount", post.getFavoriteCount());
        vo.put("status", post.getStatus());
        vo.put("aiAuditMsg", post.getAiAuditMsg());
        vo.put("createTime", post.getCreateTime());
        vo.put("updateTime", post.getUpdateTime());
        SysUser u = post.getUserId() == null ? null : sysUserMapper.selectById(post.getUserId());
        vo.put("authorName", displayName(u, post.getUserId()));
        return vo;
    }

    private List<Map<String, Object>> toCommentTree(List<ForumComment> flat, long userId) {
        Map<Long, String> names = loadNames(flat.stream().map(ForumComment::getUserId).toList());
        List<Map<String, Object>> roots = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> children = new HashMap<>();
        for (ForumComment c : flat) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("postId", c.getPostId());
            m.put("parentId", c.getParentId());
            m.put("userId", c.getUserId());
            m.put("content", c.getContent());
            m.put("likeCount", c.getLikeCount());
            m.put("isAi", c.getIsAi() != null && c.getIsAi() == 1);
            m.put("createTime", c.getCreateTime());
            m.put("authorName", c.getIsAi() != null && c.getIsAi() == 1
                    ? "AI 教练"
                    : names.getOrDefault(c.getUserId(), "用户" + c.getUserId()));
            m.put("liked", forumService.liked(userId, ForumService.LIKE_COMMENT, c.getId()));
            m.put("replies", new ArrayList<Map<String, Object>>());
            long pid = c.getParentId() == null ? 0L : c.getParentId();
            if (pid == 0L) {
                roots.add(m);
            } else {
                children.computeIfAbsent(pid, k -> new ArrayList<>()).add(m);
            }
        }
        for (Map<String, Object> root : roots) {
            Long id = (Long) root.get("id");
            List<Map<String, Object>> reps = children.getOrDefault(id, List.of());
            root.put("replies", reps);
        }
        return roots;
    }

    private Map<Long, String> loadNames(List<Long> userIds) {
        Map<Long, String> map = new HashMap<>();
        Set<Long> uniq = new HashSet<>();
        for (Long id : userIds) {
            if (id != null) {
                uniq.add(id);
            }
        }
        for (Long id : uniq) {
            SysUser u = sysUserMapper.selectById(id);
            map.put(id, displayName(u, id));
        }
        return map;
    }

    private static String displayName(SysUser u, Long id) {
        if (u == null) {
            return "用户" + id;
        }
        if (u.getRealName() != null && !u.getRealName().isBlank()) {
            return u.getRealName();
        }
        return u.getUsername() != null ? u.getUsername() : ("用户" + id);
    }
}
