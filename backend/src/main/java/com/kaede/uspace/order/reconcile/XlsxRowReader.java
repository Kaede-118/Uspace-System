package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * 读 xlsx 账单（微信 App 导出的就是这种）。
 *
 * <h3>xlsx 是什么</h3>
 *
 * <p>它是一个 <b>zip 包</b>，里面装着若干 XML 文件。一份账单用得到的有两个：
 * <ul>
 *   <li>{@code xl/sharedStrings.xml} —— <b>共享字符串表</b>。表格里所有的文字
 *       （包括表头「交易时间」）都集中放在这里，单元格里只存一个下标 ——
 *       这是 Excel 为了压缩体积做的去重</li>
 *   <li>{@code xl/worksheets/sheet1.xml} —— 数据本身</li>
 * </ul>
 *
 * <p>所以读它不需要 Apache POI 那 20MB 的依赖，一共就三步：
 * 解压 → 把共享字符串读成一个 List → 按「行 / 单元格」把下标换回文字。
 * 本类只实现账单用得到的那一小部分（不做公式、样式、合并单元格、图表），
 * 这正是可以自己写的底气 —— 通用的 xlsx 解析器要复杂一个量级。
 *
 * <h3>三个必须踩对的坑</h3>
 *
 * <ol>
 *   <li><b>空单元格在文件里是被省略的。</b> 若一行里第 C 列没有值，
 *       XML 里就<b>根本没有那个 {@code <c>} 元素</b>，单元格之间不是顺序对应的。
 *       所以必须解析 {@code <c r="B3">} 里的列字母来定位（A=0、B=1、AA=26），
 *       数数是不行的。<b>漏了这一条的后果是金额与单号整体串位，而且不报任何错</b> ——
 *       本类里没有比这更危险的一处。</li>
 *   <li><b>单元格的值有三种来源</b>：{@code t="s"} 时 {@code <v>} 是共享字符串的
 *       下标；{@code t="inlineStr"} 时值在 {@code <is><t>} 里；没有 {@code t}
 *       或 {@code t="str"} 时 {@code <v>} 就是值本身。</li>
 *   <li><b>共享字符串表可能不存在</b>（整份文件都用 inlineStr 的文件），
 *       不能当成必读 —— 读不到就用空表，让下标解析自然失败成空串。</li>
 * </ol>
 *
 * <h3>解压体积上限</h3>
 *
 * <p>上传大小由 {@code spring.servlet.multipart.max-file-size} 限制在 2MB，
 * 但 zip 是压缩的 —— 2MB 的 zip 可以解出几个 GB（zip 炸弹）。
 * 所以这里对每个条目解压后的字节数另设一个上限，
 * 与「上传文件按 magic bytes 判类型」是同一条思路：<b>不信客户端给的东西</b>。
 */
public final class XlsxRowReader implements BillRowReader {

    /** 单个条目解压后的字节上限。一份账单撑死几 MB，64MB 已经是三个数量级的余量 */
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    /** 共享字符串表在 zip 里的路径，xlsx 规范固定 */
    private static final String SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml";

    /** 工作表的路径前缀 */
    private static final String WORKSHEET_PREFIX = "xl/worksheets/";

    /**
     * 只认 zip 容器的文件头 {@code PK}。
     *
     * <p>比 {@code PK\x03\x04} 宽松一个字节：判到「这是个 zip」就够了 ——
     * 而它若不是一份 xlsx，{@link #read} 会给出「这里面没有 Excel 工作表」这种
     * 准确得多的提示，比在这里拦下来要好。
     */
    @Override
    public boolean supports(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    /**
     * 读出 zip 里那两个 XML，解析成「行 × 列」。
     *
     * @param bytes xlsx 文件的字节
     * @return 行列表；所有行都会被补齐到同一个列宽
     * @throws BillParseException 不是合法 zip、是加密 zip、里面没有工作表、
     *                            解压后过大，或 XML 损坏
     */
    @Override
    public List<List<String>> read(byte[] bytes) {
        byte[] sharedXml = null;
        byte[] sheetXml = null;
        String sheetName = null;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (SHARED_STRINGS_ENTRY.equals(name)) {
                    sharedXml = readEntry(zip, name);
                } else if (isWorksheet(name) && (sheetName == null || name.compareTo(sheetName) < 0)) {
                    // 多张工作表时取名字最小的那张（通常是 sheet1）。
                    // 不依赖 zip 里条目的先后顺序 —— 那个顺序规范里没有保证。
                    sheetXml = readEntry(zip, name);
                    sheetName = name;
                }
            }
        } catch (ZipException e) {
            // 加密 zip 走这一支。微信账单发到邮箱的就是一个加密 zip，
            // 密码在微信里单独给 —— 这是管理员最容易卡住的一步，所以文案要说透。
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这个压缩包打不开（可能设了密码）。若它是从邮箱下载的账单压缩包，"
                            + "请先用微信或邮件里给的密码解压，再上传里面的文件");
        } catch (IOException e) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这个文件不是完整的压缩包，可能上传时损坏了，请重新上传");
        }

        if (sheetXml == null) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这个压缩包里没有 Excel 工作表。若它是从邮箱下载的账单压缩包，"
                            + "请先解压，再上传里面的文件");
        }

        List<String> shared = sharedXml == null ? List.of() : parseSharedStrings(sharedXml);
        return parseSheet(sheetXml, shared);
    }

    /**
     * 解出一张工作表的「行 × 列」。
     *
     * @param xml    工作表 XML 的字节
     * @param shared 共享字符串表，可能为空表
     * @return 行列表，已补齐到统一列宽
     * @throws BillParseException XML 结构损坏时
     */
    private static List<List<String>> parseSheet(byte[] xml, List<String> shared) {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader reader = null;
        try {
            reader = newFactory().createXMLStreamReader(new ByteArrayInputStream(xml));

            List<String> currentRow = null;
            String cellRef = null;
            String cellType = null;
            StringBuilder cellValue = null;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "row" -> currentRow = new ArrayList<>();
                        case "c" -> {
                            cellRef = reader.getAttributeValue(null, "r");
                            cellType = reader.getAttributeValue(null, "t");
                            cellValue = new StringBuilder();
                        }
                        // <v> 是单元格的值；<t> 只出现在 inlineStr 的 <is> 里。
                        // 两者的处理是一样的：把文本读进来。具体怎么解释由 </c> 时决定。
                        case "v", "t" -> {
                            if (cellValue != null) {
                                cellValue.append(reader.getElementText());
                            }
                        }
                        default -> {
                            // 样式、公式、合并单元格等一律不关心
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "c" -> {
                            if (currentRow != null && cellValue != null) {
                                int column = columnIndex(cellRef);
                                ensureSize(currentRow, column + 1);
                                currentRow.set(column, resolveCell(cellType, cellValue.toString(), shared));
                            }
                            cellValue = null;
                        }
                        case "row" -> {
                            if (currentRow != null) {
                                rows.add(currentRow);
                            }
                            currentRow = null;
                        }
                        default -> {
                            // 同上，不关心
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这个 Excel 文件的内容读不出来，可能已损坏，请在 Excel 里重新另存一次");
        } finally {
            closeQuietly(reader);
        }

        // 统一列宽：省略掉的尾部空列不会出现在 XML 里，不补齐的话解析层取列会越界
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        for (List<String> row : rows) {
            ensureSize(row, width);
        }
        return rows;
    }

    /**
     * 解出共享字符串表。
     *
     * <p>每个 {@code <si>} 是一条字符串。它里面可能直接是 {@code <t>文本</t>}，
     * 也可能是富文本 {@code <r><t>前</t></r><r><t>后</t></r>} ——
     * 后者的几个片段要拼起来才是一条完整的字符串，所以这里对 {@code <si>} 里
     * <b>所有的</b> {@code <t>} 依次追加。
     *
     * @param xml 共享字符串表 XML 的字节
     * @return 字符串列表，下标即单元格里 {@code <v>} 的值
     * @throws BillParseException XML 结构损坏时
     */
    private static List<String> parseSharedStrings(byte[] xml) {
        List<String> strings = new ArrayList<>();
        XMLStreamReader reader = null;
        try {
            reader = newFactory().createXMLStreamReader(new ByteArrayInputStream(xml));
            StringBuilder current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("si".equals(reader.getLocalName())) {
                        current = new StringBuilder();
                    } else if ("t".equals(reader.getLocalName()) && current != null) {
                        current.append(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "si".equals(reader.getLocalName()) && current != null) {
                    strings.add(current.toString());
                    current = null;
                }
            }
        } catch (XMLStreamException e) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这个 Excel 文件的内容读不出来，可能已损坏，请在 Excel 里重新另存一次");
        } finally {
            closeQuietly(reader);
        }
        return strings;
    }

    /**
     * 把一个单元格的原始内容换成人看得懂的文字。
     *
     * @param type   单元格的 {@code t} 属性，可为 null（表示数值）
     * @param raw    {@code <v>} 里的原文
     * @param shared 共享字符串表
     * @return 单元格的文本；下标越界时返回空串（宁可空着，也不要抛异常打断整份账单）
     */
    private static String resolveCell(String type, String raw, List<String> shared) {
        if (type == null) {
            return raw;
        }
        return switch (type) {
            // 共享字符串：<v> 是下标
            case "s" -> {
                int index = parseIntOrMinusOne(raw);
                yield index >= 0 && index < shared.size() ? shared.get(index) : "";
            }
            // 内联字符串与公式的字符串结果：<v> / <is><t> 就是值本身
            case "inlineStr", "str" -> raw;
            // 布尔、错误值等一律原样带出去，由解析层决定认不认
            default -> raw;
        };
    }

    /**
     * 把 {@code r="AB12"} 里的列字母换算成 0 起的下标。
     *
     * <p>A=0、B=1、…、Z=25、AA=26 —— 二十六进制，但<b>没有零</b>
     *（A 是 1 而不是 0），所以每一步都要「先乘再加、最后减一」，
     * 直接当二十六进制算是错的。
     *
     * @param ref 单元格引用，可为 null（此时退化为第一列）
     * @return 列下标，最小 0
     */
    private static int columnIndex(String ref) {
        if (ref == null) {
            return 0;
        }
        int index = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                index = index * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                index = index * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return Math.max(index - 1, 0);
    }

    /**
     * 把一行补齐到指定列数，缺的位置填空串。
     *
     * @param row  行
     * @param size 目标列数
     */
    private static void ensureSize(List<String> row, int size) {
        while (row.size() < size) {
            row.add("");
        }
    }

    /**
     * 读一个 zip 条目，并挡住解压炸弹。
     *
     * @param zip  zip 流，当前位于该条目上
     * @param name 条目名，只用于错误提示
     * @return 条目内容
     * @throws IOException 读取出错
     * @throws BillParseException 解压后超过 {@link #MAX_ENTRY_BYTES}
     */
    private static byte[] readEntry(ZipInputStream zip, String name) throws IOException {
        byte[] data = zip.readNBytes(MAX_ENTRY_BYTES + 1);
        if (data.length > MAX_ENTRY_BYTES) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这个 Excel 文件解压后过大（" + name + "），不是一份账单该有的体积");
        }
        return data;
    }

    /**
     * 判断 zip 条目是不是一张工作表。
     *
     * @param name 条目名
     * @return 是工作表则 true
     */
    private static boolean isWorksheet(String name) {
        return name.startsWith(WORKSHEET_PREFIX) && name.endsWith(".xml");
    }

    /**
     * 造一个关闭了 DTD 与外部实体的 XML 解析器。
     *
     * <p><b>这是 XXE 防御</b>：默认的 {@code XMLInputFactory} 会去解析文档里声明的
     * 外部实体，于是一份精心构造的 xlsx 能读到服务器上的本地文件
     *（{@code file:///etc/passwd}）甚至发起网络请求。
     * 文件是管理员上传的、本系统的管理员就是店主，威胁不大 ——
     * 但防御的代价只有两行，不值得为省它留一个口子。
     *
     * @return 解析器工厂
     */
    private static XMLInputFactory newFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        setPropertyQuietly(factory, XMLInputFactory.SUPPORT_DTD, false);
        setPropertyQuietly(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * 设置解析器属性，不认这个属性就算了。
     *
     * <p>不同 JDK / 不同 XML 实现支持的属性集不完全一样，设置不认的属性会抛
     * {@code IllegalArgumentException}。上面那两个是安全加固、不是功能的一部分，
     * 所以设不上也不该让整份账单读不出来。
     *
     * @param factory 工厂
     * @param name    属性名
     * @param value   属性值
     */
    private static void setPropertyQuietly(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // 实现不支持，跳过
        }
    }

    /**
     * 把字符串转成整数，转不动返回 -1。
     *
     * @param raw 原文
     * @return 整数；格式不对时 -1
     */
    private static int parseIntOrMinusOne(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 关闭解析器，失败不管。
     *
     * @param reader 解析器，可为 null
     */
    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // 结果已经拿到了，关闭失败不影响什么
        }
    }
}
