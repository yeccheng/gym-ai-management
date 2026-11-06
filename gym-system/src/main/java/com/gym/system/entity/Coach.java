package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 教练。
 */
@Data
@TableName("coach")
public class Coach {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String phone;

    /** 1 初级 2 中级 3 高级 */
    private Integer level;

    private String specialty;
    private String intro;

    @TableLogic
    private Integer deleted;
}
