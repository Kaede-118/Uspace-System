package com.kaede.uspace.order.ocr;

import java.math.BigDecimal;

/**
 * 从 OCR 文本行里解析出来的字段（{@link OcrTextParser} 的产物）。
 *
 * <p><b>三个字段各是一个「可空」</b>：识别不出的那张图、只认出金额没认出单号的图、
 * 以及两个都认出的图，都会走到这里。用 {@link #EMPTY} 表达「什么都没认出」，
 * 而不是把 null 散在调用方 —— 三处都要判 null 的话，漏一处就是一次 NPE。
 *
 * <p>字段名与 {@code PaymentProof} 实体的 {@code ocrPaymentNo} / {@code ocrAmount} /
 * {@code ocrText} 一一对应，调用方中间不必做一次重命名。
 *
 * @param paymentNo 识别出的交易单号，可空。已去掉空白（OCR 常把长数字串断开）
 * @param amount    识别出的金额（元），可空。已做范围检查，必定塞得进 {@code DECIMAL(10,2)}
 * @param text      识别出的原文，可空。已按 {@code ocr_text} 的列宽截断
 */
public record OcrFields(String paymentNo, BigDecimal amount, String text) {

    /**
     * 什么都没认出。
     *
     * <p>没配识别引擎时（{@code uspace.ocr.provider=mock}）每次上传都是它 ——
     * 这是正常的运营状态，不是错误。
     */
    public static final OcrFields EMPTY = new OcrFields(null, null, null);

    /**
     * 是否没有认出任何<b>对业务有用</b>的东西。
     *
     * <p>只问单号与金额两个字段，<b>{@link #text} 不参与判定</b>：
     * 原文只是给管理员看「这张图里到底有什么字」的参考，用户端看不到它，
     * 也因此不能拿它当「识别出了东西」的依据 —— 传一张风景照也能识别出一堆
     * 无关的字，那时给用户提示「已从截图中识别出交易单号」是纯粹的假话。
     *
     * <p>这个方法就是为那句提示准备的：调用方拿它决定要不要开口。
     *
     * @return 单号与金额都没有时返回 true
     */
    public boolean isEmpty() {
        return paymentNo == null && amount == null;
    }
}
