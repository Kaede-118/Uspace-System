package com.kaede.uspace.user;

import java.util.Arrays;
import java.util.Optional;

/**
 * 用户角色（模块 2 权限控制的基础）。
 *
 * <p>取值与 {@code sys_user.role} 列一致。Spring Security 的
 * {@code @PreAuthorize("hasRole('ADMIN')")} 依赖这个字符串，
 * 因此<b>枚举名即权限标识，不可随意改名</b> —— 改了要同步改所有注解。
 *
 * <p>刻意手写 {@code final} 字段与 getter，不用 Lombok：
 * 枚举的写法简单直白，加注解反而多一层需要理解的间接。
 *
 * @see com.kaede.uspace.user.entity.SysUser#getRole()
 */
public enum UserRole {

    /** 普通用户。到场消费的顾客，能管理自己的资料与订单 */
    USER("普通用户"),

    /** 管理员。能进运营后台，管理用户、房间、设备与订单 */
    ADMIN("管理员");

    /** 中文名称，用于界面展示与管理端列表 */
    private final String label;

    UserRole(String label) {
        this.label = label;
    }

    /**
     * 取中文名称。
     *
     * @return 中文名称，如「管理员」
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按名称解析角色，用于校验外部传入的角色值。
     *
     * <p>不接受 null，也不做大小写兼容 —— 角色值只会由前端从固定选项里传，
     * 出现大小写不一致说明调用方有问题，应当尽早暴露而不是宽容地接受。
     *
     * @param name 角色名，如 {@code ADMIN}
     * @return 解析成功时返回对应角色；名称不合法时返回空
     */
    public static Optional<UserRole> parse(String name) {
        return Arrays.stream(values())
                .filter(role -> role.name().equals(name))
                .findFirst();
    }
}
