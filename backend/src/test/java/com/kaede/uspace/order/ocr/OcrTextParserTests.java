package com.kaede.uspace.order.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OcrTextParser} 的单元测试。
 *
 * <p><b>纯单测</b>：本类不依赖 Spring、不连网络，喂进去的是一行行文本
 *（就是百度 {@code words_result} 里那些 {@code words}），出来的应当是金额与单号。
 *
 * <p>它值得写这么细，是因为<b>错了不会报错</b>：认错了金额，后台那一行会显示一个
 * 「识别到 ¥22.00」的提示，而管理员看到的截图上是 8.00 —— 他不一定会去分辨
 * 这是识别错了还是真有问题。而认错了单号更隐蔽，那串数字没人会逐位核对。
 *
 * <h3>用例里的文本从哪来</h3>
 *
 * <p>照着微信与支付宝「付款成功 / 账单详情」页的真实排版抄的（见各用例里的
 * {@code List.of(...)}）。<b>不照着想象中的格式写</b> —— 规则是给真实页面写的，
 * 用假想格式测出来的绿没有意义。
 */
class OcrTextParserTests {

    // ==================================================================
    // 真实页面
    // ==================================================================

    @Test
    @DisplayName("微信付款成功页 → 认出金额与交易单号")
    void parse_wechatPaymentPage() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "微信支付",
                "支付成功",
                "¥8.00",
                "当前状态",
                "支付成功",
                "商品",
                "共享空间使用费",
                "商户全称",
                "Uspace 共享空间",
                "支付方式",
                "零钱",
                "支付时间",
                "2026年9月30日 21:45",
                "交易单号",
                "4200001234202609301234567890",
                "商户单号",
                "USP20260930001"));

        assertEquals(0, fields.amount().compareTo(new BigDecimal("8.00")),
                "金额要从「¥8.00」里读出来 —— 这是管理员核对时第一眼要比的数");
        assertEquals("4200001234202609301234567890", fields.paymentNo(),
                "要取「交易单号」而不是同一页上的「商户单号」："
                        + "前者是支付平台的，才能跟平台账单勾稽上");
    }

    @Test
    @DisplayName("支付宝账单详情页 → 认出订单号")
    void parse_alipayPaymentPage() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "账单详情",
                "支付成功",
                "8.00",
                "订单号",
                "2026093022001234567890123456",
                "商户订单号",
                "USP20260930001",
                "付款时间",
                "2026-09-30 21:45:32",
                "付款方式",
                "余额宝"));

        assertEquals("2026093022001234567890123456", fields.paymentNo(),
                "支付宝那边叫「订单号」，关键词表里要有它");
    }

    @Test
    @DisplayName("关键词与数字挤在同一行 → 同样认得出")
    void parse_keywordAndDigitsOnSameLine() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "支付成功 ¥8.00",
                "交易单号：4200001234202609301234567890"));

        assertEquals(0, fields.amount().compareTo(new BigDecimal("8.00")), "同一行里的金额也要认");
        assertEquals("4200001234202609301234567890", fields.paymentNo(),
                "关键词后面跟着冒号再接数字，是同样常见的一种排版");
    }

    @Test
    @DisplayName("长数字串被 OCR 断成几段 → 去掉空白后仍能拼回来")
    void parse_numberSplitBySpaces() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "交易单号",
                "4200 0012 3420 2609 3012 3456 7890"));

        assertEquals("4200001234202609301234567890", fields.paymentNo(),
                "OCR 常常把长数字串按 4 位一组断开 —— 不去空白的话这一条永远认不出，"
                        + "而它恰恰是最常见的一种输出");
    }

    // ==================================================================
    // 关键词优先与兜底
    // ==================================================================

    @Test
    @DisplayName("没有关键词时 → 退化为找最长的数字串")
    void parse_fallsBackToLongDigitRun() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "支付成功",
                "¥8.00",
                "4200001234202609301234567890"));

        assertEquals("4200001234202609301234567890", fields.paymentNo(),
                "截图可能只截到一半、关键词正好被裁掉，这时兜底规则要接得住");
    }

    @Test
    @DisplayName("兜底规则不把短数字串当单号 → 时间与日期不会被误取")
    void parse_shortDigitsAreNotPaymentNo() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "支付时间",
                "20260930214532",
                "¥8.00"));

        assertNull(fields.paymentNo(),
                "14 位的时间戳不该被当成单号 —— 兜底规则宁可不认，也不能认错");
    }

    @Test
    @DisplayName("交易单号与商户单号同时出现 → 取交易单号")
    void parse_prefersTradeNo() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "商户单号",
                "USP20260930001",
                "交易单号",
                "4200001234202609301234567890"));

        assertEquals("4200001234202609301234567890", fields.paymentNo(),
                "哪怕商户单号排在前面，也该优先取交易单号 —— 关键词表的顺序就是干这个的");
    }

    // ==================================================================
    // 金额的边界
    // ==================================================================

    @Test
    @DisplayName("金额 0 元 → 跳过，继续找后面那个真的")
    void parse_skipsZeroAmount() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "订单金额 ¥0.00",
                "实付金额 ¥8.00"));

        assertEquals(0, fields.amount().compareTo(new BigDecimal("8.00")),
                "页面上可能先出现一个 0（占位、优惠前的金额），0 元不是一次有效付款");
    }

    @Test
    @DisplayName("金额超出合理区间 → 丢弃，不认")
    void parse_dropsUnreasonableAmount() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "¥999999999.00"));

        assertNull(fields.amount(),
                "单笔消费不可能过万。丢掉它比让一个塞不进 DECIMAL(10,2) 的数"
                        + "一路走到插入语句里要好得多");
    }

    @Test
    @DisplayName("全角数字 → 归一化之后照样认得出")
    void parse_normalizesFullWidthDigits() {
        OcrFields fields = OcrTextParser.parse(List.of(
                "¥８．００",
                "交易单号",
                "４２００００１２３４２０２６０９３０１２３４５６７８９０"));

        assertEquals(0, fields.amount().compareTo(new BigDecimal("8.00")),
                "OCR 只认字形不认宽度，同一张图可能一会儿全角一会儿半角，"
                        + "不归一化就得给每种宽度各写一份规则");
        assertEquals("4200001234202609301234567890", fields.paymentNo(), "单号同理");
    }

    // ==================================================================
    // 什么也没有
    // ==================================================================

    @Test
    @DisplayName("空文本行 → 返回 EMPTY，不抛异常")
    void parse_emptyLines() {
        assertEquals(OcrFields.EMPTY, OcrTextParser.parse(List.of()), "没认出任何字是正常路径");
        assertEquals(OcrFields.EMPTY, OcrTextParser.parse(null),
                "识别服务返回 null 时也不该炸 —— 这是 mock 之外的第二道保险");
    }

    @Test
    @DisplayName("一张风景照 → 没有金额也没有单号，但原文仍留着")
    void parse_unrelatedPhoto() {
        OcrFields fields = OcrTextParser.parse(List.of("蓝天", "白云", "草地"));

        assertNull(fields.amount(), "认不出金额就该是 null，不能猜");
        assertNull(fields.paymentNo(), "认不出单号同理");
        assertNotNull(fields.text(), "原文仍要留着 —— 管理员能看到「这张图里到底有什么字」，"
                + "从而判断用户是不是传错了图");
    }

    // ==================================================================
    // 落库前的钳制
    // ==================================================================

    @Test
    @DisplayName("原文超长 → 截断到列宽，不留给数据库去报错")
    void parse_truncatesLongText() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            lines.add("这是一行很长的说明文字，用来把原文顶过一千个字符的上限");
        }

        OcrFields fields = OcrTextParser.parse(lines);

        assertEquals(OcrTextParser.MAX_TEXT_LENGTH, fields.text().length(),
                "ocr_text 是 VARCHAR(1000)。不在这一步截，超长就会在插入时炸，"
                        + "而用户完全不知道发生了什么（这三个字段是系统塞给他带回来的）");
    }

    @Test
    @DisplayName("原文为空字符串 → 归一成 null，不写空串进库")
    void parse_blankTextBecomesNull() {
        OcrFields fields = OcrTextParser.parse(List.of("", "   "));

        assertNull(fields.text(),
                "「没有原文」应当是 null 而不是空串 —— 与 ocr_text 列的 DEFAULT NULL 一致，"
                        + "前台判空时也少一种情形要处理");
    }

    @Test
    @DisplayName("isEmpty → 三个字段全空时为 true")
    void isEmpty_reflectsAllThreeFields() {
        assertTrue(OcrTextParser.parse(List.of()).isEmpty(), "什么都没认出就是 EMPTY");
        assertTrue(OcrTextParser.parse(List.of("蓝天白云")).isEmpty(),
                "只认出几个无关的字，对业务而言也是「什么都没认出」—— "
                        + "前端据此决定要不要提示「已识别出单号」，提示错了比不提示更糟");
    }
}
