package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 驳回付款凭证的请求体。
 *
 * <p>「通过」不需要请求体（没有任何要填的），所以只有「驳回」有这一个 DTO ——
 * 不为对称而给确认接口也造一个空对象。
 *
 * <p><b>原因必填</b>：驳回之后用户会看到这条原因，他要么改图重交、
 * 要么来找管理员问。空着的话他只能反复试，而管理员那边也不记得
 * 当初为什么驳的（凭证上只留着状态，不复述当时的判断）。
 */
@Data
public class ProofRejectRequest {

    /**
     * 未通过原因，会原样展示给用户。
     *
     * <p>上限 200 与库列一致。<b>写具体的</b>：「金额对不上，截图上显示 8 元，
     * 本单应付 12 元」比「凭证无效」有用得多 —— 前者用户知道该做什么，
     * 后者只会让他再传一张一样的图。
     */
    @NotBlank(message = "请填写未通过的原因")
    @Size(max = 200, message = "原因不能超过 200 个字符")
    private String reason;
}
