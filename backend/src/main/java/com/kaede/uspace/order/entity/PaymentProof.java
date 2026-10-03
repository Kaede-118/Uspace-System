package com.kaede.uspace.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 付款凭证实体，对应 {@code biz_payment_proof} 表。
 *
 * <p>2026-09-30 起扫码转账是 12 月投产时唯一的收款方式，本表因此是资金的
 * 唯一凭据来源：用户扫店内的收款码付款 → 上传付款截图 → 管理员复核。
 * 三条线上支付通道（微信 JSAPI / 微信 H5 / 支付宝 WAP）都因资质门槛走不通，
 * 见 {@code docs/开发约定与设计说明.md} 第九章。
 *
 * <p><b>四类收款共用一个入口</b>：订单 {@code ORDER}、包场 {@code BOOKING}、
 * 月卡 {@code MONTHLY_CARD}、商品 {@code PRODUCT}。所以本表
 * <b>不挂在任何一张业务表下面</b>，而是用 {@code target_type} + {@code target_id}
 * 指向它们 —— 加一类收款不必建一张新凭证表。语义与 {@code PaymentTarget}
 * 完全一致，两者应当一起看。
 *
 * <p><b>刻意不继承 {@code BaseEntity}</b>（因此没有 {@code deleted} 列），
 * 两条理由同时成立：
 * <ol>
 *   <li><b>凭证是财务凭据，语义上 append-only</b> —— 照
 *       {@code AccessRecord} 的先例。行一旦产生就不该消失，只该被复核状态标记</li>
 *   <li><b>逻辑删除与 {@code uk_target} 唯一键天然冲突</b>：软删的行仍占着键，
 *       删过一次就再也插不进同一目标，且失败方式是「提交时报重复」这种
 *       完全指错方向的错</li>
 * </ol>
 * 对照 {@link PayQr} —— 那张是普通运营数据，会改名、会停用，所以它继承基类。
 *
 * @see com.kaede.uspace.order.PaymentProofStatus 复核状态的取值定义
 */
@Data
@TableName("biz_payment_proof")
public class PaymentProof {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 收款类型，取值为 {@link com.kaede.uspace.order.PaymentTargetType} 的枚举名。
     *
     * <p>与 {@link #targetId} 一起构成 {@code uk_target} 唯一键，
     * 也一起指向那条业务记录。存字符串而不是枚举类型：与 {@code biz_order.status}
     * 同一套做法，库里存的就是人可读的名字，排查数据时不必回查对照表。
     */
    private String targetType;

    /** 目标主键（订单 / 包场 / 月卡购买单 / 商品购买单的 ID） */
    private Long targetId;

    /**
     * 商户订单号快照，提交那一刻从目标上抄下来。
     *
     * <p><b>为什么冗余存一份</b>：它是支付平台与对账都认识的号，
     * 而 {@code target_id} 只在本系统内有意义。管理员在后台核对凭证时，
     * 手上拿的是微信 / 支付宝的账单（上面只有流水号与金额），
     * 有一列单号才好一条条对。事后目标若被删除，这一列还在。
     */
    private String orderNo;

    /** 提交人用户 ID。归属校验的落点，也是「这个人有没有拿同一张图重复提交」的追溯依据 */
    private Long userId;

    /**
     * 提交时的应付额快照（元）。
     *
     * <p>用 {@code DECIMAL(10,2)} 与全项目的金额口径一致。
     * 快照而不是实时读目标：管理员核对的是「当时该收多少」，
     * 而目标上的金额在人工调整时长等操作后是会变的。
     */
    private BigDecimal amount;

    /**
     * 扫的是哪张收款码（{@code biz_pay_qr.id}），可为空。
     *
     * <p>店里有多个收款账号时（比如老板与老板娘各自一张微信码），
     * 靠它才能分清这笔钱进了谁的口袋。用户没选（页面上只有一张码时
     * 前端不一定回传）就是空。
     */
    private Long payQrId;

    /** 付款截图的站内路径，形如 {@code /uploads/proof/xxxx.jpg} */
    private String proofUrl;

    /**
     * 用户确认后的交易流水号，以它为准。
     *
     * <p>可空 —— 用户可以不填就提交（有些收款方式确实没有流水号，
     * 比如收到的现金）。但填了的话它就是自动对账的主键（{@code idx_payment_no}）。
     */
    private String paymentNo;

    /**
     * OCR 识别出的流水号，<b>仅作辅助线索</b>。
     *
     * <p>与 {@link #paymentNo} 分开两列、不合并：用户可以在 OCR 结果上手工修改，
     * 那时以用户改的为准，而「机器当初读出了什么」仍要留着 ——
     * 它既能解释「用户为什么会填成这个」，也是 OCR 识别质量的观测数据。
     */
    private String ocrPaymentNo;

    /** OCR 识别出的金额（元），与提交额不一致时值得人工看一眼 */
    private BigDecimal ocrAmount;

    /**
     * OCR 原始文本，仅供人工复核参考。
     *
     * <p>⚠️ <b>它对用户不可见、也不进日志</b>：付款详情页的截图里有付款人昵称等
     * 个人信息，全文打进 info 日志等于把它散到了日志系统里。后台复核页展示它
     * 是必要的（管理员本来就在看那张截图），但仅此一处。
     */
    private String ocrText;

    /**
     * 复核状态，取值见 {@link com.kaede.uspace.order.PaymentProofStatus}。
     *
     * <p>默认 {@code SUBMITTED}。驳回后重新提交会被翻回 {@code SUBMITTED}
     * 并清掉 {@link #rejectReason} —— 这条语义写在 {@code PaymentProofService}
     * 的 upsert 里，与唯一键的配合见类注释。
     */
    private String verifyStatus;

    /**
     * 风险标记，可为空。当前唯一的取值是 {@code DUPLICATE_PAYMENT_NO}。
     *
     * <p>由对账（Phase 5）写入：同一个流水号被多笔凭证引用，是纯信任制下
     * 最值得防的作弊手法（一张截图付两单）。后台列表据此把它排到最前面 ——
     * <b>不拦住提交</b>，因为真要发生也应当让人看见并判断，而不是让系统
     * 静默拒绝一笔可能的正常付款。
     */
    private String riskFlag;

    /** 命中的对账批次 ID（Phase 5 写入），未参与对账时为空 */
    private Long reconcileBatchId;

    /** 复核管理员 ID。为空表示尚未复核 */
    private Long confirmedBy;

    /** 复核时刻。为空表示尚未复核 */
    private LocalDateTime confirmedAt;

    /** 未通过原因，驳回时必填。重新提交时会被清空 */
    private String rejectReason;

    /**
     * 提交时间。插入时由 {@code AuditMetaObjectHandler} 自动填充，业务代码不要赋值。
     *
     * <p>本表不继承 {@code BaseEntity}，这两列靠字段上的
     * {@code @TableField(fill = ...)} 单独指定 —— 与 {@code AccessRecord} 同一套做法。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间。插入与更新时都由框架填充 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
