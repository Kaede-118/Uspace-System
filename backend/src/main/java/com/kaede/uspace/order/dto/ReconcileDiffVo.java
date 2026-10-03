package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.entity.ReconcileDiff;
import com.kaede.uspace.order.reconcile.ReconcileDiffType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 后台对账差异明细里的一条。
 *
 * <p><b>管理员在这一页上要做的判断是「这两边为什么对不上」</b>，
 * 所以字段是围着「把两侧摆在一起看」组织的：
 * 账单说收了多少（{@link #billAmount} / {@link #billTime} / {@link #billSummary}）、
 * 系统记了多少（{@link #proofAmount} / {@link #orderNo}）、
 * 靠什么认定它们是同一笔（{@link #paymentNo}）。
 *
 * <p>{@link #diffTypeHint} 是给管理员的一句话说明「这类差异意味着什么、该做什么」——
 * 六类的处置动作完全不同，让他在页面上现推是没必要的负担。
 *
 * <p><b>{@link #handled} 用的是布尔而不是 0/1</b>：库列是 {@code TINYINT}，
 * 但前端要拿它做判断（显示「标记已处理」按钮还是处理结论），
 * 转换放在这一层比让每个使用点各判一次 {@code === 1} 好。
 */
@Data
public class ReconcileDiffVo {

    /** 差异 ID */
    private Long id;

    /** 所属批次 ID */
    private Long batchId;

    /** 类型名，取值为 {@link ReconcileDiffType} 的枚举名 */
    private String diffType;

    /** 类型的中文短标签（列表上的 chip） */
    private String diffTypeLabel;

    /** 类型的一句话说明：这类差异意味着什么、该做什么 */
    private String diffTypeHint;

    /** 相关凭证 ID，{@code BILL_ONLY} 时为空 */
    private Long proofId;

    /** 归一化后的交易单号，可空（{@code NO_PAYMENT_NO} 时为空） */
    private String paymentNo;

    /** 商户订单号快照，可空。管理员凭它直接去订单页找人 */
    private String orderNo;

    /** 收款类型名，取值见 {@link PaymentTargetType}；{@code BILL_ONLY} 时为空 */
    private String targetType;

    /** 收款类型的中文名 */
    private String targetTypeLabel;

    /** 账单侧金额（元），可空 */
    private BigDecimal billAmount;

    /** 系统侧凭证金额（元），可空 */
    private BigDecimal proofAmount;

    /** 账单上这笔交易的时刻，可空 */
    private LocalDateTime billTime;

    /** 账单侧的商品或交易对方摘要，可空 */
    private String billSummary;

    /** 是否已处理 */
    private boolean handled;

    /** 处理备注，可空 */
    private String handleNote;

    /** 处理人管理员 ID，未处理时为空 */
    private Long handledBy;

    /** 处理人管理员昵称，未处理或查不到时为空 */
    private String handledByName;

    /** 处理时刻，未处理时为空 */
    private LocalDateTime handledAt;

    /** 产生时刻 */
    private LocalDateTime createdAt;

    /**
     * 由实体组装视图。
     *
     * @param diff          差异实体，不可为 null
     * @param handledByName 处理人昵称，未处理或查不到时传 null
     * @return 视图对象
     */
    public static ReconcileDiffVo from(ReconcileDiff diff, String handledByName) {
        ReconcileDiffVo vo = new ReconcileDiffVo();
        vo.setId(diff.getId());
        vo.setBatchId(diff.getBatchId());
        vo.setDiffType(diff.getDiffType());
        vo.setDiffTypeLabel(ReconcileDiffType.labelOf(diff.getDiffType()));
        vo.setDiffTypeHint(hintOf(diff.getDiffType()));
        vo.setProofId(diff.getProofId());
        vo.setPaymentNo(diff.getPaymentNo());
        vo.setOrderNo(diff.getOrderNo());
        vo.setTargetType(diff.getTargetType());
        vo.setTargetTypeLabel(diff.getTargetType() == null
                ? null
                : PaymentTargetType.labelOf(diff.getTargetType()));
        vo.setBillAmount(diff.getBillAmount());
        vo.setProofAmount(diff.getProofAmount());
        vo.setBillTime(diff.getBillTime());
        vo.setBillSummary(diff.getBillSummary());
        vo.setHandled(diff.getHandled() != null && diff.getHandled() == 1);
        vo.setHandleNote(diff.getHandleNote());
        vo.setHandledBy(diff.getHandledBy());
        vo.setHandledByName(handledByName);
        vo.setHandledAt(diff.getHandledAt());
        vo.setCreatedAt(diff.getCreatedAt());
        return vo;
    }

    /**
     * 取类型说明。
     *
     * <p>认不出的取值返回 null 而不是原样回显 —— 说明是整句话，
     * 拿一个枚举名去当说明只会让页面多出一行乱码似的东西。
     *（标签那一栏是另一回事：认不出时原样显示，那才有诊断价值。）
     *
     * @param name 类型名，可为 null
     * @return 说明文本；认不出时返回 null
     */
    private static String hintOf(String name) {
        for (ReconcileDiffType type : ReconcileDiffType.values()) {
            if (type.name().equals(name)) {
                return type.getHint();
            }
        }
        return null;
    }
}
