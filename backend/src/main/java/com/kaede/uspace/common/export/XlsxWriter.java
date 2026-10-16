package com.kaede.uspace.common.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 最小 xlsx 写入器（<b>零依赖</b>）。
 *
 * <p><b>为什么不引 POI / EasyExcel</b>：本项目对账那一侧读 xlsx 就是手写的
 * （解压 zip + 读 {@code sheet1.xml}，见 {@code AbstractBillParser}），
 * 这里与它对称 —— 我们只需要「一张表、一行行、单元格是文本或数字」，
 * 为此拉进一个几十 MB、还带一堆传递依赖的库不划算，
 * 与本项目「前端不引 Playwright、tools 脚本零依赖」是同一条取舍。
 *
 * <p>只生成 xlsx 规范里最小的那一套部件（五份 XML）：工作簿、关系、
 * 内容类型、以及一张 sheet。字符串用 {@code inlineStr} 内联写，
 * 因此<b>不需要 sharedStrings.xml</b> —— 少一份部件就少一处能写错的地方。
 *
 * <p>⚠️ <b>数字与文本要分开写</b>：金额、时长这类必须是真正的数字单元格
 * （{@code <v>}），否则 Excel 里求和求不出来 —— 而这个导出存在的理由
 * 正是「拿出去做流水计算」（见 {@code ExportService} 的类注释）。
 *
 * <p>⚠️ <b>单元格文本必须转义</b>：备注里可能带 {@code &}、{@code <}，
 * 直接拼进 XML 会让 Excel 报「文件已损坏」。非法控制字符（XML 1.0 不允许的那些）
 * 也要剔掉 —— 它们同样只在 Excel 打开时才暴露。
 */
public final class XlsxWriter {

    /** 单元格文本长度上限（Excel 规范：32767 字符），超长截断而不是让文件打不开 */
    private static final int MAX_TEXT = 32767;

    /** 列宽下限：再窄连表头都看不全 */
    private static final double MIN_WIDTH = 8;

    /** 列宽上限：再宽就得左右滚了 */
    private static final double MAX_WIDTH = 40;

    private XlsxWriter() {
    }

    /**
     * 生成一份单 sheet 的 xlsx。
     *
     * @param sheetName sheet 名，会自动过滤 Excel 不允许的字符并截断到 31 字
     * @param headers   表头，按顺序
     * @param rows      数据行；每格可以是 {@link Number}（写成数字单元格）
     *                  或其它对象（{@code toString()} 后按文本写）；{@code null} 写空格
     * @return xlsx 文件字节
     */
    public static byte[] write(String sheetName, List<String> headers, List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            put(zip, "xl/workbook.xml", workbook(safeSheetName(sheetName)));
            put(zip, "xl/worksheets/sheet1.xml", sheet(headers, rows));
        } catch (IOException e) {
            // 全在内存里，正常不会失败；真失败说明环境有问题，包成运行时异常交给上层
            throw new IllegalStateException("生成 xlsx 失败", e);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        // ⚠️ 条目名一律用 '/'（zip 规范），不能用 File.separator —— Windows 上会生成
        // 带反斜杠的条目，Excel 认不出来，报的却是「文件格式不对」
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** 拼 sheet：表头一行 + 数据行。 */
    private static String sheet(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder(256 + rows.size() * 128);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                // ⚠️ <cols> 必须排在 <sheetData> 之前 —— 顺序反了 Excel 会直接判文件损坏，
                // 而那个提示不会告诉你是哪一段的问题
                .append("<cols>").append(colsXml(headers, rows)).append("</cols>")
                .append("<sheetData>");

        sb.append("<row r=\"1\">");
        for (int c = 0; c < headers.size(); c++) {
            sb.append(textCell(ref(c, 1), headers.get(c)));
        }
        sb.append("</row>");

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;   // 表头占了第 1 行
            List<Object> row = rows.get(i);
            sb.append("<row r=\"").append(rowNo).append("\">");
            for (int c = 0; c < headers.size(); c++) {
                Object value = c < row.size() ? row.get(c) : null;
                String ref = ref(c, rowNo);
                if (value instanceof Number number) {
                    sb.append(numberCell(ref, number));
                } else if (value == null) {
                    sb.append(textCell(ref, ""));
                } else {
                    sb.append(textCell(ref, value.toString()));
                }
            }
            sb.append("</row>");
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    /**
     * 按内容估算每列宽度，生成 {@code <cols>} 段。
     *
     * <p><b>为什么不让 Excel 自己适配</b>：xlsx 里没有「打开时自动调整列宽」这个开关 ——
     * Excel 的那个功能是打开后按一次快捷键，不会随文件保存。不给宽度的话，
     * 打开看到的是一堆 {@code ####} 或者被截断的表头（中文尤其明显），
     * 而「列太窄」这种事不会报任何错，只会让每个人打开后都先手动拖一遍。
     *
     * <p>估算口径：取该列<b>表头与所有单元格里最宽的那一个</b>，中文按两个字宽算，
     * 再留 2 个字符的余量；钳在 [{@value #MIN_WIDTH}, {@value #MAX_WIDTH}] 之间 ——
     * 太窄看不出、太宽要横向滚。它是估算不是精确排版：
     * 字号、字体、加粗都会影响实际宽度，做到「不用手动拖」即可。
     *
     * @param headers 表头
     * @param rows    数据行
     * @return {@code <col .../>} 串；没有列时返回空串
     */
    static String colsXml(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder(headers.size() * 60);
        for (int c = 0; c < headers.size(); c++) {
            double width = clamp(textWidth(headers.get(c)));
            for (List<Object> row : rows) {
                if (c < row.size()) {
                    width = Math.max(width, clamp(textWidth(cellText(row.get(c)))));
                }
            }
            sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(String.format(java.util.Locale.ROOT, "%.1f", width))
                    .append("\" customWidth=\"1\"/>");
        }
        return sb.toString();
    }

    /** 单元格的「显示文本」——数字也要按它的字面长度参与列宽估算。 */
    private static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    /**
     * 估算一段文本占几个字符宽：<b>中文（全角）算 2 个</b>，其余算 1 个，末尾留 2 个余量。
     *
     * @param text 文本
     * @return 估算宽度
     */
    static double textWidth(String text) {
        if (text == null) {
            return MIN_WIDTH;
        }
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            units += isWide(text.charAt(i)) ? 2 : 1;
        }
        return units + 2;
    }

    private static double clamp(double width) {
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
    }

    /**
     * 是不是「全角」字符（占两个字符宽）。
     *
     * <p>用的是一张常见的宽字符区间表（与终端里的 {@code wcwidth} 同源）——
     * 引一个 Unicode 库只为这件事不值当，而这些区间的判定已经足够准。
     *
     * @param ch 字符
     * @return 全角为 true
     */
    private static boolean isWide(char ch) {
        return ch >= 0x1100 && (ch <= 0x115F
                || ch == 0x2329 || ch == 0x232A
                || (ch >= 0x2E80 && ch <= 0xA4CF && ch != 0x303F)
                || (ch >= 0xAC00 && ch <= 0xD7A3)
                || (ch >= 0xF900 && ch <= 0xFAFF)
                || (ch >= 0xFE30 && ch <= 0xFE6F)
                || (ch >= 0xFF00 && ch <= 0xFF60)
                || (ch >= 0xFFE0 && ch <= 0xFFE6));
    }

    /** 数字单元格：不加 t 属性即为数字类型（Excel 据此参与求和）。 */
    private static String numberCell(String ref, Number value) {
        String literal = value instanceof BigDecimal decimal
                ? decimal.toPlainString()
                : String.valueOf(value);
        return "<c r=\"" + ref + "\"><v>" + literal + "</v></c>";
    }

    /** 文本单元格：inlineStr，内容转义后写入。 */
    private static String textCell(String ref, String value) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + escape(value) + "</t></is></c>";
    }

    /** 列号转字母：0→A、25→Z、26→AA。 */
    static String ref(int columnIndex, int rowNumber) {
        StringBuilder letters = new StringBuilder();
        int n = columnIndex;
        while (true) {
            letters.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
            if (n < 0) {
                break;
            }
        }
        return letters + String.valueOf(rowNumber);
    }

    /**
     * XML 转义 + 剔掉非法字符。
     *
     * <p>XML 1.0 不允许的字符（除制表、换行、回车之外的 C0 控制符）如果留在文本里，
     * 生成的文件在 Excel 里会被判为损坏 —— 而损坏提示不会告诉你是哪一格的问题。
     */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.length() > MAX_TEXT ? raw.substring(0, MAX_TEXT) : raw;
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    boolean legal = ch == '\t' || ch == '\n' || ch == '\r' || ch >= 0x20;
                    if (legal) {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** sheet 名：Excel 禁止 {@code []:*?/\\}，且不得超 31 字；空名回落默认值。 */
    static String safeSheetName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Sheet1";
        }
        String cleaned = raw.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (cleaned.isEmpty()) {
            return "Sheet1";
        }
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>""";

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>""";

    private static final String WORKBOOK_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>""";

    private static String workbook(String sheetName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + escape(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }
}
