package com.kaede.uspace.order.reconcile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StandardBillParser} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>
 *
 * <p>标准模板是本系统自己定义的格式，用于「微信与支付宝导出的文件都读不出来」时的退路。
 * 它的两处刻意的宽松各有一条用例钉着：<b>允许省略「收/支」与「交易状态」两列</b>
 *（手工整理时最容易漏的就是它们），以及<b>标记比另两种少一个</b>
 *（正因为少，它才能当兜底而不被微信抢走）。
 */
class StandardBillParserTests {

    /** 完整六列的表头 */
    private static final String HEADER = "交易时间,交易单号,金额(元),收/支,交易状态,备注";

    private final StandardBillParser parser = new StandardBillParser();

    @Test
    @DisplayName("完整六列正常解析")
    void parse_fullColumns() {
        BillParseResult result = parse(rows(HEADER,
                "2026-09-01 10:12:03,4200001234202609301234567890,8.00,收入,交易成功,舞萌"));

        assertEquals(1, result.records().size(), "一行解一条");
        BillRecord record = result.records().get(0);
        assertEquals("4200001234202609301234567890", record.paymentNo());
        assertEquals(new BigDecimal("8.00"), record.amount());
        assertEquals(LocalDateTime.of(2026, 9, 1, 10, 12, 3), record.tradedAt());
        assertEquals(ReconcileChannel.STANDARD, result.channel(), "渠道标成标准模板");
    }

    @Test
    @DisplayName("省略「收/支」与「交易状态」两列照样能解析 —— 这是本格式唯一的宽待")
    void parse_optionalColumnsMissing() {
        BillParseResult result = parse(rows("交易时间,交易单号,金额(元),备注",
                "2026-09-01 10:12:03,4200001234,8.00,舞萌"));

        assertEquals(1, result.records().size(),
                "手工整理时最容易漏的就是这两列，而它们在本店几乎恒为「收入」与「交易成功」。"
                        + "为一次漏填让整份文件解析不出来，代价与收益不成比例");
        assertEquals(0, result.skippedRows(), "缺省放行不是「认不出」，不该计入跳过数");
    }

    @Test
    @DisplayName("只省略「交易状态」一列也行")
    void parse_onlyStatusMissing() {
        BillParseResult result = parse(rows("交易时间,交易单号,金额(元),收/支",
                "2026-09-01 10:12:03,4200001234,8.00,收入"));

        assertEquals(1, result.records().size(), "缺一列与缺两列都要认");
    }

    @Test
    @DisplayName("没有「交易类型」列也照样解析 —— 它不该是必需列")
    void parse_tradeTypeColumnNotRequired() {
        // 上面几条用例其实都在顺带验证这一点（它们都没有这一列），
        // 但显式钉一条：optionalColumns() 是覆写而不是叠加，
        // 漏掉 TRADE_TYPE 的话，一份完好的标准模板会被判成缺列，
        // 而提示写着「缺少列：交易类型」—— 管理员手里那份文件明明没有问题
        BillParseResult result = parse(rows(HEADER,
                "2026-09-01 10:12:03,4200001234,8.00,收入,交易成功,舞萌"));

        assertEquals(1, result.records().size());
        assertEquals(0, result.excludedCount(),
                "标准模板不按交易类型筛 —— 这份文件本来就只装本店的收款");
    }

    @Test
    @DisplayName("但「交易单号」不能省 —— 它是对账的匹配键")
    void locateHeader_requiresPaymentNoColumn() {
        List<List<String>> rows = rows("交易时间,金额(元),收/支", "2026-09-01 10:12:03,8.00,收入");

        assertEquals(-1, parser.locateHeader(rows),
                "单号是匹配的主键，没有它整份文件解出来的记录一条都对不上。"
                        + "认不出来（返回 -1，交给下一个解析器）好过解出一堆空号记录");
    }

    @Test
    @DisplayName("没有备注列也能解析 —— 备注只是给人工看的上下文")
    void parse_withoutSummaryColumn() {
        BillParseResult result = parse(rows("交易时间,交易单号,金额(元),收/支,交易状态",
                "2026-09-01 10:12:03,4200001234,8.00,收入,交易成功"));

        assertEquals(1, result.records().size(),
                "备注与「这笔钱对不对得上」毫无关系，为它缺席让整份账单读不出来是把装饰当承重墙");
    }

    @Test
    @DisplayName("表头标记只有两列，比微信少一个 —— 这正是它能当兜底的原因")
    void locateHeader_isLooserThanWechat() {
        List<List<String>> rows = rows(HEADER, "2026-09-01 10:12:03,4200001234,8.00,收入,交易成功,舞萌");

        assertEquals(0, parser.locateHeader(rows), "标准模板的表头在第一行时直接命中");
    }

    @Test
    @DisplayName("手工写的「收」「成功」这类简称也认")
    void parse_shortFormValues() {
        BillParseResult result = parse(rows(HEADER,
                "2026-09-01 10:12:03,4200001234,8.00,收,成功,舞萌"));

        assertEquals(1, result.records().size(),
                "标准模板是人手填的，「收」与「收入」都会出现，两个都收下");
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
