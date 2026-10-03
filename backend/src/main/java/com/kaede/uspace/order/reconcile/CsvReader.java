package com.kaede.uspace.order.reconcile;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 CSV 文本切成「行 × 列」。
 *
 * <p>纯静态、无状态，与 {@code BillTextDecoder}、{@code OcrTextParser} 同一类东西。
 *
 * <h3>为什么必须用字符级状态机，而不是 {@code split(",")}</h3>
 *
 * <p><b>因为账单的「商品」列几乎必然含逗号</b>（{@code 游戏币,A 套餐} 这种写法），
 * 而 {@code split(",")} 会让<b>每一行的列整体串位</b> —— 金额落到状态列上、
 * 单号落到备注列上，最后解析出的每一笔都是错的，
 * <b>而行数完全正常、不报任何错</b>。整个对账功能里没有比这更危险的一处。
 *
 * <p>这不是理论上的担心：CSV 的引号机制存在的唯一理由就是「字段里可以放分隔符」，
 * 所以只要账单里有任何一个字段被引号包起来，它里面就可能有逗号。
 *
 * <h3>只实现 RFC4180 里用得到的那部分</h3>
 *
 * <p>支持：引号包裹的字段、字段内的逗号与换行、{@code ""} 转义成一个 {@code "}、
 * 三种换行（{@code \r\n} / {@code \n} / 单独的 {@code \r}）。
 *
 * <p>不支持：多字符分隔符、转义字符（{@code \}）、注释行 —— 账单里都没有，
 * 实现了也没有样本能测。将来真遇到再加。
 *
 * <p><b>为什么不引入 commons-csv</b>：真正省下的只有下面那三十来行状态机，
 * 而「跳过表头前的说明行、表头别名、编码判定、列数不齐的容错」一样都省不掉；
 * 于是多一个依赖、多一份版本升级的负担，换来的只是一个自己能写清楚、
 * 且能逐条写用例钉住的函数。与 {@code tools/} 零依赖驱动 Chrome 是同一条取舍。
 */
public final class CsvReader {

    /**
     * 切分。
     *
     * <p><b>不做列数对齐</b>：每行切出几列就返回几列。对齐是解析层的事 ——
     * 它才知道表头有几列、少的那列该补什么。
     *
     * <p>空行会被保留成一个「只有一个空字段」的行。也交给解析层跳过：
     * 读取层与解析层的分工是「读取层只管字节怎么变成格子，解析层管哪些格子有意义」。
     *
     * @param text 已解码的文本，可为 null
     * @return 行列表；{@code text} 为 null 或空时返回空列表。<b>永远不返回 null</b>
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        // 「本行有没有内容」与「字段是不是空」是两回事：
        // 一行 "a,," 切出来是三个字段（后两个是空串），而一个空行切出来是一个空字段。
        // 收尾时靠这个标记决定要不要把最后一行交出去 —— 没有它的话，
        // 文件末尾的换行会多产生一个空行。
        boolean rowStarted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // 连续两个引号是一个转义，还原成一个字面量引号
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    // 引号内的一切都原样收下 —— 包括逗号与换行
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rowStarted = true;
                }
                case '"' -> {
                    // 引号只在字段开头有意义，字段中间出现的引号是字面量
                    //（RFC4180 规定这种写法非法，但真实文件里偶尔能见到，当成普通字符最宽容）
                    if (field.isEmpty()) {
                        inQuotes = true;
                    } else {
                        field.append('"');
                    }
                    rowStarted = true;
                }
                case '\r' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    rowStarted = false;
                    // \r\n 是一个换行，别把它数成两个
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    rowStarted = false;
                }
                default -> {
                    field.append(c);
                    rowStarted = true;
                }
            }
        }

        // 收尾：末行可能没有换行符。判断依据是「这一行有过内容」而不是
        // 「字段非空」—— 一行 "a," 的最后那个字段是空的，但它确实是一行。
        if (rowStarted || !field.isEmpty() || !row.isEmpty() || inQuotes) {
            row.add(field.toString());
            rows.add(row);
        }

        return rows;
    }

    /** 工具类，不实例化 */
    private CsvReader() {
    }
}
