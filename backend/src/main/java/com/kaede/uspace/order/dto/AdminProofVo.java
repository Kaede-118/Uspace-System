package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentProofStatus;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.entity.PaymentProof;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 后台待复核列表里的一条凭证。
 *
 * <p><b>管理员在这一页上要做的判断是「这笔钱到底收到没有」</b>，
 * 所以字段是围着这个判断组织的：截图（{@link #proofUrl}）、
 * 该收多少（{@link #amount}）、用户说付了多少（{@link #ocrAmount}）、
 * 流水号对不对得上（{@link #paymentNo}）、这是哪类收款（{@link #targetTypeLabel}）、
 * 谁提交的（{@link #userNickname}）。
 *
 * <p><b>{@link #delivered} 是这一页最要紧的一个字段</b>：订单与商品
 * 「提交即交付」—— 用户提交的那一刻系统就落账、扣库存了。这两类若复核不通过，
 * <b>系统不会自动回退</b>（钱已入账、货已扣），只能人工处置。
 * 后台必须把这类条目显著标出来，否则管理员点下「未通过」之后会以为
 * 事情结束了，而实际上还欠一步人工回退。
 *
 * <p>{@link #risk} 同理：同一流水号被多笔凭证引用，是最需要先看一眼的一类。
 */
@Data
public class AdminProofVo {

    /** 凭证 ID */
    private Long id;

    /** 收款类型名，取值为 {@link PaymentTargetType} 的枚举名 */
    private String targetType;

    /** 收款类型的中文名（订单 / 包场 / 月卡 / 商品） */
    private String targetTypeLabel;

    /** 目标 ID。管理员要跳过去看那条单子时用它 */
    private Long targetId;

    /** 商户订单号快照 */
    private String orderNo;

    /** 提交人用户 ID */
    private Long userId;

    /**
     * 提交人昵称。
     *
     * <p>只在<b>这一处</b>补上：凭证表里存的是 {@code user_id}，
     * 而管理员认人靠昵称。由 Service 批量查一次 {@code sys_user} 填进来 ——
     * 逐条查会变成 N+1，一页 20 条就是 20 次查询。
     */
    private String userNickname;

    /** 提交时的应付额（元） */
    private BigDecimal amount;

    /** 付款截图的站内路径，直接给 {@code <img :src>} */
    private String proofUrl;

    /** 用户确认后的交易流水号，可空 */
    private String paymentNo;

    /** OCR 识别出的流水号（Phase 4 起有值），可空 */
    private String ocrPaymentNo;

    /** OCR 识别出的金额（Phase 4 起有值），可空 */
    private BigDecimal ocrAmount;

    /** OCR 原始文本（Phase 4 起有值），仅供复核参考 */
    private String ocrText;

    /**
     * 扫的是哪张收款码的名字，可空。
     *
     * <p>店里有多个收款账号时，管理员核对账单要靠它分清「这笔钱进了谁的口袋」。
     * 收款码改名后这里显示的是<b>当前</b>的名字（凭证上存的是 ID）——
     * 名字变了不影响追溯，ID 才是锚点。
     */
    private String payQrName;

    /** 复核状态名，见 {@link PaymentProofStatus} */
    private String verifyStatus;

    /** 复核状态的中文名 */
    private String verifyStatusLabel;

    /** 风险标记原始值，如 {@code DUPLICATE_PAYMENT_NO}；无风险时为空 */
    private String riskFlag;

    /** 是否有风险标记，供前端排序与高亮（不必自己判字符串非空） */
    private boolean risk;

    /**
     * 这类收款是否「提交即交付」。
     *
     * <p>为 true 时，这条凭证被驳回<b>不代表事情结束</b> ——
     * 目标已经落账（订单已 PAID、库存已扣），需要人工回退。
     */
    private boolean delivered;

    /** 提交时间 */
    private LocalDateTime createdAt;

    /** 复核时刻，未复核时为空 */
    private LocalDateTime confirmedAt;

    /**
     * 复核管理员 ID，未复核时为空。
     *
     * <p>⚠️ <b>为 null 但状态是「已核对」时，说明那次通过是【机器自动】做的</b>
     * （识别到单号、金额相符、且单号无重复引用 —— 见
     * {@code PaymentProofService} 的三道闸）。前端据此把「机器自动通过」
     * 与「管理员核对」分开显示，并允许管理员对前者补一次人工驳回
     * （后端驳回守卫同样认这个区分）。
     */
    private Long confirmedBy;

    /**
     * 这条凭证被哪一批对账认领过；null 表示还没被任何批次对到。
     *
     * <p><b>它与 {@link #verifyStatus} 是两件事，不要互相推断</b>：
     * 那是「管理员（或机器）看过截图认下的结论」，这是「与收款账单勾稽上了」。
     * 后台因此能把<b>「到账」与「对账确认」分开显示</b> —— 对账不认定到账
     *（管理员先认了、账单隔月才导出），到账也不代表账勾上了。
     */
    private Long reconcileBatchId;

    /** 未通过原因，驳回时才有值 */
    private String rejectReason;

    /**
     * 由实体组装视图。
     *
     * <p>参数里那三项（昵称、收款码名、是否即交付）都是凭证表之外的信息，
     * 由 Service 查好后传进来 —— 视图对象自己不去查库。
     *
     * @param proof        凭证实体，不可为 null
     * @param userNickname 提交人昵称，查不到时传 null
     * @param payQrName    收款码显示名，未记录或已被删时传 null
     * @param delivered    该收款类型是否「提交即交付」
     * @return 视图对象
     */
    public static AdminProofVo from(PaymentProof proof, String userNickname,
                                    String payQrName, boolean delivered) {
        AdminProofVo vo = new AdminProofVo();
        vo.setId(proof.getId());
        vo.setTargetType(proof.getTargetType());
        vo.setTargetTypeLabel(PaymentTargetType.labelOf(proof.getTargetType()));
        vo.setTargetId(proof.getTargetId());
        vo.setOrderNo(proof.getOrderNo());
        vo.setUserId(proof.getUserId());
        vo.setUserNickname(userNickname);
        vo.setAmount(proof.getAmount());
        vo.setProofUrl(proof.getProofUrl());
        vo.setPaymentNo(proof.getPaymentNo());
        vo.setOcrPaymentNo(proof.getOcrPaymentNo());
        vo.setOcrAmount(proof.getOcrAmount());
        vo.setOcrText(proof.getOcrText());
        vo.setPayQrName(payQrName);
        vo.setVerifyStatus(proof.getVerifyStatus());
        vo.setVerifyStatusLabel(PaymentProofStatus.labelOf(proof.getVerifyStatus()));
        vo.setRiskFlag(proof.getRiskFlag());
        vo.setRisk(proof.getRiskFlag() != null && !proof.getRiskFlag().isBlank());
        vo.setDelivered(delivered);
        vo.setCreatedAt(proof.getCreatedAt());
        vo.setConfirmedAt(proof.getConfirmedAt());
        vo.setConfirmedBy(proof.getConfirmedBy());
        vo.setRejectReason(proof.getRejectReason());
        vo.setReconcileBatchId(proof.getReconcileBatchId());
        return vo;
    }
}
