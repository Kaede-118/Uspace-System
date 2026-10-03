package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 提交付款凭证的请求体。
 *
 * <p><b>四类收款共用一个入口</b>（订单 / 包场 / 月卡 / 商品），靠
 * {@link #targetType} + {@link #targetId} 指明是哪一笔。这是「把入口收在
 * 『支付』这个动作上」那套设计的延续 —— 加一类收款不必新开端点，
 * 也不必让前端再写一遍上传界面。
 *
 * <p>提交之后会发生什么<b>取决于收款类型</b>，不是一刀切：订单与商品
 * 「提交即交付」（当场落账、库存当场扣、欠费拦截当场解除），
 * 包场与月卡则要等管理员复核。这条差异由
 * {@code PaymentTargetHandler#deliverOnSubmit} 声明，前端拿
 * {@code ProofSubmitVo.delivered} 的结果原样展示，<b>不要自己按类型判断</b>。
 */
@Data
public class ProofSubmitRequest {

    /**
     * 收款类型。取值见 {@link PaymentTargetType}。
     *
     * <p>用枚举而不是字符串：非法取值会被 Jackson 在进 Controller 之前挡掉
     * （400），不必在 Service 里再判一次。
     */
    @NotNull(message = "请指明收款类型")
    private PaymentTargetType targetType;

    /** 目标主键（订单 ID / 包场 ID / 月卡购买单 ID / 商品购买单 ID） */
    @NotNull(message = "请指明收款目标")
    private Long targetId;

    /**
     * 付款截图的站内路径，形如 {@code /uploads/proof/xxxx.jpg}。
     *
     * <p><b>它一定会被校验前缀</b>（见 {@code PaymentProofService}）：
     * 请求体是客户端给的，不校验就能塞一个外链 —— 管理员的浏览器会去加载
     * 别人的服务器，等于把「谁在什么时候付款」泄露给第三方；
     * 也能塞 {@code /uploads/avatar/xxx.jpg} 把别人的头像当成付款凭证。
     * 上传接口返回什么就提交什么，不要自己拼。
     */
    @NotBlank(message = "请上传付款截图")
    @Size(max = 255, message = "付款截图路径不能超过 255 个字符")
    private String proofUrl;

    /**
     * 交易流水号，<b>可空</b>。
     *
     * <p>用户从微信 / 支付宝的付款详情页里抄过来。允许不填：有些收款方式
     * 确实没有流水号，硬性必填只会逼着用户瞎填一串，那比空着更糟 ——
     * 空着至少能看出来「这条没法自动对账」。
     *
     * <p>填了的话它有两个用途：复核时管理员拿它去账单里核对，
     * 以及对账时作为主键（Phase 5）。<b>同一个流水号被两笔凭证引用会被标记
     * 风险</b>（一张截图付两单），标记写在后端，不需要前端参与。
     */
    @Size(max = 64, message = "交易流水号不能超过 64 个字符")
    private String paymentNo;

    /**
     * 上传截图时识别出的交易单号，<b>可空</b>。
     *
     * <p>与 {@link #paymentNo} 分开两个字段、不合并：用户可以在识别结果上手工修改，
     * 那时以 {@code paymentNo} 为准，而「机器当初读出了什么」仍要留着 ——
     * 它既能解释「用户为什么会填成这个」，也是识别质量的观测数据。
     *
     * <p>⚠️ <b>这三个 OCR 字段与上面的字段有个本质区别：它们由客户端回传，
     * 服务端无从核对。</b> 之所以接受，是因为它们被明确定位成<b>辅助线索</b> ——
     * 复核永远是人做的，管理员看的始终是那张截图，系统里没有任何自动判断
     * 依赖这三个值。将来若要加「识别金额相符就自动通过」之类的逻辑，
     * <b>必须先改成服务端自己识别</b>，否则等于把放行权交给了请求体。
     *
     * <p>也正因如此，这里<b>不加 {@code @Size} 之类的校验</b>：这些值不是用户填的，
     * 他改不了也不该为它们负责。真超长时由 Service 侧截断 —— 因为辅助信息
     * 没对上格式而让「提交付款凭证」整个失败，是用户完全无法自救的一种报错。
     */
    private String ocrPaymentNo;

    /** 识别出的金额（元），可空。与应付额不一致时后台值得多看一眼 */
    private BigDecimal ocrAmount;

    /** 识别出的原文，可空。仅后台复核时展示 */
    private String ocrText;

    /**
     * 扫的是哪张收款码，可空。
     *
     * <p>页面上只有一张码时前端不一定回传；店里有多个收款账号时回传它，
     * 钱进了谁的口袋事后才追溯得到。传了就校验它确实是本店启用中的码。
     */
    private Long payQrId;
}
