package com.kaede.uspace.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账批次实体，对应 {@code biz_reconcile_batch} 表。
 *
 * <p>一次对账 = 管理员上传一个账单文件 + 系统跑一次匹配，产出<b>一条本记录</b>
 * 与若干条 {@link ReconcileDiff}。这是支付改造 Phase 5 的产物，
 * 见 {@code docs/开发约定与设计说明.md} 第九章。
 *
 * <p><b>刻意不继承 {@code BaseEntity}</b>（因此没有 {@code deleted} 列），
 * 理由与 {@link PaymentProof} 完全一致：它是财务过程的记录，语义上 append-only。
 * 而且 {@code biz_payment_proof.reconcile_batch_id} 指回本表，
 * 软删会让「这条凭证属于哪一批」指向一条看起来不存在的记录。
 * 传错文件也<b>不做撤销</b> —— 后果只是多一条批次与少量差异，
 * 不值得为它引入删除语义。
 *
 * <p><b>表里没有 {@code updated_at}</b>：一条批次产生之后不再改动，
 * 唯一会变的是 {@code matched_count}，而那一条由 Mapper 的定向 UPDATE 负责，
 * 不需要审计列。
 */
@Data
@TableName("biz_reconcile_batch")
public class ReconcileBatch {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /** 所属门店 ID。当前是单门店，留着是为了将来开分店时各店对各自的账 */
    private Long storeId;

    /**
     * 钱从哪导出来的，取值见 {@code ReconcileChannel}。
     *
     * <p><b>由表头认出来的，不是管理员选的</b> —— 让他在上传时多选一个东西，
     * 就多一个选错的机会，而选错的后果是数据错且不会报错。
     * 认错的代价也有限：它只影响报表归类，因为三种格式共用同一套解析逻辑。
     */
    private String channel;

    /** 上传时的原始文件名。展示用，也是下载时 {@code Content-Disposition} 的文件名来源 */
    private String fileName;

    /**
     * 账单原文件的落盘相对路径，可为空。
     *
     * <p><b>为空不代表对账失败</b>：落盘只是留档，失败时只记 warn 不阻断 ——
     * 管理员的核心目的是把账对起来，为次要目标让主要目标失败是本末倒置。
     * 前端据此把下载按钮置灰（视图里对应 {@code hasBillFile}）。
     */
    private String billFilePath;

    /** 文件内容的 SHA-256，供事后辨认「这两个批次是不是同一份文件」—— 文件名可以随便改，内容不会 */
    private String fileSha256;

    /** 账单内容里最早的交易时间。取自记录而非文件名，一条有效记录都没有时为空 */
    private LocalDateTime periodStart;

    /** 账单内容里最晚的交易时间 */
    private LocalDateTime periodEnd;

    /**
     * 系统侧凭证的候选窗口下界。
     *
     * <p><b>存下来是为了事后答得了「为什么这条凭证没被算进来」</b>——
     * 窗口是配置项算出来的（{@code periodStart − window-before-days}），
     * 而那笔账过去之后，光看一条批次记录是推不回去的。
     */
    private LocalDateTime windowStart;

    /** 系统侧凭证的候选窗口上界，见 {@link #windowStart} */
    private LocalDateTime windowEnd;

    /** 账单侧解析出的「收入且成功」笔数 */
    private Integer billCount;

    /** 账单侧收入合计（元） */
    private BigDecimal billAmount;

    /**
     * 账单里<b>未参与对账</b>的笔数，两类合在一起：收支方向不是「收入」的
     *（支出与「不计收支」），以及交易类型不在白名单里的（个人账单里的转账、
     * 红包、别处买东西的退款）。
     *
     * <p><b>这个数字是「账单合计与系统对不上」的唯一解释</b>：一份个人收款账单上
     * 写着收了 47764 元，而系统里只认了 6 元 —— 差额全在这里。页面上要显示它，
     * 否则管理员只看得到「账单 3 笔」，连那个文件原本有 40 行都不知道。
     *
     * <p>只计数、不参与匹配：它们每一笔都找不到对应凭证，放进去只会变成一屏假差异。
     */
    private Integer billExcludedCount;

    /**
     * 账单侧因「单号已被之前的批次认领过」而跳过的笔数。
     *
     * <p><b>这个数字是「重传同一份文件」时唯一能解释「为什么匹配 0 笔」的东西</b> ——
     * 没有它，管理员看到一屏 0 会以为系统坏了。
     */
    private Integer billSkippedCount;

    /** 系统侧参与比对的凭证数（落在窗口内、未被认领、未被驳回） */
    private Integer proofCount;

    /** 系统侧参与比对的凭证金额合计（元） */
    private BigDecimal proofAmount;

    /** 落在窗口内但已被之前批次认领、本次跳过的凭证数 */
    private Integer proofSkippedCount;

    /** 匹配成功的笔数（单号相符 + 金额相符，已回写 {@code reconcile_batch_id}） */
    private Integer matchedCount;

    /** 匹配成功的金额合计（元） */
    private BigDecimal matchedAmount;

    /** 本批次写入的差异条数（已去重） */
    private Integer diffCount;

    /** 执行对账的管理员 ID */
    private Long createdBy;

    /**
     * 对账时刻。插入时由 {@code AuditMetaObjectHandler} 自动填充，业务代码不要赋值。
     *
     * <p>本表不继承 {@code BaseEntity}，这一列靠字段上的
     * {@code @TableField(fill = ...)} 单独指定 —— 与 {@link PaymentProof} 同一套做法。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
