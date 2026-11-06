package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 器材。
 *
 * <p>category：1 有氧 2 力量 3 自由重量 4 功能。
 * status：1 正常 2 报修 3 停用。
 * 论文(1)「器材管理：登记、报修、维护」对应 登记=save、报修=status 2、维护=status 1。
 */
@Data
@TableName("equipment")
public class Equipment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 1 有氧 2 力量 3 自由重量 4 功能 */
    private Integer category;

    private String location;

    /** 1 正常 2 报修 3 停用 */
    private Integer status;

    private LocalDate buyDate;
    private LocalDate lastMaint;

    private String note;

    @TableLogic
    private Integer deleted;
}
