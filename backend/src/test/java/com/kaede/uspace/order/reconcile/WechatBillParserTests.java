package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WechatBillParser} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>行由 {@link CsvReader} 从文本切出来 ——
 * 手工拼 {@code List.of(...)} 也能测解析逻辑，但用真实的切分器能顺带把
 * 「商品列里的逗号」那条链路一起覆盖上，而它正是账单里必然出现的写法。
 *
 * <p>⚠️ 微信账单的真实表头还没有样本（见类的注释），所以这里的表头是按公开资料写的。
 * 真文件到手后，这些用例的表头要跟着改 —— <b>那正是它们存在的意义</b>：
 * 改一处就能看出解析逻辑有没有被带坏。
 */
class WechatBillParserTests {

    /** 真实的微信账单在表头之前还有几行说明文字，这几行是照着真实形态写的 */
    private static final String TITLE = "微信支付账单明细,,,,,,,,,,";

    /** 见 {@link #TITLE} */
    private static final String PERIOD = "起始时间：[2026-09-01 00:00:00] 终止时间：[2026-09-30 23:59:59],,,,,,,,,,";

    /** 真实表头 */
    private static final String HEADER =
            "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注";

    private final WechatBillParser parser = new WechatBillParser();

    // ==================================================================
    // 找表头
    // ==================================================================

    @Test
    @DisplayName("跳过前面几行说明文字，找到真正的表头")
    void locateHeader_skipsPreamble() {
        List<List<String>> rows = rows(TITLE, PERIOD, HEADER, data());

        assertEquals(2, parser.locateHeader(rows),
                "真实账单的表头不在第一行 —— 不跳说明文字的话整份文件都认不出来");
    }

    @Test
    @DisplayName("认不出表头时返回 -1，不抛异常")
    void locateHeader_returnsMinusOne() {
        assertEquals(-1, parser.locateHeader(rows("随便一个表,第二列", "1,2")),
                "「这不是我的格式」要能用返回值表达 —— 分派器还要拿它去问下一个解析器");
    }

    @Test
    @DisplayName("没有「当前状态」列的文件不归微信读 —— 那是与标准模板的分界")
    void locateHeader_requiresCurrentStatusColumn() {
        List<List<String>> rows = rows("交易时间,交易单号,金额(元),收/支,交易状态,备注");

        assertEquals(-1, parser.locateHeader(rows),
                "「当前状态」是微信特有的列名。少了这个标记，标准模板文件会被微信抢走，渠道就记错了");
    }

    // ==================================================================
    // 正常解析
    // ==================================================================

    @Test
    @DisplayName("解析出一条完整记录，字段各就各位")
    void parse_oneRecord() {
        BillParseResult result = parse(rows(TITLE, PERIOD, HEADER, data()));

        assertEquals(1, result.records().size(), "一行有效数据要解出一条记录");
        assertEquals(ReconcileChannel.WXPAY, result.channel(), "渠道要标成微信");

        BillRecord record = result.records().get(0);
        assertEquals("4200001234202609301234567890", record.paymentNo(), "单号取「交易单号」列");
        assertEquals(new BigDecimal("8.00"), record.amount(), "金额要洗掉货币符号");
        assertEquals(LocalDateTime.of(2026, 9, 30, 21, 45, 32), record.tradedAt(), "交易时间要解出来");
    }

    @Test
    @DisplayName("商品列里带逗号时列不错位 —— 这是整份文件最容易崩的地方")
    void parse_commaInsideGoodsColumn() {
        // 「商品」列被引号包着，里面有一个逗号。用 split(",") 的话后面所有列都会往前串一位
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:45:32,二维码收款,张三,\"游戏币,A套餐\",收入,8.00,零钱,支付成功,4200001234,,舞萌"));

        assertEquals(1, result.records().size(), "有引号的行照样要解出来");
        assertEquals("4200001234", result.records().get(0).paymentNo(),
                "单号必须还是「交易单号」列里的那一串。列串位的话这里会变成别的字段");
        assertEquals(new BigDecimal("8.00"), result.records().get(0).amount(),
                "金额同理 —— 串位之后金额会取到状态列上，解析成 null 然后整行被跳过");
    }

    @Test
    @DisplayName("按列名找列：打乱列顺序照样解析")
    void parse_columnOrderDoesNotMatter() {
        // 与真实表头相比，把「交易单号」与「金额(元)」调了个位置
        List<List<String>> rows = rows(
                "商品,收/支,交易单号,当前状态,金额(元),交易时间,备注,商户单号,支付方式,交易类型,交易对方",
                "舞萌,收入,4200001234,支付成功,8.00,2026-09-30 21:45:32,,,零钱,二维码收款,张三");

        BillParseResult result = parse(rows);

        assertEquals(new BigDecimal("8.00"), result.records().get(0).amount(),
                "按列位置解析的实现在这里会取到别的东西 —— 而改版时列的顺序是会变的");
        assertEquals("4200001234", result.records().get(0).paymentNo());
    }

    @Test
    @DisplayName("金额的三种写法都能洗")
    void parse_amountFormats() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,舞萌,收入,¥8.00,零钱,支付成功,111,,备注",
                "2026-09-30 21:01:00,二维码收款,张三,舞萌,收入,12.5,零钱,支付成功,222,,备注",
                "2026-09-30 21:02:00,二维码收款,张三,舞萌,收入,100,零钱,支付成功,333,,备注"));

        assertEquals(new BigDecimal("8.00"), result.records().get(0).amount(), "带货币符号的");
        assertEquals(new BigDecimal("12.50"), result.records().get(1).amount(), "一位小数的要补到两位");
        assertEquals(new BigDecimal("100.00"), result.records().get(2).amount(), "整数的也要补到两位");
    }

    // ==================================================================
    // 过滤
    // ==================================================================

    @Test
    @DisplayName("支出与退款只计数、不进记录")
    void parse_nonIncomeExcludedNotParsed() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,舞萌,收入,8.00,零钱,支付成功,111,,",
                "2026-09-30 22:00:00,退款,张三,舞萌,支出,8.00,零钱,已全额退款,222,,",
                "2026-09-30 23:00:00,零钱提现,张三,提现,不计收支,100.00,零钱,提现已到账,333,,"));

        assertEquals(1, result.records().size(), "只有那笔收入进记录");
        assertEquals(2, result.excludedCount(),
                "支出与不计收支各自算一笔 —— 退款是系统主动发起的，系统本来就知道，不参与对账");
    }

    @Test
    @DisplayName("状态不是「成功」的行计入跳过数，不进记录")
    void parse_nonSuccessStatusSkipped() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,舞萌,收入,8.00,零钱,支付成功,111,,",
                "2026-09-30 21:01:00,二维码收款,张三,舞萌,收入,8.00,零钱,已取消,222,,"));

        assertEquals(1, result.records().size(), "只有成功的那笔进记录");
        assertEquals(1, result.skippedRows(),
                "认不出的行要计数并显示给管理员 —— 悄悄丢掉的话，"
                        + "「账单里 87 笔、系统只认了 67 笔」这件事就永远说不清了");
    }

    @Test
    @DisplayName("「退款成功」不能被当成「成功」—— 状态必须精确匹配")
    void parse_refundSuccessIsNotSuccess() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,舞萌,收入,8.00,零钱,退款成功,111,,"));

        assertEquals(0, result.records().size(),
                "「退款成功」里含「成功」二字。用包含判断的话这笔退款会变成一笔收款 —— "
                        + "凭空多出的钱比少算一笔危险得多，因为没有人会发现");
    }

    @Test
    @DisplayName("缺金额或缺单号的行计入跳过数")
    void parse_missingAmountOrNoSkipped() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,舞萌,收入,,零钱,支付成功,111,,",
                "2026-09-30 21:01:00,二维码收款,张三,舞萌,收入,8.00,零钱,支付成功,,,"));

        assertEquals(0, result.records().size(), "两个都缺关键字段，一条都不该解出来");
        assertEquals(2, result.skippedRows(), "两行都要计进跳过数");
    }

    @Test
    @DisplayName("空行不计入跳过数")
    void parse_blankRowNotCounted() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,张三,舞萌,收入,8.00,零钱,支付成功,111,,",
                ",,,,,,,,,,"));

        assertEquals(1, result.records().size());
        assertEquals(0, result.skippedRows(),
                "账单末尾的空行不是「认不出的数据行」—— 算进去会让管理员以为解析漏了什么");
    }

    // ==================================================================
    // 交易类型白名单 —— 个人收款账单里最关键的一道过滤
    // ==================================================================

    @Test
    @DisplayName("转账与红包不进记录 —— 它们的「收入」与「已存入零钱」骗不过交易类型这一关")
    void parse_transferExcludedByTradeType() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                // 本店的收款
                "2026-09-30 21:00:00,二维码收款,张三,收款方备注:二维码收款,收入,8.00,零钱,已收钱,111,,/",
                // 私人往来：收支与状态跟上一行一模一样，只差一个交易类型
                "2026-09-30 21:01:00,转账,李四,转账备注:微信转账,收入,45000.00,/,已存入零钱,222,,/",
                "2026-09-30 21:02:00,微信红包,王五,/,收入,10.00,/,已存入零钱,333,,/"));

        assertEquals(1, result.records().size(),
                "只有「二维码收款」那一笔是本店的钱。转账与红包的收/支同样是「收入」、"
                        + "状态同样是成功态 —— 看单行根本分不出来，只能靠交易类型");
        assertEquals(2, result.excludedCount(), "被挡下的两笔要计数，让管理员看得见");
        assertEquals(List.of("转账", "微信红包"), result.excludedTypes(),
                "类型要原样、按出现顺序记下来 —— 换成经营账户后白名单该补什么就看这一串");
    }

    @Test
    @DisplayName("交易类型读不到时按「不算本店收款」处理 —— 不按「放行」")
    void parse_missingTradeTypeExcluded() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,,张三,舞萌,收入,8.00,零钱,已收钱,111,,备注"));

        assertEquals(0, result.records().size(),
                "本格式声明了白名单，却读不到这一行的类型 —— 说明格式变了。"
                        + "此时放行等于静默地把一堆无关往来当成收款，"
                        + "远比「少认」危险：少认会在页面上显示「未参与对账 N 笔」");
    }

    @Test
    @DisplayName("摘要取「交易对方」而不是「商品」—— 差异里要一眼看出是谁付的")
    void parse_summaryPrefersCounterparty() {
        BillParseResult result = parse(rows(TITLE, HEADER,
                "2026-09-30 21:00:00,二维码收款,zmh 夏巧 (Natsutakum1),收款方备注:二维码收款,"
                        + "收入,8.00,零钱,已收钱,111,,/"));

        assertEquals("zmh 夏巧 (Natsutakum1)", result.records().get(0).summary(),
                "真实表头里「商品」与「交易对方」两列都有，取第一个命中的别名。"
                        + "按原来的顺序会取到「收款方备注:二维码收款」这种写死的废话");
    }

    // ==================================================================
    // 缺列
    // ==================================================================

    @Test
    @DisplayName("缺必需列时报错，并把认出的表头带出去")
    void parse_missingRequiredColumn() {
        // 表头要有 locateHeader 认的那些标记列（交易时间 / 交易单号 / 当前状态），
        // 否则连「这是不是微信账单」都判不出来，轮不到缺列检查。
        // 这里缺的是「金额(元)」与「收/支」—— 它们不是标记列，但解析时必须有
        List<List<String>> rows = rows(TITLE,
                "交易时间,交易类型,交易对方,商品,支付方式,当前状态,交易单号,商户单号,备注",
                "2026-09-30 21:00:00,二维码收款,张三,舞萌,零钱,支付成功,4200001234,,");

        BillParseException e = assertThrows(BillParseException.class, () -> parse(rows));

        assertEquals(ErrorCode.RECONCILE_BILL_FORMAT, e.getError());
        assertTrue(e.getMessage().contains("金额") && e.getMessage().contains("收/支"),
                "缺了哪几列要逐一点名。实际文案：" + e.getMessage());
        assertTrue(e.getMessage().contains("交易时间"),
                "还要把认出的表头原样带出去 —— 真实账单的列名只有拿到真文件才能确认，"
                        + "管理员把这句话发回来，别名表补一行就修好了。实际文案：" + e.getMessage());
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /** 一行正常的数据 */
    private static String data() {
        return "2026-09-30 21:45:32,二维码收款,张三,舞萌,收入,¥8.00,零钱,"
                + "支付成功,4200001234202609301234567890,,备注";
    }

    /**
     * 把每行文本切成一行的列。
     *
     * <p>逐行调用 {@link CsvReader} 而不是把整段文本一次切 ——
     * 这样才能用可变参数一行行地写用例，读起来清爽。
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
     * 找表头并解析，找不到表头就判定用例失败。
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
