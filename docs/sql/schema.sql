-- ============================================================
-- 智慧健身管理系统 · 建表脚本
-- 设计要点：
--   1. 训练数据粒度到「组」(set_log)，不是「练了什么动作」——否则算不出容量与 e1RM
--   2. 会员表唯一索引为 (phone, deleted) 联合索引——解决逻辑删除与唯一索引的冲突
--   3. 消课记录带 biz_no 唯一键——MQ 重复消费的幂等依据
--   4. ai_task 落库——长任务支持 taskId 查询与重启后恢复（对标 RAG 长任务做法）
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 训练领域（核心，70% 权重） ----------

-- 动作库：RAG 的私有语料来源，外部无现成数据集
CREATE TABLE IF NOT EXISTS exercise (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name             VARCHAR(64)  NOT NULL                COMMENT '动作名称',
    name_en          VARCHAR(64)                          COMMENT '英文名',
    alias            VARCHAR(255)                         COMMENT '别名，逗号分隔，供口语化检索召回',
    equipment        TINYINT      NOT NULL DEFAULT 1      COMMENT '1杠铃 2哑铃 3固定器械 4自重 5绳索 6壶铃',
    movement_type    TINYINT      NOT NULL DEFAULT 1      COMMENT '1复合 2孤立',
    movement_pattern TINYINT                              COMMENT '1推 2拉 3蹲 4髋铰链 5核心 6单侧',
    primary_muscle   VARCHAR(32)                          COMMENT '主要肌群：胸/背/股四头/腘绳/臀/肩/肱二/肱三/小腿/腹',
    secondary_muscle VARCHAR(128)                         COMMENT '辅助肌群，逗号分隔',
    difficulty       TINYINT      NOT NULL DEFAULT 2      COMMENT '1入门 2进阶 3高级',
    action_level     TINYINT      NOT NULL DEFAULT 2      COMMENT '1基础主动作 2变体 3辅助孤立，搜索排序用',
    steps            TEXT                                 COMMENT '动作要领',
    common_mistakes  TEXT                                 COMMENT '常见错误',
    breathing        VARCHAR(255)                         COMMENT '呼吸节奏',
    safety_tips      TEXT                                 COMMENT '安全提示',
    deleted          TINYINT      NOT NULL DEFAULT 0,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_primary_muscle (primary_muscle),
    KEY idx_pattern (movement_pattern)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '动作库';

-- 一次训练
CREATE TABLE IF NOT EXISTS workout_session (
    id              BIGINT     PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT     NOT NULL,
    workout_date    DATE       NOT NULL                COMMENT '训练日期',
    session_type    TINYINT                            COMMENT '1推 2拉 3腿 4上肢 5下肢 6全身 7有氧',
    start_time      DATETIME,
    end_time        DATETIME,
    duration_min    INT                                COMMENT '时长（分钟）',
    readiness_score TINYINT                            COMMENT '主观准备度 1-5，用于自动降载判断',
    sleep_hours     DECIMAL(3, 1)                      COMMENT '睡眠时长，疲劳管理输入',
    body_weight_kg  DECIMAL(5, 2),
    total_volume    DECIMAL(12, 2)                     COMMENT '确定性计算得出，不交给大模型',
    note            VARCHAR(500),
    is_demo         TINYINT   NOT NULL DEFAULT 0       COMMENT '1示例数据 0真实打卡',
    deleted         TINYINT   NOT NULL DEFAULT 0,
    create_time     DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_date (user_id, workout_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '训练记录';

-- 每一组：整个系统的数据地基
--
-- 关键设计：performed_at（训练发生时间）与 create_time（记录录入时间）必须分开。
-- 用户周三补录周一练的内容是常态，趋势分析若按 create_time 取窗口，
-- 这组数据会被算进周三那一周，周容量、e1RM 序列、平台期判定全部错位。
-- 所有按时间窗口的统计一律走 performed_at，create_time 只用于审计。
CREATE TABLE IF NOT EXISTS set_log (
    id             BIGINT      PRIMARY KEY AUTO_INCREMENT,
    session_id     BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    exercise_id    BIGINT      NOT NULL,
    set_index      INT         NOT NULL                COMMENT '第几组',
    reps           INT         NOT NULL                COMMENT '次数',
    weight_kg      DECIMAL(6, 2) NOT NULL DEFAULT 0    COMMENT '重量',
    rpe            DECIMAL(3, 1)                       COMMENT '主观强度 6-10',
    is_warmup      TINYINT     NOT NULL DEFAULT 0      COMMENT '是否热身组',
    is_working_set TINYINT     NOT NULL DEFAULT 1      COMMENT '有效组判定：重量>=65%e1RM 或 RPE>=7',
    e1rm           DECIMAL(6, 2)                       COMMENT '该组估算1RM，写入时计算，供趋势分析',
    is_pr          TINYINT     NOT NULL DEFAULT 0      COMMENT '是否个人记录',
    tempo          VARCHAR(16)                         COMMENT '节奏，如 3-1-2-0',
    rest_sec       INT                                 COMMENT '组间休息秒数',
    performed_at   DATETIME    NOT NULL                COMMENT '训练发生时间，所有时间窗口统计都按它',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间，仅用于审计',
    KEY idx_session (session_id),
    KEY idx_user_performed (user_id, performed_at),
    KEY idx_user_exercise (user_id, exercise_id, performed_at),
    KEY idx_exercise (exercise_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '训练组记录';

-- 计划执行偏差（P2：减一组/换动作/状态差 → 下次计划自动调强度）
CREATE TABLE IF NOT EXISTS plan_deviation (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    session_id  BIGINT,
    exercise_id BIGINT,
    type        VARCHAR(32)  NOT NULL                COMMENT 'SKIP_SET/SWAP/BAD_FEEL/INJURY_HEAVY',
    note        VARCHAR(255),
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '训练计划执行偏差';

-- 饮食热量粗略记录（智能体 getCurrentDietLog 工具依赖）
CREATE TABLE IF NOT EXISTS diet_log (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    log_date    DATE         NOT NULL                COMMENT '记录日期',
    meal_type   TINYINT      NOT NULL DEFAULT 1      COMMENT '1早餐 2午餐 3晚餐 4加餐',
    food_name   VARCHAR(64)  NOT NULL                COMMENT '食物名称',
    calories    INT          NOT NULL DEFAULT 0      COMMENT '热量 kcal',
    protein_g   DECIMAL(6,1)                         COMMENT '蛋白质克数',
    note        VARCHAR(255),
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_date (user_id, log_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '饮食热量粗略记录';

-- 体测记录
CREATE TABLE IF NOT EXISTS body_record (
    id                BIGINT      PRIMARY KEY AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL,
    record_date       DATE        NOT NULL,
    weight_kg         DECIMAL(5, 2),
    body_fat_pct      DECIMAL(4, 2),
    skeletal_muscle_kg DECIMAL(5, 2),
    note              VARCHAR(255),
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_date (user_id, record_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '体测记录';

-- ---------- AI 任务与知识库 ----------

-- 长任务落库：taskId + 状态机 + 结果持久化，支持查询与重启恢复
CREATE TABLE IF NOT EXISTS ai_task (
    id            BIGINT   PRIMARY KEY AUTO_INCREMENT,
    task_id       VARCHAR(36) NOT NULL                COMMENT '对外暴露的任务ID',
    user_id       BIGINT   NOT NULL,
    task_type     TINYINT  NOT NULL                   COMMENT '1周报复盘 2平台期破局 3计划生成 4知识问答',
    status        TINYINT  NOT NULL DEFAULT 0         COMMENT '0排队 1执行中 2成功 3失败 4取消',
    request_json  JSON                                COMMENT '请求快照',
    result_json   LONGTEXT                            COMMENT '结果（含 cited ids，供引用校验）',
    error_msg     VARCHAR(500),
    token_in      INT      NOT NULL DEFAULT 0,
    token_out     INT      NOT NULL DEFAULT 0,
    elapsed_ms    INT      NOT NULL DEFAULT 0,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    finish_time   DATETIME,
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_user_type (user_id, task_type, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 异步任务';

-- RAG 切片：MD5 判重 + 章节号入 metadata
CREATE TABLE IF NOT EXISTS rag_doc_chunk (
    id         BIGINT   PRIMARY KEY AUTO_INCREMENT,
    doc_name   VARCHAR(128) NOT NULL,
    doc_md5    CHAR(32) NOT NULL                      COMMENT '文档 MD5，用于入库判重',
    doc_type   TINYINT  NOT NULL                      COMMENT '1动作库 2训练原则 3营养',
    section_no VARCHAR(64)                            COMMENT '章节号，写入向量库 metadata',
    title      VARCHAR(255),
    content    TEXT     NOT NULL,
    token_count INT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_md5_section (doc_md5, section_no),
    KEY idx_doc_type (doc_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识库切片';

-- ---------- 场馆业务（做薄，30% 权重，为扣题保留） ----------

CREATE TABLE IF NOT EXISTS member (
    id          BIGINT      PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(32) NOT NULL,
    phone       VARCHAR(20) NOT NULL,
    gender      TINYINT     NOT NULL DEFAULT 0,
    birthday    DATE,
    height_cm   DECIMAL(5, 2),
    weight_kg   DECIMAL(5, 2),
    goal        TINYINT     NOT NULL DEFAULT 1          COMMENT '1增肌 2减脂 3力量 4塑形 5健康',
    train_level TINYINT     NOT NULL DEFAULT 1          COMMENT '1新手 2中级 3高级，由 e1RM/体重 自动判定',
    join_date   DATE,
    status      TINYINT     NOT NULL DEFAULT 1          COMMENT '1正常 2挂失 3冻结 4注销',
    deleted     TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 关键：联合唯一索引，解决「逻辑删除后同一手机号无法重新录入」的经典冲突
    UNIQUE KEY uk_phone_deleted (phone, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员';

CREATE TABLE IF NOT EXISTS equipment (
    id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(64) NOT NULL,
    category   TINYINT     NOT NULL DEFAULT 1          COMMENT '1有氧 2力量 3自由重量 4功能',
    location   VARCHAR(32),
    status     TINYINT     NOT NULL DEFAULT 1          COMMENT '1正常 2报修 3停用',
    buy_date   DATE,
    last_maint DATE,
    note       VARCHAR(255),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '器材';

CREATE TABLE IF NOT EXISTS membership_card (
    id           BIGINT      PRIMARY KEY AUTO_INCREMENT,
    member_id    BIGINT      NOT NULL,
    card_name    VARCHAR(64) NOT NULL,
    card_type    TINYINT     NOT NULL                   COMMENT '1次卡 2月卡 3年卡 4私教卡',
    total_count  INT         NOT NULL DEFAULT 0,
    remain_count INT         NOT NULL DEFAULT 0,
    start_date   DATE        NOT NULL,
    end_date     DATE        NOT NULL,
    price        DECIMAL(10, 2),
    status       TINYINT     NOT NULL DEFAULT 1         COMMENT '1有效 2已用完 3已过期',
    version      INT         NOT NULL DEFAULT 0         COMMENT '乐观锁，消课并发控制',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员卡';

CREATE TABLE IF NOT EXISTS coach (
    id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(32) NOT NULL,
    phone      VARCHAR(20),
    level      TINYINT                                  COMMENT '1初级 2中级 3高级',
    specialty  VARCHAR(255),
    intro      TEXT,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '教练';

CREATE TABLE IF NOT EXISTS course (
    id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(64) NOT NULL,
    coach_id   BIGINT      NOT NULL,
    capacity   INT         NOT NULL DEFAULT 10,
    booked_cnt INT         NOT NULL DEFAULT 0,
    course_time DATETIME   NOT NULL,
    duration_min INT       NOT NULL DEFAULT 60,
    room       VARCHAR(32),
    status     TINYINT     NOT NULL DEFAULT 1           COMMENT '1可预约 2已满 3已结束 4已取消',
    version    INT         NOT NULL DEFAULT 0           COMMENT '乐观锁，防止超卖',
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_coach_time (coach_id, course_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '课程';

CREATE TABLE IF NOT EXISTS booking (
    id         BIGINT   PRIMARY KEY AUTO_INCREMENT,
    member_id  BIGINT   NOT NULL,
    course_id  BIGINT   NOT NULL,
    status     TINYINT  NOT NULL DEFAULT 1              COMMENT '1已预约 2已完成 3已取消 4超时未支付',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 注意：不在此建 (member_id, course_id) 唯一约束。取消后的预约(status=3)仍需保留用于追溯，
    -- 否则同一会员无法在取消后再次预约同一课程。重复「有效预约」由业务层(CourseService.book)前置拦截并返回友好错误。
    KEY idx_member_course (member_id, course_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '课程预约';

CREATE TABLE IF NOT EXISTS consume_record (
    id            BIGINT   PRIMARY KEY AUTO_INCREMENT,
    -- 消课流水号：MQ 重复消费时靠它做幂等，防止一次预约扣两次课时
    biz_no        VARCHAR(64) NOT NULL,
    member_id     BIGINT   NOT NULL,
    card_id       BIGINT,
    course_id     BIGINT,
    consume_count INT      NOT NULL DEFAULT 1,
    consume_type  TINYINT  NOT NULL DEFAULT 1           COMMENT '1课程消课 2入场消次',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '消课流水';

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT      PRIMARY KEY AUTO_INCREMENT,
    username    VARCHAR(64) NOT NULL,
    password    VARCHAR(128) NOT NULL,
    real_name   VARCHAR(32),
    phone       VARCHAR(20)                          COMMENT '登录手机号，可与 username 相同',
    role        TINYINT     NOT NULL DEFAULT 3          COMMENT '1管理员 2教练 3会员',
    member_id   BIGINT,
    status      TINYINT     NOT NULL DEFAULT 1,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username_deleted (username, deleted),
    KEY idx_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '系统用户';

-- ---------- 健身社区论坛 ----------
-- 高并发：浏览量 Redis INCR 定时刷库；点赞 Redis SET + uk 兜底；热门 ZSET
-- AI：发帖审核 / 摘要 / 求助帖 AI 教练回帖（仅两层评论，不做无限嵌套）

CREATE TABLE IF NOT EXISTS forum_post (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    title          VARCHAR(120) NOT NULL,
    content        TEXT         NOT NULL,
    summary        VARCHAR(500)                          COMMENT 'AI 摘要',
    category       TINYINT      NOT NULL DEFAULT 4      COMMENT '1增肌 2减脂 3饮食 4闲聊 5求助',
    view_count     INT          NOT NULL DEFAULT 0,
    like_count     INT          NOT NULL DEFAULT 0,
    comment_count  INT          NOT NULL DEFAULT 0,
    favorite_count INT          NOT NULL DEFAULT 0,
    status         TINYINT      NOT NULL DEFAULT 0      COMMENT '0待审 1通过 2拒绝 3删除',
    ai_audit_msg   VARCHAR(255)                          COMMENT 'AI 审核说明',
    deleted        TINYINT      NOT NULL DEFAULT 0,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status_cat_time (status, category, create_time),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '论坛帖子';

CREATE TABLE IF NOT EXISTS forum_comment (
    id          BIGINT        PRIMARY KEY AUTO_INCREMENT,
    post_id     BIGINT        NOT NULL,
    parent_id   BIGINT        NOT NULL DEFAULT 0       COMMENT '0=顶层；>0=楼中楼（仅两层）',
    user_id     BIGINT        NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    like_count  INT           NOT NULL DEFAULT 0,
    is_ai       TINYINT       NOT NULL DEFAULT 0       COMMENT '1=AI 教练自动回帖',
    status      TINYINT       NOT NULL DEFAULT 1       COMMENT '1正常 2隐藏',
    deleted     TINYINT       NOT NULL DEFAULT 0,
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
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

-- ---------- 动作库种子数据（答辩演示用） ----------
INSERT INTO exercise (name, name_en, alias, equipment, movement_type, movement_pattern,
                      primary_muscle, secondary_muscle, difficulty, steps, common_mistakes,
                      breathing, safety_tips) VALUES
('杠铃卧推', 'Barbell Bench Press', '卧推,平板卧推,bench', 1, 1, 1, '胸', '肱三头肌,三角肌前束', 2,
 '仰卧于平板，双眼位于杠铃正下方；肩胛骨后收下沉并全程保持；握距略宽于肩，杠铃下落至乳线位置，小臂垂直地面；推起至肘部接近伸直但不锁死。',
 '肩胛骨不收拢导致肩关节受压；杠铃弹胸借力；臀部离凳；手腕过度后翻。',
 '下放时吸气，推起过粘滞点后呼气。',
 '大重量必须有保护者或安全杠；手腕不要过度后翻，避免腕关节劳损。'),

('杠铃深蹲', 'Barbell Back Squat', '深蹲,后蹲,背蹲,squat', 1, 1, 3, '股四头肌', '臀,腘绳肌,核心', 2,
 '杠铃置于斜方肌上，双手窄握收紧背部；下蹲时髋膝同时启动，膝盖沿脚尖方向；下蹲至大腿低于水平或活动度允许的最低位；起身时先抬髋再伸膝。',
 '膝盖内扣；骨盆眨眼（底部腰椎失去中立）；上身过度前倾把重量压到腰；脚跟离地。',
 '下蹲时屏气加压（Valsalva），过粘滞点后呼气换气。',
 '膝内扣是膝关节损伤的主要诱因，可用弹力带套膝辅助；底部骨盆眨眼时应减重或垫高脚跟。'),

('硬拉', 'Conventional Deadlift', '传统硬拉,deadlift', 1, 1, 4, '腘绳肌', '臀,背,股四头肌,前臂', 3,
 '站距与髋同宽，杠铃贴近小腿；屈髋屈膝，背部保持中立，手臂垂直；蹬地启动，杠铃全程贴腿上行；锁定髋膝后缓慢下放。',
 '启动瞬间腰部弯曲（圆背硬拉）；杠铃离身体过远导致腰椎力矩剧增；用腰把重量拽起来而非蹬地。',
 '拉起前吸气加压，锁定后呼气。',
 '腰部一旦出现弯曲应立即停止，这是腰椎间盘损伤的高风险动作。'),

('肩上推举', 'Overhead Press', '站姿推举,肩推,OHP', 1, 1, 1, '肩', '肱三头肌,上胸,核心', 2,
 '站姿，杠铃置于锁骨上方，手腕在肘正上方；收紧核心与臀部，身体略微后让让杠铃垂直上行；推至过头后肩胛上回旋；下落回到锁骨上方。',
 '腰椎过度反弓代偿；手腕不在肘的正上方导致力线偏移；肩胛未上回旋造成肩峰撞击。',
 '推起过头部后呼气，下落时吸气。',
 '肩关节活动度不足者应改用哑铃或器械推举，避免肩峰撞击。'),

('引体向上', 'Pull Up', '引体,拉单杠,pullup', 4, 1, 2, '背', '肱二头肌,核心', 3,
 '双手略宽于肩握杠，肩胛先下沉后收；启动时想象把肘往地面方向拉；上拉至下巴过杠，下放至手臂接近伸直但保持肩胛张力。',
 '用甩身体借力（kipping）；只用小臂和二头拉；下放时完全松掉肩胛。',
 '上拉时呼气，下放时吸气。',
 '肩部有疼痛时应避免颈后引体。'),

('哑铃卧推', 'Dumbbell Bench Press', '哑铃推胸,哑铃卧推', 2, 1, 1, '胸', '肱三头肌,三角肌前束', 1,
 '仰卧，双手持哑铃位于胸部两侧，掌心朝前；推起时哑铃向中间靠拢但不碰撞；下放至胸部两侧有拉伸感。',
 '哑铃轨迹过于分散导致肩关节受力过大；下落过快失去控制。',
 '下放吸气，推起呼气。',
 '哑铃卧推的活动度大于杠铃，肩部有伤者需控制下放幅度。'),

('杠铃划船', 'Barbell Row', '俯身划船,row', 1, 1, 2, '背', '肱二头肌,后肩,核心', 2,
 '屈髋至上身接近水平，膝盖微屈，腰椎保持中立；将杠铃沿大腿拉向下腹部；顶点挤压肩胛后缓慢放回。',
 '上身角度站得太高变成直立划船；用腰甩动提拉；肩胛没有后缩。',
 '拉起时呼气，放回时吸气。',
 '俯身姿势下腰椎负担大，核心必须全程收紧。'),

('腿举', 'Leg Press', '倒蹬,腿举,legpress', 3, 1, 3, '股四头肌', '臀,腘绳肌', 1,
 '坐入器械，双脚踏板与髋同宽；膝盖微屈解锁安全装置；下放至大腿接近躯干，膝盖不要内扣；蹬起时不要锁死膝盖。',
 '下放幅度过大导致骨盆离垫卷起；膝盖内扣；锁死膝关节。',
 '下放吸气，蹬起呼气。',
 '膝关节不要完全锁死，避免压力集中在关节而非肌肉。'),

('二头弯举', 'Barbell Curl', '弯举,弯举杠铃,手臂', 1, 2, 2, '肱二头肌', '前臂', 1,
 '站姿，双手肩宽握杠，肘部固定体侧；仅靠肘关节弯曲将杠铃举至肩前；缓慢下放至手臂接近伸直。',
 '甩腰借力（cheat curl）；肘部前移让三角肌前束代偿；下放过快。',
 '弯举时呼气，下放时吸气。',
 '肘部位置必须固定，前移会让发力转移到肩部。'),

('绳索下压', 'Triceps Pushdown', '三头下压,臂屈伸,tricep', 5, 2, 1, '肱三头肌', '', 1,
 '站姿，双手握绳或直杆，肘部夹紧体侧；仅靠肘关节伸展向下压至手臂接近伸直；缓慢回到起始位。',
 '肘部外展让背阔肌代偿；身体前倾借力；下压过猛。',
 '下压时呼气，回位时吸气。',
 '肘部全程贴住躯干是发力的关键。');
