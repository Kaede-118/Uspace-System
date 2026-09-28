package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料的请求。
 *
 * <p><b>语义是全量替换（PUT），不是部分更新</b>：字段传 null 表示「清空该项」，
 * 不传与传 null 是一回事。前端提交表单时会把当前所有值一起带上，
 * 因此这个语义与表单的实际行为一致。
 *
 * <p>若将来需要「只改一个字段、其余原样保留」的语义，应当另开 PATCH 接口，
 * 而不是在这里把 null 重新解释成「不修改」—— 那样会让「想清空手机号」
 * 这个需求变得无法表达，且同一份请求体的含义会随实现漂移。
 *
 * <p><b>用户名不在可改范围内</b>：它是登录凭据与对账依据，
 * 改了会让历史订单的对账线索断掉。昵称才是展示用的名字。
 */
@Data
public class UpdateProfileRequest {

    /**
     * 昵称。
     *
     * <p>留空时按三级规则重新兜底（QQ 号 → 用户名），不会真的存成空 ——
     * 昵称是 QQ 群播报与后台列表的标识，空值会让播报失去意义。
     */
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /** 手机号。传 null 或空串表示清除 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** QQ 号。传 null 或空串表示解除绑定；填写了则必须未被他人占用 */
    @Pattern(regexp = "^$|^[1-9]\\d{4,11}$", message = "QQ 号格式不正确")
    private String qq;

    /** 游玩偏好，逗号分隔的设备类型 code。传 null 或空串表示不设置 */
    @Size(max = 64, message = "游玩偏好不能超过 64 个字符")
    private String preference;
}
