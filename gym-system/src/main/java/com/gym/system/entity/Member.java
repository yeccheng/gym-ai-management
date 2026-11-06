package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 会员档案。
 *
 * <p>status 状态机：1 正常 / 2 挂失 / 3 冻结 / 4 注销。
 * 论文(1)「会员管理：注册、信息维护、挂失、冻结、注销」即对应
 * 注册=save、信息维护=updateById、其余三个=改 status。
 */
@Data
@TableName("member")
public class Member {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String phone;

    /** 0 未知 1 男 2 女 */
    private Integer gender;

    private LocalDate birthday;
    private BigDecimal heightCm;
    private BigDecimal weightKg;

    /** 1 增肌 2 减脂 3 力量 4 塑形 5 健康 */
    private Integer goal;

    /** 1 新手 2 中级 3 高级 */
    private Integer trainLevel;

    private LocalDate joinDate;

    /** 1 正常 2 挂失 3 冻结 4 注销 */
    private Integer status;

    @TableLogic
    private Integer deleted;
}
