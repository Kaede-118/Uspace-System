package com.kaede.uspace.order.reconcile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CsvReader} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不碰任何外部资源。</b>
 *
 * <p>这个类钉住的是整个对账功能里最危险的一处：<b>用 {@code split(",")} 切 CSV</b>。
 * 那种写法在账单的「商品」列含逗号时会让每一行的列整体串位 ——
 * 金额落到状态列上、单号落到备注列上，最后解析出的每一笔都是错的，
 * 而行数完全正常、不报任何错。所以下面第一条用例是必须的，不是锦上添花。
 */
class CsvReaderTests {

    // ==================================================================
    // 引号：这一组是必须的，不是边界情况
    // ==================================================================

    @Test
    @DisplayName("引号里的逗号算一个字段 —— 账单的「商品」列必然用到")
    void parse_commaInsideQuotesIsOneField() {
        List<List<String>> rows = CsvReader.parse("a,\"b,c\",d");

        assertEquals(1, rows.size(), "一行文本应当只切出一行");
        assertEquals(List.of("a", "b,c", "d"), rows.get(0),
                "引号里的逗号必须留在字段内。当成分隔符的话，后面每一列的取值都会往前串一位");
    }

    @Test
    @DisplayName("引号里的换行算一个字段 —— 字段内的换行不能被当成分行")
    void parse_newlineInsideQuotesIsOneField() {
        List<List<String>> rows = CsvReader.parse("a,\"第一行\n第二行\",c");

        assertEquals(1, rows.size(),
                "引号内的换行是字段内容。当成行分隔符的话，一条记录会被劈成两条，行数对不上");
        assertEquals(List.of("a", "第一行\n第二行", "c"), rows.get(0),
                "换行要原样保留在字段里");
    }

    @Test
    @DisplayName("两个连着的引号还原成一个引号")
    void parse_doubleQuoteEscape() {
        List<List<String>> rows = CsvReader.parse("\"他说\"\"好\"\",然后走了\"");

        assertEquals(List.of("他说\"好\",然后走了"), rows.get(0),
                "CSV 里用两个引号表示一个字面量引号，还原不了的话备注会多出引号");
    }

    @Test
    @DisplayName("字段中间的引号是普通字符，不进入引号模式")
    void parse_quoteInMiddleIsLiteral() {
        List<List<String>> rows = CsvReader.parse("a\"b,c");

        assertEquals(List.of("a\"b", "c"), rows.get(0),
                "引号只在字段开头才特殊。判错的话后面的逗号会被当成内容，整行的列数就少了");
    }

    // ==================================================================
    // 换行与收尾
    // ==================================================================

    @Test
    @DisplayName("\\r\\n 与 \\n 都算一次换行 —— 不能把 \\r\\n 数成两次")
    void parse_bothLineEndings() {
        assertEquals(2, CsvReader.parse("a\nb").size(), "\\n 要认");
        assertEquals(2, CsvReader.parse("a\r\nb").size(),
                "Windows 的 \\r\\n 是一个换行。数成两个的话每两行之间会多出一个空行");
        assertEquals(2, CsvReader.parse("a\rb").size(), "老式 Mac 的单独 \\r 也要认");
    }

    @Test
    @DisplayName("末尾没有换行的最后一行要认")
    void parse_lastRowWithoutTrailingNewline() {
        List<List<String>> rows = CsvReader.parse("a,b\nc,d");

        assertEquals(2, rows.size(), "最后一行没有换行符，但它是一行真实数据，不能丢");
        assertEquals(List.of("c", "d"), rows.get(1));
    }

    @Test
    @DisplayName("末尾的换行不多产生一行")
    void parse_trailingNewlineDoesNotAddRow() {
        assertEquals(1, CsvReader.parse("a,b\n").size(),
                "末尾换行之后没有内容，不该凭空多出一行 —— 那会让跳过计数多算一次");
        assertEquals(1, CsvReader.parse("a,b\r\n").size(), "\\r\\n 结尾同理");
    }

    // ==================================================================
    // 空字段与空行
    // ==================================================================

    @Test
    @DisplayName("连续逗号切出空字段 —— 空着与没有是两回事")
    void parse_emptyFieldsAreKept() {
        assertEquals(List.of("a", "", "c"), CsvReader.parse("a,,c").get(0),
                "中间的逗号表示这里有一个空字段，不能把它吞掉 —— 吞掉之后后面所有的列都会往前挪");
        assertEquals(List.of("a", "", ""), CsvReader.parse("a,,").get(0),
                "末尾的空字段同样要留着");
    }

    @Test
    @DisplayName("空行被保留成一行空字段，由解析层决定要不要跳过")
    void parse_blankLineIsKept() {
        List<List<String>> rows = CsvReader.parse("a\n\nb");

        assertEquals(3, rows.size(), "空行要留着 —— 读取层不管「哪些行有意义」，那是解析层的事");
        assertEquals(List.of(""), rows.get(1), "空行切出来是一个空字段");
    }

    // ==================================================================
    // 基本情形
    // ==================================================================

    @Test
    @DisplayName("空输入返回空列表，不抛异常")
    void parse_emptyInput() {
        assertTrue(CsvReader.parse(null).isEmpty(), "null 输入要返回空列表而不是 NPE");
        assertTrue(CsvReader.parse("").isEmpty(), "空串同理");
    }

    @Test
    @DisplayName("列数不一致时原样返回，由解析层按表头对齐")
    void parse_raggedRowsAreReturnedAsIs() {
        List<List<String>> rows = CsvReader.parse("a,b,c\n1,2\n3,4,5,6");

        assertEquals(3, rows.get(0).size(), "第一行三列");
        assertEquals(2, rows.get(1).size(),
                "第二行只有两列 —— 读取层不补齐、不截断，因为它还不知道表头有几列");
        assertEquals(4, rows.get(2).size(), "多出来的列也原样带着");
    }

    @Test
    @DisplayName("全角逗号不是分隔符")
    void parse_fullWidthCommaIsNotSeparator() {
        List<List<String>> rows = CsvReader.parse("游戏币，套餐,8.00");

        assertEquals(2, rows.get(0).size(),
                "全角逗号是字段内容。当成分隔符会把一个字段劈成两个，列数整体错位");
    }
}
