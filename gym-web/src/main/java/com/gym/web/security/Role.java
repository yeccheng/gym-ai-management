package com.gym.web.security;

/**
 * 角色枚举。role 字段在库里存的是 TINYINT，这里做一层映射——
 * 好处是改角色名只改枚举，不用动数据库和历史 Token。
 */
public enum Role {

    ADMIN(1, "ADMIN"),
    COACH(2, "COACH"),
    MEMBER(3, "MEMBER");

    private final int code;
    private final String name;

    Role(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int code() {
        return code;
    }

    public String roleName() {
        return name;
    }

    /** 库里存的 code 转成 Shiro 角色名（无 ROLE_ 前缀） */
    public static String roleNameOf(Integer code) {
        for (Role role : values()) {
            if (role.code == (code == null ? -1 : code)) {
                return role.name;
            }
        }
        return MEMBER.name;
    }

    /** @deprecated 使用 {@link #roleNameOf(Integer)}；保留兼容旧调用 */
    @Deprecated
    public static String authorityOf(Integer code) {
        return "ROLE_" + roleNameOf(code);
    }
}
