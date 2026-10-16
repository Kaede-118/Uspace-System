package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentProofStatus;
import com.kaede.uspace.order.entity.PaymentProof;
import lombok.Data;

/**
 * 某一笔收款的凭证状态（用户端查「我交过图没有、管理员看过没有」）。
 *
 * <p><b>它服务的是「延后交付」那两类的用户端显示</b>：包场与月卡提交凭证之后，
 * 目标仍停在待支付（那两类的交付发生在复核通过那一刻），页面上于是显示成
 * 「待支付 + 去支付」—— 而用户明明已经把钱转出去了。有了这个查询，
 * 前端就能把那一笔显示成「待复核」并把支付按钮收起来。
 *
 * <p>⚠️ <b>状态是从凭证那张表读的，而不是给订单加一个状态位</b>：
 * 「在等复核」这件事的唯一事实就是「有一条 {@code SUBMITTED} 的凭证」——
 * 在订单上再存一份的话，两个状态漂移时没有任何东西会报错
 *（订单说待复核、凭证其实已被驳回），而那种错只会以「用户白等一场」的形式出现。
 * 这与「驳回退的是状态而非交付物」是同一套取舍：事实只留一处。
 *
 * <p><b>没有凭证时也是一个正常结果</b>（三个字段全 null），不是 404 ——
 * 月卡页与包场页每次加载都会问一次，「还没交图」是最常见的情形，
 * 不该在浏览器控制台留下一片红。这也与 {@code GET /api/payment-proofs/rejected}
 * 返回空列表同一条契约。
 *
 * <p>⚠️ <b>不带 {@code proofUrl}</b>：那是用户自己刚传的图，回显没有意义。
 */
@Data
public class ProofStatusVo {

    /** 复核状态名：{@code SUBMITTED}（待复核）/ {@code CONFIRMED} / {@code REJECTED}；没有凭证时为 null */
    private String verifyStatus;

    /** 状态中文名（待复核 / 已核对 / 未通过）；没有凭证时为 null */
    private String verifyStatusLabel;

    /** 管理员填写的驳回原因；非驳回态为 null */
    private String rejectReason;

    /**
     * 由凭证实体组装视图。
     *
     * <p>与 {@code RejectedProofVo.from} 同一套做法：视图对象自己不去查库，
     * 需要的信息由调用方已经取到的实体给出。
     *
     * @param proof 凭证实体；<b>可为 null</b>（那一笔还没有凭证）
     * @return 视图对象；{@code proof} 为 null 时三个字段都是 null
     */
    public static ProofStatusVo from(PaymentProof proof) {
        ProofStatusVo vo = new ProofStatusVo();
        if (proof == null) {
            return vo;
        }
        vo.setVerifyStatus(proof.getVerifyStatus());
        vo.setVerifyStatusLabel(PaymentProofStatus.labelOf(proof.getVerifyStatus()));
        vo.setRejectReason(proof.getRejectReason());
        return vo;
    }
}
