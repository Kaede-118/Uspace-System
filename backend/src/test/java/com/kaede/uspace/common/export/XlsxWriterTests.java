package com.kaede.uspace.common.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link XlsxWriter} 的单元测试。
 *
 * <p>测的都是「写错了不会有任何报错、只在 Excel 打开时才暴露」的那几处：
 * 部件缺一份、数字写成了文本（求不了和）、{@code &} 没转义（报文件损坏）、
 * 控制字符没剔掉（同上）。这些都不是靠肉眼看代码能确认的，得真生成一遍再解开看。
 *
 * <p>验证方式是<b>解压自己生成的 zip 读 XML</b>：不引入 POI 来「读回来」，
 * 免得测试通过只是因为两个库对同一处错误口径一致。
 */
class XlsxWriterTests {

    /** 解开生成的 xlsx：条目名 → 文本内容。 */
    private static Map<String, String> unzip(byte[] xlsx) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    @Test
    @DisplayName("生成的是合法 zip，五份必需部件齐全")
    void write_producesRequiredParts() throws Exception {
        byte[] xlsx = XlsxWriter.write("测试", List.of("列"), List.of(List.of("值")));

        Map<String, String> parts = unzip(xlsx);
        assertTrue(parts.containsKey("[Content_Types].xml"), "缺内容类型表，Excel 打不开");
        assertTrue(parts.containsKey("_rels/.rels"));
        assertTrue(parts.containsKey("xl/workbook.xml"));
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"));
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"));
    }

    @Test
    @DisplayName("数字写成数字单元格、文本走 inlineStr —— 前者才能被 Excel 求和")
    void write_numberVsText() throws Exception {
        byte[] xlsx = XlsxWriter.write("S", List.of("金额", "备注"),
                List.of(List.of(new BigDecimal("23.50"), "现金")));

        String sheet = unzip(xlsx).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"A2\"><v>23.50</v></c>"), "金额应是数字单元格，实际：" + sheet);
        assertTrue(sheet.contains("t=\"inlineStr\""), "文本应走 inlineStr");
    }

    @Test
    @DisplayName("文本里的 & 与尖括号被转义，否则 Excel 报「文件已损坏」")
    void write_escapesXml() throws Exception {
        byte[] xlsx = XlsxWriter.write("S", List.of("备注"), List.of(List.of("A&B <C>")));

        String sheet = unzip(xlsx).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("A&amp;B &lt;C&gt;"), "应转义，实际：" + sheet);
        assertFalse(sheet.contains("A&B <C>"));
    }

    @Test
    @DisplayName("XML 不允许的控制字符被剔除")
    void write_stripsIllegalChars() throws Exception {
        // ⚠️ 用 (char) 1 拼而不是字面量 \u0001：Java 在词法分析【之前】就处理
        // unicode 转义，写进源码会变成真的控制字符，报的错与这里要测的东西无关
        String dirty = "好" + (char) 1 + "的";
        byte[] xlsx = XlsxWriter.write("S", List.of("备注"), List.of(List.of(dirty)));

        String sheet = unzip(xlsx).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("好的"), "控制字符应被剔除，实际：" + sheet);
        assertFalse(sheet.contains(String.valueOf((char) 1)));
    }

    @Test
    @DisplayName("每列都带按内容估算的列宽（否则中文表头一打开就被截断）")
    void write_setsColumnWidths() throws Exception {
        byte[] xlsx = XlsxWriter.write("S",
                List.of("订单号", "用户昵称", "金额(元)"),
                List.of(List.of("OD202610162217013921", "蓝色大肥鱼", new BigDecimal("129.00"))));

        String sheet = unzip(xlsx).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<cols>"), "要有列宽定义");
        assertEquals(3, sheet.split("<col ", -1).length - 1, "每列一个 col");

        // ⚠️ 顺序：<cols> 必须在 <sheetData> 之前，反了 Excel 直接判文件损坏
        assertTrue(sheet.indexOf("<cols>") < sheet.indexOf("<sheetData>"), "cols 要排在 sheetData 之前");

        // 第一列装的是 19 个 ASCII 字符的单号：宽度要跟着它走，而不是固定的最小值
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<col min=\"1\" max=\"1\" width=\"([0-9.]+)\"")
                .matcher(sheet);
        assertTrue(m.find(), "第一列应当有宽度");
        assertTrue(Double.parseDouble(m.group(1)) >= 20, "宽度应按内容估算，实际=" + m.group(1));
    }

    @Test
    @DisplayName("宽度估算：中文按两个字宽算")
    void textWidth_countsWideCharsAsTwo() {
        assertEquals(6.0, XlsxWriter.textWidth("abcd"), "4 个 ASCII + 2 的余量");
        assertEquals(10.0, XlsxWriter.textWidth("用户昵称"), "4 个中文字 = 8 宽 + 2 的余量");
    }

    @Test
    @DisplayName("列号超过 Z 进位成 AA")
    void ref_carriesOver() {
        assertEquals("A1", XlsxWriter.ref(0, 1));
        assertEquals("Z1", XlsxWriter.ref(25, 1));
        assertEquals("AA1", XlsxWriter.ref(26, 1));
        assertEquals("AB3", XlsxWriter.ref(27, 3));
    }

    @Test
    @DisplayName("sheet 名过滤掉 Excel 禁止的字符并截断到 31 字")
    void safeSheetName_filtersIllegal() {
        assertEquals("Sheet1", XlsxWriter.safeSheetName("   "));
        assertEquals("a b", XlsxWriter.safeSheetName("a[b]"));
        assertEquals(31, XlsxWriter.safeSheetName("x".repeat(40)).length());
    }

    @Test
    @DisplayName("空行与 null 单元格照样写出结构完整的行")
    void write_toleratesNulls() throws Exception {
        byte[] xlsx = XlsxWriter.write("S", List.of("A", "B"), List.of(java.util.Arrays.asList("有", null)));

        String sheet = unzip(xlsx).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"A2\""), "第一格应在");
        assertTrue(sheet.contains("<c r=\"B2\""), "null 也要占位，否则整行错列");
        assertTrue(sheet.contains("</sheetData>"));
    }
}
