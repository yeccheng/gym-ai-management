package com.gym.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.system.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 登录查询。必须显式加 deleted = 0——
     * @TableLogic 只对 MyBatis-Plus 自带方法生效，
     * 手写 SQL 不带这个条件会把已删账号查出来。
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    SysUser selectByUsername(@Param("username") String username);

    /** 手机号登录 / 找回密码 */
    @Select("SELECT * FROM sys_user WHERE phone = #{phone} AND deleted = 0 LIMIT 1")
    SysUser selectByPhone(@Param("phone") String phone);

    /** 账号框：用户名或手机号都能命中 */
    @Select("""
            SELECT * FROM sys_user
            WHERE deleted = 0
              AND (username = #{account} OR phone = #{account})
            LIMIT 1
            """)
    SysUser selectByAccount(@Param("account") String account);
}
