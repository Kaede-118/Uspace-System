package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交支付凭证（人工核销降级路径）的请求体。
 *
 * <p><b>这是降级路径，不是主路径。</b>主路径是支付回调自动核销 ——
 * 用户在微信或支付宝里付完，平台回调本系统，订单自动转已支付，全程无人介入。
 * 上传截图这条路径存在的唯一理由，是运营方可能<b>还没有支付商户号</b>：
 * 个人主体开不了商户号，而办执照、开对公账户、域名备案是一串以月计的外部流程。
 * 在那些流程走完之前，先让生意能收钱。
 *
 * <p>它的固有缺陷（也是设计文档里写明「要主动说明」的）：
 * 截图可伪造、需要人工复核、支付流水不在系统内所以对账困难。
 * 因此一旦拿到商户号就应当切回主路径 —— 两条路径共用同一套订单状态机与字段，
 * 切换不需要改造数据模型。
 */
@Data
public class PaymentProofRequest {

    /**
     * 支付截图的存储路径。
     *
     * <p>本系统不处理文件上传本身（那是前端与对象存储的事），
     * 这里只收一个已经存好的路径 —— 后端存不下图片，也不该把二进制塞进数据库。
     *
     * <p>它会被写进 {@code biz_order.payment_proof}，列宽 255，
     * 与这里的上限一致。同时订单会被标记为 {@code QR_UPLOAD} 通道，
     * 等待管理员核销。
     */
    @NotBlank(message = "支付凭证不能为空")
    @Size(max = 255, message = "支付凭证路径不能超过 255 个字符")
    private String paymentProof;
}
