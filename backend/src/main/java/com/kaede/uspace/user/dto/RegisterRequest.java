package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * <p>除用户名与密码外全部选填 —— 注册环节每多一个必填项就多一分流失，
 * 手机号、QQ 号这些等用户真正需要时（收通知、加群播报）再补也不迟。
 *
 * <p><b>注册不返回登录凭证</b>，前端注册成功后需再调一次登录接口。
 * 这样安排是为了守住模块边界：「模块 1 管用户、模块 2 管发放凭证」，
 * 注册逻辑不必反过来依赖认证逻辑，论文里的章节划分也与之对应。
 */
@Data
public class RegisterRequest {

    /**
     * 登录名。
     *
     * <p>限制为字母、数字与下划线：登录名会出现在日志、账单与后台列表里，
     * 允许任意字符会让这些地方的展示变得难以对齐，也容易与分隔符冲突。
     * 中文姓名放昵称即可。
     */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度需在 3~20 个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字与下划线")
    private String username;

    /**
     * 密码明文。
     *
     * <p>只在校验与加密时短暂存在，绝不落库、绝不记日志。
     * 长度下限取 8 位：更短的口令在离线爆破面前几乎等于没有。
     * 刻意不强制「大小写 + 数字 + 符号」的复杂度组合 ——
     * 那类规则的实际效果是让人写出 {@code Password1!} 这种既难记又好猜的口令，
     * 长度才是真正有效的强度来源。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需在 8~32 位之间")
    private String password;

    /**
     * 昵称。可留空。
     *
     * <p>留空时按三级规则兜底：填了的 → QQ 号 → 用户名。
     * 昵称<b>不允许为空</b>，因为它要在 QQ 群播报与后台列表里标识用户，
     * 空昵称会让「谁在店里」这类播报失去意义。
     */
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /** 手机号。选填，格式为中国大陆手机号 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * QQ 号。<b>必填，且必须通过群内验证</b>。
     *
     * <p>⚠️ <b>它不是选填（2026-10-01 改）</b>。原先允许留空，理由是「注册环节每多一个
     * 必填项就多一分流失」—— 那条对普通产品成立，对这个系统不成立：
     *
     * <ul>
     *   <li><b>店里没有店员。</b>顾客是谁、什么时候来的、该收多少钱，全靠系统；
     *       「不绑 QQ 也能进店」等于给自己留一个<b>找不到主的顾客</b> ——
     *       东西坏了、欠费没付、出了纠纷，都没有任何途径找到人</li>
     *   <li><b>群里找不到他。</b>模块 11 的播报与查询按 {@code sys_user.qq} 认人，
     *       没绑的人在任何群消息里都匹配不到，等于在系统里不存在</li>
     * </ul>
     *
     * <p>所以它是<b>硬门槛</b>：必填，而且必须走完群内验证
     * （{@code UserService#register} 里消费 {@code challengeId}）。
     * 库里的唯一索引 {@code uk_qq} 兜住并发重号。
     *
     * <p>正则不再允许空串 —— 空值由 {@code @NotBlank} 拦下，报错信息也更直白。
     */
    @NotBlank(message = "QQ 号不能为空")
    @Pattern(regexp = "^[1-9]\\d{4,11}$", message = "QQ 号格式不正确")
    private String qq;

    /**
     * QQ 号验证凭证（由 {@code POST /api/user/qq-verify} 签发）。选填，
     * 但<b>填了 {@link #qq} 就必须填它</b>。
     *
     * <p>⚠️ <b>刻意不做成 {@code @NotBlank}</b>：它是<b>条件必填</b>（有 QQ 才需要），
     * 而 Bean Validation 的注解表达不了「与另一个字段联动」的约束 ——
     * 强行标上去，不填 QQ 的用户也注册不了了。
     *
     * <p>真正的校验在 {@code UserService#register} 里：拿它去
     * {@code QqVerifyService#consume}，换不回一个「已验证」的 QQ 就返回
     * {@code QQ_VERIFY_REQUIRED}(40935)。
     *
     * <p><b>它不是密码，但同样是凭证</b>：谁拿到它，谁就能在验证有效期内
     * 用那个 QQ 注册一个账号。所以签发接口与状态查询接口<b>都走 POST 请求体</b>，
     * 不走 URL 查询参数 —— 后者会落进访问日志。
     */
    @Size(max = 64, message = "验证凭证长度不合法")
    private String challengeId;

    /**
     * 游玩偏好，逗号分隔的设备类型 code（如 {@code ONGEKI,MAIMAI}）。选填。
     *
     * <p>取值由模块 4 的字典表 {@code biz_equipment_type} 维护。
     * 这里只做长度限制，具体格式（空项、重复项）由 Service 校验并给出友好提示 ——
     * 正则表达式的报错信息对用户来说过于费解。
     */
    @Size(max = 64, message = "游玩偏好不能超过 64 个字符")
    private String preference;
}
