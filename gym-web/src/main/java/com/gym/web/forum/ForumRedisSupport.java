package com.gym.web.forum;

import com.gym.system.entity.ForumPost;
import com.gym.system.service.ForumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 论坛高并发热路径：
 * <ul>
 *   <li>浏览量：{@code INCR forum:view:{id}}，定时批量刷回 MySQL</li>
 *   <li>点赞去重：{@code SET forum:like:{type}:{id}} 成员为 userId；DB 唯一索引兜底</li>
 *   <li>热门榜：{@code ZSET forum:hot}，score = 赞×2 + 评×3 + 浏×0.1 − 时间衰减</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForumRedisSupport {

    public static final String VIEW_KEY = "forum:view:";
    public static final String VIEW_DIRTY = "forum:view:dirty";
    public static final String LIKE_KEY = "forum:like:";
    public static final String HOT_KEY = "forum:hot";

    private final StringRedisTemplate redis;
    private final ForumService forumService;

    /** 详情页浏览 +1，返回当前浏览量（优先 Redis） */
    public long incrView(Long postId, int dbViewCount) {
        String key = VIEW_KEY + postId;
        // 先 INCR，冷启动再用 INCRBY 补库内基数，禁止 SET 覆盖（会抹掉并发 INCR）
        Long v = redis.opsForValue().increment(key);
        if (v != null && v == 1L && dbViewCount > 0) {
            Long after = redis.opsForValue().increment(key, dbViewCount);
            v = after == null ? (long) dbViewCount + 1 : after;
        }
        redis.opsForSet().add(VIEW_DIRTY, String.valueOf(postId));
        redis.expire(key, Duration.ofDays(7));
        return v == null ? dbViewCount + 1L : v;
    }

    public long currentView(Long postId, int dbViewCount) {
        String raw = redis.opsForValue().get(VIEW_KEY + postId);
        if (raw == null) {
            return dbViewCount;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return dbViewCount;
        }
    }

    /** Redis SET 去重；true=本次新点赞 */
    public Boolean tryMarkLike(int targetType, Long targetId, long userId) {
        String key = LIKE_KEY + targetType + ":" + targetId;
        Long added = redis.opsForSet().add(key, String.valueOf(userId));
        redis.expire(key, Duration.ofDays(30));
        return added != null && added > 0;
    }

    public void unmarkLike(int targetType, Long targetId, long userId) {
        redis.opsForSet().remove(LIKE_KEY + targetType + ":" + targetId, String.valueOf(userId));
    }

    public boolean hasLiked(int targetType, Long targetId, long userId) {
        Boolean m = redis.opsForSet().isMember(LIKE_KEY + targetType + ":" + targetId, String.valueOf(userId));
        return Boolean.TRUE.equals(m);
    }

    /**
     * 热度公式（答辩可讲）：
     * score = like×2 + comment×3 + view×0.1 − hours×0.5
     * 时间衰减保证新帖有曝光，热帖不会永久霸榜。
     */
    public void removeHot(Long postId) {
        if (postId != null) {
            redis.opsForZSet().remove(HOT_KEY, String.valueOf(postId));
        }
    }

    public void refreshHotScore(ForumPost post, long viewCount) {
        if (post == null || post.getId() == null) {
            return;
        }
        if (post.getStatus() == null || post.getStatus() != ForumService.STATUS_PASS) {
            redis.opsForZSet().remove(HOT_KEY, String.valueOf(post.getId()));
            return;
        }
        int likes = post.getLikeCount() == null ? 0 : post.getLikeCount();
        int comments = post.getCommentCount() == null ? 0 : post.getCommentCount();
        double hours = 0;
        if (post.getCreateTime() != null) {
            hours = Duration.between(post.getCreateTime(), java.time.LocalDateTime.now()).toMinutes() / 60.0;
        }
        double score = likes * 2.0 + comments * 3.0 + viewCount * 0.1 - hours * 0.5;
        redis.opsForZSet().add(HOT_KEY, String.valueOf(post.getId()), score);
    }

    public Set<ZSetOperations.TypedTuple<String>> topHot(int limit) {
        return redis.opsForZSet().reverseRangeWithScores(HOT_KEY, 0, Math.max(0, limit - 1));
    }

    /** 每分钟把脏浏览量刷回 MySQL，避免每次详情都打行锁 */
    @Scheduled(fixedDelay = 60_000)
    public void flushViews() {
        Set<String> dirty = redis.opsForSet().members(VIEW_DIRTY);
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        for (String idStr : dirty) {
            try {
                Long id = Long.valueOf(idStr);
                String raw = redis.opsForValue().get(VIEW_KEY + id);
                if (raw == null) {
                    redis.opsForSet().remove(VIEW_DIRTY, idStr);
                    continue;
                }
                int views = Integer.parseInt(raw);
                // 先写库再清 dirty：写后若 Redis 又涨了，保留 dirty 下一轮再刷
                forumService.syncViewCount(id, views);
                String after = redis.opsForValue().get(VIEW_KEY + id);
                if (after != null && Integer.parseInt(after) == views) {
                    redis.opsForSet().remove(VIEW_DIRTY, idStr);
                }
            } catch (Exception e) {
                log.warn("[forum] 刷浏览量失败 id={}: {}", idStr, e.getMessage());
            }
        }
    }
}
