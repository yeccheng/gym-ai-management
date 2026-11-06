package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 会员卡。
 *
 * <p>card_type：1 次卡 2 月卡 3 年卡 4 私教课包。
 * status：1 有效 2 已用完 3 已过期 4 已停用。
 * version 用于消课并发控制（乐观锁）。
 */
@Data
@TableName("membership_card")
public class MembershipCard {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;
    private String cardName;

    /** 1 次卡 2 月卡 3 年卡 4 私教课包 */
    private Integer cardType;

    private Integer totalCount;
    private Integer remainCount;

    private LocalDate startDate;
    private LocalDate endDate;

    private BigDecimal price;

    /** 1 有效 2 已用完 3 已过期 4 已停用 */
    private Integer status;

    /** 乐观锁，消课并发控制 */
    private Integer version;
}
