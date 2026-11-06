package com.gym.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动作库。
 *
 * <p>它是 RAG 的私有语料源——外部没有现成的中文健身动作数据集，
 * 这正是本系统 RAG 成立的前提：召回的东西通用大模型不知道。
 */
@Data
@TableName("exercise")
public class Exercise {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    private String nameEn;

    /** 别名，用于口语化召回：「练胸外侧」要能命中「哑铃飞鸟」 */
    private String alias;

    /** 1杠铃 2哑铃 3固定器械 4自重 5绳索 6壶铃 */
    private Integer equipment;

    /** 1复合 2孤立 */
    private Integer movementType;

    /** 1推 2拉 3蹲 4髋铰链 5核心 6单侧 */
    private Integer movementPattern;

    private String primaryMuscle;
    private String secondaryMuscle;
    private Integer difficulty;

    /**
     * 动作分级（搜「卧推」时用）：1 基础主动作，2 变体，3 辅助孤立。
     * 默认 2，避免新动作抢基础动作的位置。
     */
    private Integer actionLevel;

    private String steps;
    private String commonMistakes;
    private String breathing;
    private String safetyTips;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
