package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link XlsxRowReader} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不读磁盘</b> —— 每个用例都在内存里现造一个
 * 最小的 xlsx（就是手工拼几个 XML 打进 zip），因为真正要验的是<b>解析逻辑</b>，
 * 不是「能不能打开某个文件」。
 *
 * <p>最先要钉住的是第一条：<b>空单元格在 xlsx 里是被省略的</b>。
 * 一行里第 C 列没有值时，XML 里根本没有那个 {@code <c>} 元素 ——
 * 靠「第几个 c 就是第几列」去数，会让单号与金额整体串位，而且不报任何错。
 */
class XlsxRowReaderTests {

    /** 工作表的开头与结尾，中间的 {@code <row>} 由各用例自己拼 */
    private static final String SHEET_HEAD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData>";

    /** 见 {@link #SHEET_HEAD} */
    private static final String SHEET_TAIL = "</sheetData></worksheet>";

    private final XlsxRowReader reader = new XlsxRowReader();

    // ==================================================================
    // 位置：整个类最要紧的一条
    // ==================================================================

    @Test
    @DisplayName("空单元格被省略时，靠 r 属性定位列 —— 不能靠顺序数")
    void read_locatesColumnsByReferenceNotByOrder() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\">"
                + "<c r=\"A1\" t=\"s\"><v>0</v></c>"
                + "<c r=\"B1\" t=\"s\"><v>1</v></c>"
                + "<c r=\"C1\" t=\"s\"><v>2</v></c>"
                + "</row>"
                + "<row r=\"2\"><c r=\"B2\" t=\"s\"><v>3</v></c></row>"
                + SHEET_TAIL;

        List<List<String>> rows = reader.read(
                xlsx(sheet, sharedStrings("交易时间", "交易单号", "金额", "4200001234")));

        assertEquals(List.of("交易时间", "交易单号", "金额"), rows.get(0), "第一行三列都要在");
        assertEquals(List.of("", "4200001234", ""), rows.get(1),
                "第二行只写了 B 列，它必须落在第 2 列。"
                        + "靠顺序数的话单号会跑到第 1 列，金额与单号整体串位，而且不报任何错");
    }

    @Test
    @DisplayName("双字母列名（AA / AB）换算正确")
    void read_twoLetterColumnReference() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\"><c r=\"AA1\" t=\"s\"><v>0</v></c></row>"
                + SHEET_TAIL;

        List<List<String>> rows = reader.read(xlsx(sheet, sharedStrings("远列")));

        assertEquals(27, rows.get(0).size(),
                "AA 是第 27 列（0 起的下标 26）。换算成 26 进制会差一位，整张表右移");
        assertEquals("远列", rows.get(0).get(26), "值要落在 AA 那一格上");
    }

    @Test
    @DisplayName("不同行长度不一时，统一补齐到最大列宽")
    void read_padsRowsToSameWidth() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c></row>"
                + SHEET_TAIL;

        List<List<String>> rows = reader.read(xlsx(sheet, sharedStrings("表头", "第三列")));

        assertEquals(3, rows.get(0).size(), "第一行到 C 列");
        assertEquals(3, rows.get(1).size(),
                "第二行只有 A 列，但要补到同样的宽度 —— 不补的话解析层按列下标取数会越界");
        assertEquals("", rows.get(1).get(2), "补出来的是空串");
    }

    // ==================================================================
    // 单元格的三种取值来源
    // ==================================================================

    @Test
    @DisplayName("共享字符串：t=\"s\" 的 v 是下标")
    void read_sharedStrings() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>1</v></c></row>"
                + SHEET_TAIL;

        List<List<String>> rows = reader.read(xlsx(sheet, sharedStrings("第零个", "第一个")));

        assertEquals(List.of("第一个"), rows.get(0),
                "下标要按 0 起算。差一位的话整张表的值都会错位到隔壁");
    }

    @Test
    @DisplayName("内联字符串：t=\"inlineStr\" 的值在 is/t 里")
    void read_inlineString() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>交易时间</t></is></c></row>"
                + SHEET_TAIL;

        List<List<String>> rows = reader.read(xlsx(sheet, null));

        assertEquals(List.of("交易时间"), rows.get(0),
                "内联字符串不经过共享表，值直接就在单元格里");
    }

    @Test
    @DisplayName("没有共享字符串表也能读（整份文件都用内联字符串的情况）")
    void read_withoutSharedStringsEntry() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\"><c r=\"A1\"><v>8</v></c></row>"
                + SHEET_TAIL;

        List<List<String>> rows = reader.read(xlsx(sheet, null));

        assertEquals(List.of("8"), rows.get(0),
                "共享字符串表是可选条目，不能当成必读 —— 当成必读会让这类文件整份读不出来");
    }

    @Test
    @DisplayName("数字型单元格原样吐出字符串，读取层不做类型转换")
    void read_numberCellKeepsRawText() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\"><c r=\"A1\"><v>8.00</v></c><c r=\"B1\"><v>46281.9</v></c></row>"
                + SHEET_TAIL;

        List<List<String>> rows = reader.read(xlsx(sheet, null));

        assertEquals("8.00", rows.get(0).get(0),
                "读取层不解释数字的含义 —— 「这一列是金额还是日期」是解析层才知道的事");
        assertEquals("46281.9", rows.get(0).get(1), "日期序列号也原样带着");
    }

    @Test
    @DisplayName("富文本单元格的几个片段要拼成一条字符串")
    void read_richTextSharedString() {
        String sheet = SHEET_HEAD
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                + SHEET_TAIL;
        String shared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst>"
                + "<si><r><t>交易</t></r><r><t>时间</t></r></si>"
                + "</sst>";

        List<List<String>> rows = reader.read(xlsx(sheet, shared));

        assertEquals(List.of("交易时间"), rows.get(0),
                "Excel 会把带格式的文本拆成几段存，只取第一段的话列名会缺字");
    }

    // ==================================================================
    // 失败路径：要把话说准
    // ==================================================================

    @Test
    @DisplayName("是 zip 但没有工作表 —— 提示「请先解压」而不是「认不出表头」")
    void read_zipWithoutWorksheet() {
        // 微信账单发到邮箱的就是一个（加密的）zip，管理员最容易直接把它传上来
        byte[] zip = zipOf("readme.txt", "这不是 Excel".getBytes(StandardCharsets.UTF_8));

        BillParseException e = assertThrows(BillParseException.class, () -> reader.read(zip));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError(),
                "压缩包问题属于「文件类型」而不是「表头」—— 后者会让管理员反复检查表头");
        assertTrue(e.getMessage().contains("解压"),
                "文案要指向动作。实际文案：" + e.getMessage());
    }

    @Test
    @DisplayName("PK 开头但内容损坏 —— 报「压缩包打不开」")
    void read_brokenZip() {
        BillParseException e = assertThrows(BillParseException.class,
                () -> reader.read("PK这不是一个真的 zip 包".getBytes(StandardCharsets.UTF_8)));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError());
    }

    @Test
    @DisplayName("工作表 XML 损坏 —— 报错而不是静默返回半张表")
    void read_brokenSheetXml() {
        byte[] bytes = xlsx(SHEET_HEAD + "<row r=\"1\"><c r=\"A1\"><v>8</v>", null);

        BillParseException e = assertThrows(BillParseException.class, () -> reader.read(bytes));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError(),
                "XML 没闭合时必须报错。静默返回已经读到的半张表的话，"
                        + "管理员会以为「对完了」，而少掉的那些记录没有任何痕迹");
    }

    // ==================================================================
    // 分派依据
    // ==================================================================

    @Test
    @DisplayName("只认 PK 开头的文件")
    void supports_onlyZipContainer() {
        assertTrue(reader.supports("PK".getBytes(StandardCharsets.UTF_8)),
                "xlsx 是 zip 容器，这是它唯一的、可靠的文件头特征");
        assertFalse(reader.supports("交易时间,交易单号".getBytes(StandardCharsets.UTF_8)),
                "CSV 是纯文本，不归本类读");
        assertFalse(reader.supports(null), "null 不能抛 NPE");
        assertFalse(reader.supports(new byte[]{'P'}), "只有一个字节时不能越界读");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 造一个最小的 xlsx。
     *
     * @param sheetXml  工作表 XML，可为 null（不写入该条目）
     * @param sharedXml 共享字符串 XML，可为 null
     * @return 文件字节
     */
    private static byte[] xlsx(String sheetXml, String sharedXml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            if (sharedXml != null) {
                zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
                zip.write(sharedXml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (sheetXml != null) {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("造测试用的 xlsx 失败", e);
        }
        return out.toByteArray();
    }

    /**
     * 把一个条目打进 zip，用于「是压缩包但不是 Excel」这类用例。
     *
     * @param name    条目名
     * @param content 内容
     * @return 文件字节
     */
    private static byte[] zipOf(String name, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("造测试用的 zip 失败", e);
        }
        return out.toByteArray();
    }

    /**
     * 拼一份共享字符串表。
     *
     * @param items 依次的字符串
     * @return XML
     */
    private static String sharedStrings(String... items) {
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst>");
        for (String item : items) {
            sb.append("<si><t>").append(item).append("</t></si>");
        }
        return sb.append("</sst>").toString();
    }
}
