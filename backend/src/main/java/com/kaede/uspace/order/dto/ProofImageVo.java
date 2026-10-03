package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.ocr.OcrFields;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 付款截图上传的结果。
 *
 * <p>与商品封面、收款码图片是同一套语义：<b>上传只落盘、只返回路径，
 * 不写任何数据库</b>。前端拿到 {@code proofUrl} 后随提交请求一起发回，
 * 那时才写进 {@code biz_payment_proof}。
 *
 * <p>对付款凭证而言，这条「上传即写库」的替代方案尤其站不住：
 * 用户传完图还要核对流水号、可能还要改一遍再提交，
 * 上传那一刻就落库等于把「还没确认的东西」记成凭证。
 *
 * <h3>2026-09-30 起多带三个 OCR 字段</h3>
 *
 * <p>上传时顺带把截图识别一遍，把「图里写着多少钱、什么单号」回给前端：
 * 用户能一眼核对、单号还能直接预填进输入框（抄错一位正是对账困难的主要来源）。
 *
 * <p><b>这三个字段随提交请求原样回来，再落进凭证表</b> —— 也就是说不重复识别。
 * 识别是按次计费的（百度通用文字识别高精度版个人认证 1000 次/月免费），
 * 上传与提交各识别一次会让额度当月翻倍耗尽，而结果并不会因此更准。
 *
 * <p>辨识不出的那张图（以及 {@code uspace.ocr.provider=mock} 时每次）三个字段都是
 * {@code null} —— 那是正常的，前端照常让用户手填。
 *
 * @see com.kaede.uspace.order.PaymentProofImageService 产出本对象的地方
 */
@Data
public class ProofImageVo {

    /** 截图的站内路径，形如 {@code /uploads/proof/xxxx.jpg} */
    private String proofUrl;

    /**
     * 识别出的交易单号，可空。
     *
     * <p>前端用它<b>预填</b>流水号输入框，用户改不改都行 ——
     * 改过之后这里仍是机器读出的那个值，两者分列存进凭证表，
     * 管理员据此判断「这个号是机器认出来的还是人填的」。
     */
    private String ocrPaymentNo;

    /** 识别出的金额（元），可空。与应付额不一致时后台值得多看一眼 */
    private BigDecimal ocrAmount;

    /** 识别出的原文，可空。仅回给上传者本人与后台复核用，不进日志 */
    private String ocrText;

    /**
     * 构造上传结果。
     *
     * @param proofUrl 截图的站内路径
     * @param fields   识别结果，什么都没认出时传 {@link OcrFields#EMPTY}，不可为 null
     * @return 视图对象
     */
    public static ProofImageVo of(String proofUrl, OcrFields fields) {
        ProofImageVo vo = new ProofImageVo();
        vo.setProofUrl(proofUrl);
        vo.setOcrPaymentNo(fields.paymentNo());
        vo.setOcrAmount(fields.amount());
        vo.setOcrText(fields.text());
        return vo;
    }
}
