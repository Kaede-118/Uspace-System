package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BillParserDispatcher} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>这是唯一一处从「字节」走到「记录」的测试 ——
 * 读取层与解析层的接头就发生在这里，所以编码、文件形态、格式分派这三件事
 * 只有在这一层才能一起验。
 *
 * <p>最要紧的一组是<b>三个格式的互斥性</b>：微信与标准模板的表头长得很像
 *（都有「交易时间」与「交易单号」），分不开的话渠道会记错，
 * 而那是不会报错的错。
 *
 * <p>另一组是 {@link #parse_realShapeWechatXlsx()}：它按<b>真文件的形态</b>
 *（2026-09-30 拿到的个人零钱账户账单）造一份 xlsx 走完整条链路。
 * 那份文件里几处不显眼的结构 —— 17 行说明文字、XML 里被省略的空行、
 * 序列号形态的交易时间、纯数字的金额 —— 每一处看错都会静默地算错，所以值得钉住。
 */
class BillParserDispatcherTests {

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String WECHAT =
            "微信支付账单明细,,,,,,,,,,\n"
                    + "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"
                    + "2026-09-30 21:45:32,二维码收款,张三,舞萌,收入,¥8.00,零钱,支付成功,4200001234,,备注\n";

    private static final String ALIPAY =
            "支付宝交易记录明细查询,,,,,,,,,,,,\n"
                    + "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n"
                    + "2026-09-30 21:45:32,二维码收款,张三,,舞萌,收入,8.00,余额,交易成功,2026093022001234,,备注\n";

    private static final String STANDARD =
            "交易时间,交易单号,金额(元),收/支,交易状态,备注\n"
                    + "2026-09-01 10:12:03,4200009999,8.00,收入,交易成功,舞萌\n";

    // ==================================================================
    // 三种格式各自挑中
    // ==================================================================

    @Test
    @DisplayName("微信账单：带 UTF-8 BOM 的 CSV")
    void parse_wechat() {
        BillParseResult result = BillParserDispatcher.parse(utf8Bom(WECHAT));

        assertEquals(ReconcileChannel.WXPAY, result.channel(),
                "带 BOM 不影响分派 —— BOM 那一行在解码时就被剥掉了");
        assertEquals(1, result.records().size(), "说明文字行要跳过，只解出真正的那一条");
        assertEquals(new BigDecimal("8.00"), result.records().get(0).amount());
    }

    @Test
    @DisplayName("支付宝账单：无 BOM 的 GBK")
    void parse_alipay() {
        BillParseResult result = BillParserDispatcher.parse(ALIPAY.getBytes(Charset.forName("GBK")));

        assertEquals(ReconcileChannel.ALIPAY, result.channel(),
                "GBK 解不出来就没法做任何事 —— 这是支付宝账单与微信账单最实在的一个差别");
        assertEquals("2026093022001234", result.records().get(0).paymentNo());
    }

    @Test
    @DisplayName("标准模板：只有两列标记，兜底接住")
    void parse_standard() {
        BillParseResult result = BillParserDispatcher.parse(STANDARD.getBytes(StandardCharsets.UTF_8));

        assertEquals(ReconcileChannel.STANDARD, result.channel(),
                "标准模板的表头少了「当前状态」列，微信认不出它，于是轮到兜底那个");
        assertEquals("4200009999", result.records().get(0).paymentNo());
    }

    // ==================================================================
    // 互斥性：这一组错了也不会报错，只会把渠道记错
    // ==================================================================

    @Test
    @DisplayName("微信账单不会被标准模板抢走")
    void wechatIsNotClaimedByStandard() {
        // 微信与标准模板的表头都有「交易时间」与「交易单号」，
        // 区分它们的只有微信特有的「当前状态」列
        BillParseResult result = BillParserDispatcher.parse(WECHAT.getBytes(StandardCharsets.UTF_8));

        assertEquals(ReconcileChannel.WXPAY, result.channel(),
                "渠道记错不影响对账结果（三种格式共用同一套解析逻辑），"
                        + "但会让批次列表显示错误的来源，而这是不会报错的错");
    }

    @Test
    @DisplayName("标准模板不会被微信抢走")
    void standardIsNotClaimedByWechat() {
        BillParseResult result = BillParserDispatcher.parse(STANDARD.getBytes(StandardCharsets.UTF_8));

        assertEquals(ReconcileChannel.STANDARD, result.channel(),
                "标准模板没有「当前状态」列，微信的标记它一个都满足不了");
    }

    // ==================================================================
    // 真实账单的形态（2026-09-30 拿到的个人零钱账户账单）
    // ==================================================================

    @Test
    @DisplayName("真实形态的微信 xlsx：说明文字、省略的空行、序列号日期、纯数字金额")
    void parse_realShapeWechatXlsx() {
        BillParseResult result = BillParserDispatcher.parse(realShapeWechatXlsx());

        assertEquals(ReconcileChannel.WXPAY, result.channel(),
                "表头与真实文件逐字一致，归微信。它不会被标准模板抢走 ——「当前状态」是微信独有的列");

        assertEquals(1, result.records().size(),
                "三行数据的「收/支」都是收入、状态都是成功态，唯一的差别是交易类型；"
                        + "只有「二维码收款」那一笔是本店的钱");
        BillRecord record = result.records().get(0);
        assertEquals("10001073012026093001839579818556", record.paymentNo());
        assertEquals(new BigDecimal("50.00"), record.amount(),
                "金额是纯数字：文件里既没有货币符号也没有千分位，¥#,##0.00 只是样式表里的显示格式");
        assertEquals(LocalDateTime.of(2026, 9, 30, 20, 11, 6), record.tradedAt(),
                "交易时间存的是 Excel 日期序列号（46295.84104166667），"
                        + "要按「1899-12-30 起的天数 + 一天内的秒数」换算 —— 认不出来的话"
                        + "对账窗口会算不准，而窗口算窄了就等于悄悄漏掉一些凭证");
        assertEquals("张三", record.summary(), "摘要取「交易对方」");

        assertEquals(2, result.excludedCount(), "转账与红包被交易类型挡下");
        assertEquals(List.of("转账", "微信红包"), result.excludedTypes(),
                "被挡下的类型要按出现顺序记下来 —— 换成经营账户后白名单该补什么就看这一串");
        assertEquals(0, result.skippedRows(),
                "它们不是「认不出的行」：方向、状态、单号、金额一样不缺，只是不是本店的钱。"
                        + "两者混在一起的话，管理员看到「跳过 2 行」会去查一个根本不存在的解析问题");
    }

    // ==================================================================
    // 失败路径
    // ==================================================================

    @Test
    @DisplayName("认不出表头时，把文件开头原样带出去")
    void parse_unrecognizedHeader() {
        String text = "姓名,电话,金额\n张三,13800000000,8.00\n";

        BillParseException e = assertThrows(BillParseException.class,
                () -> BillParserDispatcher.parse(text.getBytes(StandardCharsets.UTF_8)));

        assertEquals(ErrorCode.RECONCILE_BILL_FORMAT, e.getError());
        assertTrue(e.getMessage().contains("姓名") && e.getMessage().contains("电话"),
                "要回显认出的表头。真实账单的列名还没有样本能确认，"
                        + "管理员把这句话发回来，别名表补一行就修好了；"
                        + "只说「格式不对」的话他除了反复重试没有别的动作可做。实际文案：" + e.getMessage());
    }

    @Test
    @DisplayName("空文件报编码错，而不是「认不出表头」")
    void parse_emptyFile() {
        BillParseException e = assertThrows(BillParseException.class,
                () -> BillParserDispatcher.parse(new byte[0]));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError(),
                "「文件是空的」与「表头不对」是两件事，管理员的动作也不同");
    }

    @Test
    @DisplayName("zip 包提示先解压，而不是认不出表头")
    void parse_zipFile() {
        BillParseException e = assertThrows(BillParseException.class,
                () -> BillParserDispatcher.parse("PK一些二进制".getBytes(StandardCharsets.UTF_8)));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError(),
                "微信账单发到邮箱的就是一个 zip 包。报「认不出表头」的话，"
                        + "管理员拿着一个明明是账单的东西会觉得系统坏了");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /** 真实微信账单的 11 个列名，与拿到的文件逐字一致 */
    private static final String[] WECHAT_HEADERS = {
            "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
            "支付方式", "当前状态", "交易单号", "商户单号", "备注"};

    /** 见 {@link #WECHAT_HEADERS} */
    private static final String[] COLUMNS = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"};

    /**
     * 造一份「形态照真文件」的微信 xlsx。
     *
     * <p>⚠️ <b>数据是编的，形态是真的。</b> 真实那份（2026-09-30 拿到）含真实姓名、
     * 房租金额与就诊退款，而本仓库是公开的 —— 它不进货。
     * 这里保留的是它<b>结构</b>上的全部特征：
     * <ul>
     *   <li>表头之前有 17 行说明文字，<b>表头落在第 18 行</b>
     *       （说明文字一旦长过 {@code MAX_HEADER_SCAN_ROWS} 就整份认不出来，
     *       所以那个长度值得钉住）；</li>
     *   <li><b>空行（第 6、16 行）在 XML 里根本不存在</b> ——
     *       列定位只能按 {@code r="A18"} 换算，数 {@code <c>} 的个数会整体串位；</li>
     *   <li>交易时间是 <b>Excel 日期序列号</b>，写成数字单元格；</li>
     *   <li>金额是<b>纯数字</b>，不是 {@code ¥#,##0.00} 那个显示格式。</li>
     * </ul>
     *
     * @return xlsx 字节
     */
    private static byte[] realShapeWechatXlsx() {
        List<String> pool = new ArrayList<>();
        StringBuilder sheet = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<sheetData>");

        // 第 1~17 行说明文字。null 表示那一行是空的 —— 真实文件里它没有对应的 <row>
        String[] preamble = {
                "微信支付账单明细",
                "微信昵称：[测试账号]",
                "起始时间：[2026-09-30 00:00:00] 终止时间：[2026-09-30 23:59:59]",
                "导出类型：[全部收入]",
                "导出时间：[2026-09-30 20:18:14]",
                null,
                "共3笔记录",
                "收入：3笔 45060.00元",
                "支出：0笔 0.00元",
                "中性交易：0笔 0.00元",
                "注：",
                "1. 充值/提现/理财通购买/零钱通存取/信用卡还款等交易，将计入中性交易",
                "2. 若交易记录明细无有效内容，则代表该时间段内此微信号无交易",
                "3. 本明细仅供个人对账使用",
                "4. 本账单中所有时间均为UTC+08:00时间",
                null,
                "----------------------微信支付账单明细列表--------------------"};
        for (int i = 0; i < preamble.length; i++) {
            if (preamble[i] != null) {
                stringRow(sheet, pool, i + 1, new String[]{"A"}, new String[]{preamble[i]});
            }
        }

        stringRow(sheet, pool, 18, COLUMNS, WECHAT_HEADERS);

        // 三笔数据。第三列往后都一样，只有「交易类型」不同 —— 这正是那道过滤要拦的东西
        dataRow(sheet, pool, 19, "46295.84104166667", "二维码收款", "张三",
                "收款方备注:二维码收款", "50", "零钱", "已收钱",
                "10001073012026093001839579818556");
        dataRow(sheet, pool, 20, "46294.5", "转账", "李四",
                "转账备注:微信转账", "45000", "/", "已存入零钱",
                "1000050001202609270220799833031");
        dataRow(sheet, pool, 21, "46293.5", "微信红包", "王五",
                "/", "10", "/", "已存入零钱", "1000039801000609046341873091041");

        sheet.append("</sheetData></worksheet>");
        return zipXlsx(sheet.toString(), pool);
    }

    /**
     * 写一整行共享字符串单元格。
     *
     * @param sheet   表 XML
     * @param pool    共享字符串池
     * @param row     行号
     * @param columns 列字母
     * @param values  各列的值
     */
    private static void stringRow(StringBuilder sheet, List<String> pool, int row,
                                  String[] columns, String[] values) {
        sheet.append("<row r=\"").append(row).append("\">");
        for (int i = 0; i < columns.length; i++) {
            sheet.append(stringCell(columns[i], row, pool, values[i]));
        }
        sheet.append("</row>");
    }

    /**
     * 写一行数据。收支方向固定是「收入」，商户单号与备注固定是 {@code /}。
     *
     * @param sheet  表 XML
     * @param pool   共享字符串池
     * @param row    行号
     * @param serial 交易时间：Excel 日期序列号，写成<b>数字</b>单元格
     * @param type   交易类型
     * @param peer   交易对方
     * @param goods  商品
     * @param amount 金额，写成<b>数字</b>单元格
     * @param payWay 支付方式
     * @param status 当前状态
     * @param no     交易单号
     */
    private static void dataRow(StringBuilder sheet, List<String> pool, int row,
                                String serial, String type, String peer, String goods,
                                String amount, String payWay, String status, String no) {
        sheet.append("<row r=\"").append(row).append("\" spans=\"1:11\">")
                .append("<c r=\"A").append(row).append("\"><v>").append(serial).append("</v></c>")
                .append(stringCell("B", row, pool, type))
                .append(stringCell("C", row, pool, peer))
                .append(stringCell("D", row, pool, goods))
                .append(stringCell("E", row, pool, "收入"))
                .append("<c r=\"F").append(row).append("\"><v>").append(amount).append("</v></c>")
                .append(stringCell("G", row, pool, payWay))
                .append(stringCell("H", row, pool, status))
                .append(stringCell("I", row, pool, no))
                .append(stringCell("J", row, pool, "/"))
                .append(stringCell("K", row, pool, "/"))
                .append("</row>");
    }

    /**
     * 写一个共享字符串单元格。
     *
     * <p>下标是现查现加的，<b>不手写数字</b>：这份数据有四十来个字符串，
     * 手写下标的话改一处就全串位，而串位的表现是「解析出来的全是别的字段」，不报任何错。
     *
     * @param column 列字母
     * @param row    行号
     * @param pool   共享字符串池
     * @param value  值
     * @return 单元格 XML
     */
    private static String stringCell(String column, int row, List<String> pool, String value) {
        int index = pool.indexOf(value);
        if (index < 0) {
            pool.add(value);
            index = pool.size() - 1;
        }
        return "<c r=\"" + column + row + "\" t=\"s\"><v>" + index + "</v></c>";
    }

    /**
     * 把表与共享字符串表打成一个 xlsx（zip）。
     *
     * @param sheetXml 表 XML
     * @param pool     共享字符串池
     * @return 文件字节
     */
    private static byte[] zipXlsx(String sheetXml, List<String> pool) {
        StringBuilder shared = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst>");
        for (String item : pool) {
            shared.append("<si><t>").append(item).append("</t></si>");
        }
        shared.append("</sst>");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(shared.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("造测试用的 xlsx 失败", e);
        }
    }

    /**
     * 在文本前面加上 UTF-8 BOM。
     *
     * @param text 文本
     * @return 带 BOM 的字节
     */
    private static byte[] utf8Bom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[BOM_UTF8.length + body.length];
        System.arraycopy(BOM_UTF8, 0, result, 0, BOM_UTF8.length);
        System.arraycopy(body, 0, result, BOM_UTF8.length, body.length);
        return result;
    }
}
