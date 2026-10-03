package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 三种账单格式的解析逻辑，<b>全部在这里</b>。
 *
 * <h3>为什么是抽象基类，而不是三个各自实现</h3>
 *
 * <p>微信、支付宝、标准模板的差别只有四样：<b>列叫什么名字、哪些行算收入、
 * 哪些状态算成功、哪些交易类型算本店收款</b>。除此之外——找表头、定位列、
 * 清洗金额、解析时间、过滤、计数——一字不差。
 *
 * <p>让三个实现各写一份 {@code parse}，就是三份复制粘贴。而复制粘贴的失败方式
 * 在这个项目里是最贵的一种：<b>改一处漏两处，表现是「微信的解析对了、
 * 支付宝的少认了一列」，它不报错</b>。对账的产出是给管理员看的差异列表，
 * 少认一列的结果是差异列表里少了几条 —— 而没有人会发现自己少看了什么。
 *
 * <p>所以逻辑收在这里，子类只留「数据」。
 *
 * <p><b>给子类的纪律：不许有 {@code if}，不许有循环。</b>
 * 三个子类里只该出现 {@code List.of(...)} 与 {@code Map.of(...)}。
 * 这条比「请不要复制粘贴」有用得多 —— 它是可检查的，一眼就能看出来。
 *
 * <h3>两条贯穿始终的取向</h3>
 *
 * <p><b>一、按列名找列，不按列的位置。</b> 账单格式改版、多一列少一列、
 * 列的顺序变了，只要名字还在就还能读。反过来（按第 6 列是金额）一改版就整份错位，
 * 而错位的表现是「金额全是 0」这种不报错的错。
 *
 * <p><b>二、认不出就跳过，绝不猜。</b> 状态值认不出、金额解析不出来、
 * 单号缺失 —— 一律不计入 records 并累加 {@code skippedRows}，由页面显示给管理员。
 * <b>失败方向必须是「少算」而不是「多算」</b>：少算一笔会让管理员看到
 * 「跳过了 3 行」而去查；多算一笔会让系统凭空多出一笔收款，
 * 然后管理员照着一条不存在的账去追钱。
 */
abstract class AbstractBillParser implements BillParser {

    /** 找表头时最多扫前多少行。真实账单的说明文字通常不超过 10 行，30 行是充裕的余量 */
    private static final int MAX_HEADER_SCAN_ROWS = 30;

    /** 摘要的截断长度，与 {@code biz_reconcile_diff.bill_summary} 的列宽一致 */
    private static final int MAX_SUMMARY_LENGTH = 100;

    /**
     * Excel 日期序列号的纪元。
     *
     * <p>是 {@code 1899-12-30} 而不是 {@code 1900-01-01}：Excel 沿袭了 Lotus 1-2-3
     * 的一个 bug —— 它认为 1900 年是闰年（其实不是），于是序列号 60 对应
     * 一个不存在的 1900-02-29。把纪元往前挪一天，1900-03-01 之后的日期就都对得上了。
     */
    private static final LocalDateTime EXCEL_EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);

    /**
     * 认可为「一个合理的 Excel 序列号」的范围（1971-05-18 ~ 2118-12-31）。
     *
     * <p>加这个范围判断是为了不把普通数字误当成日期：账单里出现 {@code 8}（金额）、
     * {@code 20260930}（八位日期）这类数字很常见，而它们都不在这个区间里。
     */
    private static final BigDecimal EXCEL_SERIAL_MIN = new BigDecimal("25000");

    /** 见 {@link #EXCEL_SERIAL_MIN} */
    private static final BigDecimal EXCEL_SERIAL_MAX = new BigDecimal("80000");

    /** 秒/天，序列号的小数部分是当天的时刻 */
    private static final BigDecimal SECONDS_PER_DAY = new BigDecimal("86400");

    /**
     * 认时间用的格式，<b>按可能性从高到低</b>排列。
     *
     * <p>用 {@code M/d/H/m/s} 这种「最少一位」的写法而不是 {@code MM/dd}：
     * 后者要求必须补零，而账单里的月份常有 {@code 2026/9/1} 这种写法。
     * 反过来 {@code M} 也能吃下 {@code 09}，所以一个格式能覆盖两种写法。
     */
    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:m:s"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:m"),
            DateTimeFormatter.ofPattern("yyyy-M-d'T'H:m:s"),
            DateTimeFormatter.ofPattern("yyyy-M-d'T'H:m"));

    /** 纯数字（可能带小数），用于识别 Excel 日期序列号 */
    private static final String NUMBER_PATTERN = "\\d+(\\.\\d+)?";

    /**
     * 表头里必须同时出现的列名。
     *
     * <p>这是区分三种格式的<b>唯一依据</b>，所以取值要挑「另一种格式不会有的列」——
     * 比如微信的标记里放「当前状态」，支付宝用的是「交易状态」，
     * 这样一份标准模板文件（它也有「交易时间」与「交易单号」）就不会被微信认领，
     * 渠道也就不会被记错。挑得太松的后果是渠道记错，而那只影响报表归类、
     * 不影响对账结果，所以这个坑不至于致命，但仍然没必要踩。
     *
     * @return 必需的列名，写原样即可（比对前会归一化）
     */
    protected abstract List<String> headerMarkers();

    /**
     * 每个内部列对应的一组列名写法。
     *
     * <p>同一个列可以有很多叫法（微信叫「交易单号」，支付宝叫「交易订单号」），
     * 这里穷举它们。<b>列表的顺序即优先级</b>：取第一个在表头里出现的别名。
     *
     * @return 列 → 别名列表
     */
    protected abstract Map<BillColumn, List<String>> aliases();

    /**
     * 「收 / 支」列里哪些取值算收入。
     *
     * <p><b>必须精确匹配，不能用「包含」</b>：支付宝的退款记录状态是「退款成功」，
     * 用包含去判「成功」会把它当成收入，于是一笔退款变成了收款。
     * 这属于上面说的「多算」，是最坏的一类错误。
     *
     * @return 算作收入的取值
     */
    protected abstract List<String> incomeValues();

    /**
     * 状态列里哪些取值算「钱确实收到了」。
     *
     * <p>同样<b>必须精确匹配</b>，理由见 {@link #incomeValues}。
     *
     * <p>漏了某个成功状态的后果是那一行被计入 {@code skippedRows} ——
     * 少算，管理员在页面上看得见，来问一句就能补上。
     * 反过来把它当成失败则会让一笔真实的收款凭空消失。两害相权，取前者。
     *
     * @return 算作成功的取值
     */
    protected abstract List<String> successValues();

    /**
     * 「交易类型」列里哪些取值算本店的收款。
     *
     * <p><b>为什么要有这一条</b>：个人收款账号导出的账单里混着大量与本店无关的往来 ——
     * 转账、红包、别处买东西的退款，它们的「收/支」同样是<b>收入</b>、
     * 状态同样是「已存入零钱」这种成功态，靠方向与状态一条都拦不住。
     * 而放进去的每一笔都会变成一条找不到对应凭证的 {@code BILL_ONLY} 假差异 ——
     * 管理员看一屏假的，第二次就不看了。
     *
     * <p><b>必须是白名单而不是黑名单</b>：平台哪天新增一个交易类型，
     * 白名单默认「不算本店收款」= <b>少算</b>（页面上看得见「未参与对账 N 笔」），
     * 黑名单默认「算」= <b>多算</b>（凭空多一笔收款，而没有人会发现）。
     * 这与 {@link #successValues()} 那条「宁可少认」是同一条取向。
     *
     * <p><b>刻意声明成抽象方法、而不是给个「不限」的默认值</b>：
     * 「不筛」在这里是危险的那一侧，漏实现的代价是一屏假差异。
     * 让它编译不过，比让它静默地什么都放进来要好。确实没有这一列的格式，
     * 显式写一个空列表并把理由讲清楚。
     *
     * @return 算作本店收款的交易类型；<b>空列表表示不按类型筛</b>
     *（那是「本格式没有交易类型这一列」的表达，不是「什么都算」的默认值）
     */
    protected abstract List<String> incomeTypes();

    /**
     * 可以缺席的列。
     *
     * <p>默认是「商品 / 备注」与「交易类型」两列：前者是给人工看差异时多一个上下文的，
     * 与「这笔钱对不对得上」毫无关系，为它缺席让整份账单解析不出来是把装饰当成了承重墙；
     * 后者只有微信账单有，支付宝与标准模板都没有 —— 而它们本来就声明白名单为空（不筛），
     * 所以这一列缺席不算错。
     *
     * <p>标准模板另外覆写它，把「收/支」与「交易状态」也放进来 ——
     * 那份文件是管理员手工整理的，那两列在本店几乎恒为固定值，
     * 而漏填是手工整理最常见的失误。
     *
     * <p>⚠️ 覆写是<b>替换</b>而不是叠加，子类必须把这两个默认值一并列上。
     *
     * @return 缺失时不算错的列
     */
    protected Set<BillColumn> optionalColumns() {
        return Set.of(BillColumn.SUMMARY, BillColumn.TRADE_TYPE);
    }

    @Override
    public int locateHeader(List<List<String>> rows) {
        List<String> markers = normalizeAll(headerMarkers());
        if (markers.isEmpty()) {
            return -1;
        }
        int limit = Math.min(rows.size(), MAX_HEADER_SCAN_ROWS);
        for (int i = 0; i < limit; i++) {
            Set<String> cells = new LinkedHashSet<>();
            for (String cell : rows.get(i)) {
                String normalized = ReconcileTexts.normalize(cell);
                if (normalized != null) {
                    cells.add(normalized);
                }
            }
            if (cells.containsAll(markers)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public BillParseResult parse(List<List<String>> rows, int headerIndex) {
        Map<BillColumn, Integer> columns = locateColumns(rows.get(headerIndex));

        List<BillRecord> records = new ArrayList<>();
        int excludedCount = 0;
        int skippedRows = 0;
        // 被交易类型白名单挡下的那些类型，去重后按出现顺序记下来。
        // 只在「一条记录都没有」的报错里用到 —— 而那时管理员最需要知道的正是
        // 「这份账单里有哪些类型」，因为那决定了白名单该补什么。
        Set<String> excludedTypes = new LinkedHashSet<>();

        for (int i = headerIndex + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (isBlankRow(row)) {
                // 空行不算「跳过」—— 它不是一行数据，账单末尾几乎必有空行，
                // 把它计进跳过数会让管理员以为解析漏了什么
                continue;
            }

            // 先判方向：支出与「不计收支」单独计数，它们不参与匹配，也不算「认不出的行」。
            // 退款是系统主动发起的（撤销包场），系统本来就知道，对账时没必要再算一遍。
            if (!isIncome(cell(row, columns, BillColumn.DIRECTION))) {
                excludedCount++;
                continue;
            }

            // 再看交易类型。个人收款账号导出的账单里混着大量与本店无关的往来
            //（个人转账、红包、别处买东西的退款），它们的方向同样是「收入」、
            // 状态同样是成功态，只靠上面那一条拦不住。
            String tradeType = cell(row, columns, BillColumn.TRADE_TYPE);
            if (!isIncomeType(tradeType)) {
                excludedCount++;
                String type = ReconcileTexts.trimToNull(tradeType);
                if (type != null) {
                    excludedTypes.add(type);
                }
                continue;
            }

            if (!isSuccess(cell(row, columns, BillColumn.STATUS))) {
                skippedRows++;
                continue;
            }

            String rawNo = cell(row, columns, BillColumn.PAYMENT_NO);
            BigDecimal amount = parseAmount(cell(row, columns, BillColumn.AMOUNT));
            String paymentNo = ReconcileTexts.normalize(rawNo);
            if (paymentNo == null || amount == null) {
                // 单号或金额缺一个，这一行就没法参与对账。不猜 —— 计入跳过数让管理员看见
                skippedRows++;
                continue;
            }

            records.add(new BillRecord(
                    paymentNo,
                    ReconcileTexts.trimToNull(rawNo),
                    amount,
                    parseTime(cell(row, columns, BillColumn.TRADE_TIME)),
                    ReconcileTexts.truncate(
                            ReconcileTexts.trimToNull(cell(row, columns, BillColumn.SUMMARY)),
                            MAX_SUMMARY_LENGTH)));
        }

        return new BillParseResult(
                channel(), records, excludedCount, List.copyOf(excludedTypes), skippedRows);
    }

    /**
     * 把表头里的列名对应到内部列上。
     *
     * @param header 表头行
     * @return 内部列 → 列下标
     * @throws BillParseException 必需列有缺失时
     */
    private Map<BillColumn, Integer> locateColumns(List<String> header) {
        List<String> normalizedHeader = new ArrayList<>(header.size());
        for (String cell : header) {
            normalizedHeader.add(ReconcileTexts.normalize(cell));
        }

        Map<BillColumn, Integer> found = new EnumMap<>(BillColumn.class);
        for (Map.Entry<BillColumn, List<String>> entry : aliases().entrySet()) {
            for (String alias : entry.getValue()) {
                String key = ReconcileTexts.normalize(alias);
                if (key == null) {
                    continue;
                }
                int index = normalizedHeader.indexOf(key);
                if (index >= 0) {
                    found.put(entry.getKey(), index);
                    break;
                }
            }
        }

        Set<BillColumn> optional = optionalColumns();
        List<String> missing = new ArrayList<>();
        for (BillColumn column : BillColumn.values()) {
            if (!optional.contains(column) && !found.containsKey(column)) {
                missing.add(column.getLabel());
            }
        }
        if (!missing.isEmpty()) {
            /*
             * 这句提示是留给管理员与将来的自己的。
             *
             * 真实账单的列名只有拿到真文件才能完全确认，而现在（2026-09-30）
             * 手上没有样本，别名表是按公开格式写的。所以「认出的表头长什么样」
             * 必须原样带出去 —— 管理员把它发回来，别名表照着补一行就修好了。
             * 只说一句「格式不对」，两边都无从下手。
             */
            throw new BillParseException(ErrorCode.RECONCILE_BILL_FORMAT,
                    "账单里缺少这些列：" + String.join("、", missing)
                            + "。识别到的表头是「" + String.join(" | ", header) + "」，"
                            + "请确认上传的是账单本身而不是别的表格");
        }
        return found;
    }

    /**
     * 取一行的某个单元格。
     *
     * @param row     行
     * @param columns 列映射
     * @param column  内部列
     * @return 单元格文本，已去掉首尾空白；列不存在或行为空时返回 null
     */
    private static String cell(List<String> row, Map<BillColumn, Integer> columns, BillColumn column) {
        Integer index = columns.get(column);
        if (index == null || index >= row.size()) {
            return null;
        }
        return ReconcileTexts.trimToNull(row.get(index));
    }

    /**
     * 判断整行是不是空的。
     *
     * @param row 行
     * @return 每个单元格都是空或空白时返回 true
     */
    private static boolean isBlankRow(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断这一行是不是收入。
     *
     * <p><b>方向列缺失时算收入</b>（标准模板允许省略它）—— 这是本类里唯一一处
     * 「缺省即放行」的判定，因为本店收到的钱确实都是收入，
     * 而它缺席时把整份文件解析成空反而更糟。
     *
     * @param direction 收 / 支列的原文，可为 null
     * @return 算收入则 true
     */
    private boolean isIncome(String direction) {
        String normalized = ReconcileTexts.normalize(direction);
        if (normalized == null) {
            return true;
        }
        return containsValue(incomeValues(), normalized);
    }

    /**
     * 判断这一行的交易类型算不算本店的收款。
     *
     * <p><b>与 {@link #isIncome} / {@link #isSuccess} 的「缺省即放行」刻意不同：
     * 这里读不到类型就<b>排除</b>。</b> 那两列在标准模板里是允许缺席的，
     * 所以缺席要放行；而本列只在声明了白名单的格式里有意义 ——
     * 声明了白名单却没读到类型，说明格式变了，此时放行等于静默地把一堆
     * 无关往来当成收款，正是这个功能最怕的那一侧。
     *
     * @param tradeType 交易类型列的原文，可为 null
     * @return 算本店收款则 true
     */
    private boolean isIncomeType(String tradeType) {
        List<String> allowed = incomeTypes();
        if (allowed.isEmpty()) {
            // 本格式没有交易类型这一列（支付宝、标准模板），不筛
            return true;
        }
        String normalized = ReconcileTexts.normalize(tradeType);
        if (normalized == null) {
            return false;
        }
        return containsValue(allowed, normalized);
    }

    /**
     * 判断这一行的交易状态是不是「成功」。
     *
     * <p>状态列缺失时算成功，理由同 {@link #isIncome}。
     *
     * @param status 状态列的原文，可为 null
     * @return 算成功则 true
     */
    private boolean isSuccess(String status) {
        String normalized = ReconcileTexts.normalize(status);
        if (normalized == null) {
            return true;
        }
        return containsValue(successValues(), normalized);
    }

    /**
     * 精确比对一组取值（归一化后）。
     *
     * @param candidates 候选取值
     * @param normalized 已归一化的目标值
     * @return 命中则 true
     */
    private static boolean containsValue(List<String> candidates, String normalized) {
        for (String candidate : candidates) {
            String key = ReconcileTexts.normalize(candidate);
            if (normalized.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一组取值归一化。
     *
     * @param values 原值
     * @return 归一化后非 null 的值
     */
    private static List<String> normalizeAll(List<String> values) {
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            String normalized = ReconcileTexts.normalize(value);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    /**
     * 解析金额。
     *
     * <p>要洗掉的东西比想象中多：货币符号（{@code ¥} / 全角 {@code ￥}）、
     * 单位（{@code 元}）、千分位逗号。NFKC 已经把全角数字与全角符号统一成半角，
     * 但仍然要处理半角的那几种。
     *
     * <p>结果统一 {@code setScale(2)}：匹配时要与系统侧凭证的
     * {@code DECIMAL(10,2)} 比大小，两边标度一致才不会出现
     * 「{@code 8} 与 {@code 8.00} 看着一样却 {@code equals} 为假」这种事。
     *（比大小本身用 {@code compareTo}，标度不参与；但存进差异表时整洁些。）
     *
     * @param raw 原文，可为 null
     * @return 金额；解析不出来时返回 null
     */
    private static BigDecimal parseAmount(String raw) {
        String text = ReconcileTexts.normalize(raw);
        if (text == null) {
            return null;
        }
        text = text.replace("¥", "")
                .replace("￥", "")
                .replace("元", "")
                .replace(",", "");
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析交易时间。
     *
     * <p>先按常见格式逐个试，都试不通再看它是不是 Excel 的日期序列号 ——
     * <b>xlsx 里的日期天生是数字</b>（Excel 不存「2026-09-30 21:45:32」，
     * 它存的是「46281.9064…」这个天数），所以那一条不是可有可无的兜底：
     * 若账单导出时没有把日期列格式化成文本，就只有它能认出来。
     *
     * <p>解析不出来时返回 null 而<b>不是丢掉整条记录</b>：交易时刻只用来算
     * 对账窗口的起止，丢一笔收款比窗口算窄了严重得多。
     *
     * @param raw 原文，可为 null
     * @return 时刻；解析不出来时返回 null
     */
    private static LocalDateTime parseTime(String raw) {
        String text = ReconcileTexts.trimToNull(raw);
        if (text == null) {
            return null;
        }
        for (DateTimeFormatter format : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // 换下一种格式
            }
        }
        return parseExcelSerial(text);
    }

    /**
     * 把 Excel 的日期序列号换算成时刻。
     *
     * @param text 纯数字文本
     * @return 时刻；不是合理的序列号时返回 null
     */
    private static LocalDateTime parseExcelSerial(String text) {
        if (!text.matches(NUMBER_PATTERN)) {
            return null;
        }
        BigDecimal value = new BigDecimal(text);
        if (value.compareTo(EXCEL_SERIAL_MIN) < 0 || value.compareTo(EXCEL_SERIAL_MAX) > 0) {
            return null;
        }
        long days = value.longValue();
        long seconds = value.subtract(BigDecimal.valueOf(days))
                .multiply(SECONDS_PER_DAY)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return EXCEL_EPOCH.plusDays(days).plusSeconds(seconds);
    }
}
