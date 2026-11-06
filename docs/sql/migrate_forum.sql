-- 健身社区论坛（高并发读 + AI 审核/摘要/教练回帖）
-- 约定：不加 FK（与全库一致，由应用层约束）；软删 + TINYINT 状态机

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS forum_post (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    title         VARCHAR(120) NOT NULL,
    content       TEXT         NOT NULL,
    summary       VARCHAR(500)                          COMMENT 'AI 摘要',
    category      TINYINT      NOT NULL DEFAULT 4      COMMENT '1增肌 2减脂 3饮食 4闲聊 5求助',
    view_count    INT          NOT NULL DEFAULT 0,
    like_count    INT          NOT NULL DEFAULT 0,
    comment_count INT          NOT NULL DEFAULT 0,
    favorite_count INT         NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 0      COMMENT '0待审 1通过 2拒绝 3删除',
    ai_audit_msg  VARCHAR(255)                          COMMENT 'AI 审核说明',
    deleted       TINYINT      NOT NULL DEFAULT 0,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status_cat_time (status, category, create_time),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '论坛帖子';

CREATE TABLE IF NOT EXISTS forum_comment (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    post_id     BIGINT       NOT NULL,
    parent_id   BIGINT       NOT NULL DEFAULT 0       COMMENT '0=顶层；>0=楼中楼（仅两层）',
    user_id     BIGINT       NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    like_count  INT          NOT NULL DEFAULT 0,
    is_ai       TINYINT      NOT NULL DEFAULT 0       COMMENT '1=AI 教练自动回帖',
    status      TINYINT      NOT NULL DEFAULT 1       COMMENT '1正常 2隐藏',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_post_parent (post_id, parent_id, create_time),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '论坛评论（仅两层）';

CREATE TABLE IF NOT EXISTS forum_like (
    id          BIGINT   PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    target_type TINYINT  NOT NULL                      COMMENT '1帖子 2评论',
    target_id   BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_target (user_id, target_type, target_id),
    KEY idx_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '点赞（唯一索引防重复）';

CREATE TABLE IF NOT EXISTS forum_favorite (
    id          BIGINT   PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    post_id     BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_post (user_id, post_id),
    KEY idx_post (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '收藏';
