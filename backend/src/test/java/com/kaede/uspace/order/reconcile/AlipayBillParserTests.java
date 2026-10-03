package com.kaede.uspace.order.reconcile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlipayBillParser} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>
 *
 * <p>重点在两处与微信不同的地方：单号列叫「交易订单号」而不是「交易单号」，
 * 以及表头里同时躺着「交易订单号」与「商家订单号」两列 ——
 * <b>取错后者的话，拿到的号在支付宝账单里永远找不到</b>，
 * 表现是所有凭证都变成「系统有、账单无」的差异。
 */
class AlipayBillParserTests {

    private static final String TITLE = "支付宝交易记录明细查询,,,,,,,,,,,,";

    /** 真实的支付宝表头（按公开格式写，待真文件校准） */
    private static final String HEADER =
            "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注";

    private final AlipayBillParser parser = new AlipayBillParser();

    @Test
    @DisplayName("单号取「交易订单号」而不是「商家订单号」")
    void parse_takesTradeOrderNoNotMerchantNo() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:45:32,二维码收款,张三,,舞萌,收入,8.00,余额,交易成功,"
                        + "2026093022001234567890123456,MERCHANT-0001,备注"));

        BillRecord record = result.records().get(0);
        assertEquals("2026093022001234567890123456", record.paymentNo(),
                "「交易订单号」是平台那一侧的号，才能与账单勾稽上；"
                        + "「商家订单号」是商户自己的号，拿它对账永远对不上");
    }

    @Test
    @DisplayName("认得出支付宝表头，且不会被微信抢走")
    void locateHeader() {
        assertEquals(1, parser.locateHeader(rows(TITLE, HEADER)),
                "表头在第二行，前面那行说明文字要跳过去");
    }

    @Test
    @DisplayName("「不计收支」的记录不进对账")
    void parse_excludesNonIncome() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,,舞萌,收入,8.00,余额,交易成功,111,,,",
                "2026-09-30 21:30:00,转账,张三,,转到余额,不计收支,8.00,余额,交易成功,222,,,"));

        assertEquals(1, result.records().size(), "只有「收入」那一笔进记录");
        assertEquals(1, result.excludedCount(),
                "「不计收支」不是退款，但它同样不参与对账 —— 归到这一类里计数");
    }

    @Test
    @DisplayName("不按交易分类筛 —— 没有真实样本，宁可不筛也不猜一个取值")
    void parse_doesNotFilterByTradeCategory() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,转账,张三,zhangsan@example.com,转到余额,收入,8.00,余额,"
                        + "交易成功,111,,,"));

        assertEquals(1, result.records().size(),
                "支付宝这份还没有真实样本，白名单是空的（不筛）。写一个猜来的取值进去更糟："
                        + "认不出时一条记录都不会剩，而管理员完全不知道该怎么改");
        assertEquals(0, result.excludedCount(),
                "没筛过就不该有「未参与对账」—— 这个数不能被无端撑起来");
    }

    @Test
    @DisplayName("金额列叫「金额」不叫「金额(元)」也能取到")
    void parse_amountColumnWithoutUnit() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,,舞萌,收入,¥12.50,余额,交易成功,111,,,"));

        assertEquals(new BigDecimal("12.50"), result.records().get(0).amount(),
                "两种列名都要认 —— 真实文件里叫哪个还没有样本能确认");
    }

    @Test
    @DisplayName("状态不是成功的行计入跳过数")
    void parse_nonSuccessSkipped() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,,舞萌,收入,8.00,余额,交易关闭,111,,,"));

        assertEquals(0, result.records().size(), "交易关闭的不是收款");
        assertEquals(1, result.skippedRows(), "要计数，让管理员看得见");
    }

    @Test
    @DisplayName("「退款成功」不会被当成成功")
    void parse_refundSuccessIsNotSuccess() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,退款,张三,,退款,收入,8.00,余额,退款成功,111,,,"));

        assertEquals(0, result.records().size(),
                "「退款成功」含「成功」二字，用包含判断会把它当成一笔收款");
    }

    @Test
    @DisplayName("渠道标成支付宝")
    void parse_channelIsAlipay() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,,舞萌,收入,8.00,余额,交易成功,111,,,"));

        assertEquals(ReconcileChannel.ALIPAY, result.channel());
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 把每行文本切成一行的列，见 {@code WechatBillParserTests#rows}。
     *
     * @param lines 每行的原始文本
     * @return 行 × 列
     */
    private static List<List<String>> rows(String... lines) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            rows.add(CsvReader.parse(line).get(0));
        }
        return rows;
    }

    /**
     * 找表头并解析。
     *
     * @param rows 行
     * @return 解析结果
     */
    private BillParseResult parse(List<List<String>> rows) {
        int header = parser.locateHeader(rows);
        assertTrue(header >= 0, "本用例的前提是表头能被认出来，但 locateHeader 返回了 -1");
        return parser.parse(rows, header);
    }
}
