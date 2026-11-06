package com.gym.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 体测记录。
 *
 * <p>体重是力量之外最诚实的一个指标：它不受当天状态影响，只反映热量收支的长期结果。
 * 减脂期看的是「几周的移动平均」而不是单日数字——
 * 单日波动 1~2 公斤几乎全是水分和肠道内容物，
 * 拿单日数据去劝用户「你这周长胖了」，属于用噪声下结论。
 */
@Data
@TableName("body_record")
public class BodyRecord {

    /**
     * 注意这里与其它表不同：本表主键走数据库自增。
     * 体测记录是用户低频、少量、且需要按日期去重的数据，
     * 用雪花 ID 没有意义，自增更省空间也便于按插入顺序看趋势。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private LocalDate recordDate;
    private BigDecimal weightKg;
    private BigDecimal bodyFatPct;
    private BigDecimal skeletalMuscleKg;
    private String note;
    private LocalDateTime createTime;
}
