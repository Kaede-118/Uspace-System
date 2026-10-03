package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员修改用户资料的请求（模块 1 的管理员侧）。
 *
 * <h3>⚠️ 刻意没有 id 与 username 两个字段</h3>
 *
 * <p><b>ID 不在请求体里</b>：目标用户由路径变量指定（{@code /api/admin/users/{id}}）。
 * 把主键放进请求体，就会出现「路径说 A、请求体说 B」这种自相矛盾的请求，
 * 而改错人的代价是不可逆的 —— 用户表的主键还被订单、月卡、出入记录引着。
 *
 * <p><b>username（登录名）不入这个请求，也不可改</b>：它是登录凭据的一半，
 * 改了用户在完全不知情的情况下就登不进去了 —— 而他不会想到是「有人改了我的登录名」，
 * 只会以为账号丢了。真要改名，那是另一件事（要通知用户），不该混在资料编辑里。
 *
 * <h3>语义</h3>
 *
 * <p>与用户自助那条一样是<b>全量替换</b>：传 null 即清空。<b>QQ 号在这条路径上例外</b> ——
 * 这里允许改（这正是 {@code QQ_CHANGE_REQUIRES_ADMIN} 说的「联系管理员」的落点），
 * 但改完要过查重，不能与别人的 QQ 撞上。
 */
@Data
public class AdminUserUpdateRequest {

    /** 昵称。留空时按 QQ 号 → 登录名兜底（与注册同一套规则）*/
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /** 手机号。可留空 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * QQ 号。可留空。
     *
     * <p>⚠️ 空串与 null 都表示<b>清空</b> —— 管理员把某个用户的 QQ 解绑是这个接口的
     * 正常用法之一（比如那个号换了人）。这与用户自助路径刻意相反：
     * 那边【任何】变更都拒绝，包括清空。
     */
    @Pattern(regexp = "^$|^[1-9]\\d{4,11}$", message = "QQ 号格式不正确")
    private String qq;

    /** 游玩偏好，逗号分隔的设备类型 code。可留空 */
    @Size(max = 64, message = "游玩偏好不能超过 64 个字符")
    private String preference;
}
