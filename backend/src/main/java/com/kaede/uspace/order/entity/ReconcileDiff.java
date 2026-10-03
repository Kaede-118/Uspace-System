package com.kaede.uspace.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账差异实体，对应 {@code biz_reconcile_diff} 表。
 *
 * <p>一条差异 = 一次对账里「系统记的」与「账单说的」对不上的一处。
 * 类型取值见 {@code ReconcileDiffType}，六类的语义与优先级都写在那个枚举上。
 *
 * <p><b>本表只记录「标记 + 备注」，不做任何反向操作</b>：
 * 不生成订单状态变更、不代提交凭证、不撤销批次。差异只是给人看的线索，
 * 钱怎么处置由人决定 —— 与「资金动作入口越少越好」是同一条原则
 *（QQ 机器人只读、订单人工核销接口被删，依据都是它）。
 *
 * <p><b>刻意不继承 {@code BaseEntity}</b>（没有 {@code deleted} 列），
 * 理由见 {@link ReconcileBatch} 的类注释。
 *
 * <p><b>也没有 {@code updated_at}</b>：差异会被处理一次（{@code handled} 由 0 变 1），
 * 那一次改写记在 {@code handled_at} 上，比一个泛泛的更新时间有用得多。
 */
@Data
@TableName("biz_reconcile_diff")
public class ReconcileDiff {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /** 所属对账批次（{@code biz_reconcile_batch.id}） */
    private Long batchId;

    /** 差异类型，取值见 {@code ReconcileDiffType} */
    private String diffType;

    /**
     * 相关凭证（{@code biz_payment_proof.id}），可为空。
     *
     * <p>{@code BILL_ONLY} 时为空 —— 那一类说的正是「系统里没有任何凭证认领这笔钱」，
     * 没有一个凭证可以指。
     */
    private Long proofId;

    /**
     * <b>归一化后</b>的交易单号（见 {@code ReconcileTexts#normalize}）。
     *
     * <p>与 {@code biz_payment_proof.payment_no} 的原始值可能差在空白、全角字符或大小写上 ——
     * 这一列的定义就是「比对用的那个号」，所以它存的是归一化的结果。
     * 要展示原始的单号，从关联的凭证或账单文件里看。
     */
    private String paymentNo;

    /** 商户订单号快照，抄自凭证。管理员凭它直接去订单页找人，不必先点开凭证 */
    private String orderNo;

    /** 收款类型快照（{@code ORDER} / {@code BOOKING} / {@code MONTHLY_CARD} / {@code PRODUCT}），抄自凭证 */
    private String targetType;

    /** 账单侧金额（元）；{@code PROOF_ONLY} / {@code NO_PAYMENT_NO} 时为空 */
    private BigDecimal billAmount;

    /** 系统侧凭证金额（元）；{@code BILL_ONLY} 时为空 */
    private BigDecimal proofAmount;

    /** 账单上这笔交易的时刻；账单侧无记录时为空 */
    private LocalDateTime billTime;

    /** 账单侧的商品或交易对方摘要，供人工判断时多一个上下文；账单侧无记录时为空 */
    private String billSummary;

    /**
     * 是否已处理：0 = 待处理，1 = 已处理。
     *
     * <p>用 {@code Integer} 而不是 {@code Boolean}，与 {@code biz_product.enabled}、
     * {@code biz_notice.pinned} 同一套：库列是 {@code TINYINT}，
     * 映射成 Boolean 会引入一层隐式转换。
     *
     * <p><b>只有两态，没有「处理中」</b>：管理员要么还没看，要么看完了写下结论。
     * 也<b>不提供「取消已处理」</b>—— 那会让这一列变成可反复翻转的状态、多一个入口，
     * 而标错了的条目仍然筛得出来、看得见。
     */
    private Integer handled;

    /**
     * 处理备注，选填。
     *
     * <p>与凭证的 {@code reject_reason} 刻意不同：那个<b>必填</b>，因为它要展示给顾客，
     * 空着的话顾客反复试也猜不出哪里不对；这个<b>选填</b>，因为它是管理员给自己的备忘。
     * 强制填的后果很具体：管理员会对 50 条「没填流水号」逐个打上「已处理」三个字，
     * 然后这个功能就没人用了。
     */
    private String handleNote;

    /** 处理人管理员 ID */
    private Long handledBy;

    /** 处理时刻 */
    private LocalDateTime handledAt;

    /**
     * 产生时刻。插入时由 {@code AuditMetaObjectHandler} 自动填充，业务代码不要赋值。
     *
     * <p>本表不继承 {@code BaseEntity}，这一列靠字段上的
     * {@code @TableField(fill = ...)} 单独指定 —— 与 {@link PaymentProof} 同一套做法。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
