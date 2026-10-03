package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;

import java.util.List;

/**
 * 对账的入口：字节 → 一批收款记录。
 *
 * <p>把两层的活串起来：{@link BillRowReaders} 负责「文件怎么变成格子」，
 * 三个 {@link BillParser} 负责「哪些格子有意义」。调用方（{@code ReconcileService}）
 * 只需要这一句。
 *
 * <h3>怎么挑解析器</h3>
 *
 * <p>按顺序问每个解析器「表头在第几行」，第一个给出非负答案的就用它。
 * <b>顺序有意义</b>：微信与支付宝的标记都更严（各要求一个对方没有的列），
 * 标准模板最宽松，所以它排在最后当兜底 ——
 * 具体的互斥关系写在 {@link StandardBillParser} 的类注释里。
 *
 * <p>与 {@code LockService} / {@code PaymentGateway} / {@code OcrService}
 * 是同一个策略模式，但<b>不走 Spring 注入</b>：这三个解析器全是无状态的纯逻辑，
 * 而「谁排前面」在这里是语义而不是偏好 —— 注入的顺序取决于包扫描与 {@code @Order}，
 * 不容易一眼看出来，也没法脱离 Spring 验证。
 *
 * <h3>认不出来时说什么</h3>
 *
 * <p>必须把<b>认出的表头原样带出去</b>。理由很实在：真实账单的列名只有拿到真文件
 * 才能完全确认，而现在（2026-09-30）手上没有样本，别名表是按公开资料写的。
 * 管理员把这句话发回来，别名表照着补一行就修好了；
 * 只说一句「格式不对」，管理员那边除了反复重试没有别的动作可做。
 */
public final class BillParserDispatcher {

    /** 解析器，<b>按「先具体、后兜底」排列</b>，顺序有意义，见类注释 */
    private static final List<BillParser> PARSERS = List.of(
            new WechatBillParser(),
            new AlipayBillParser(),
            new StandardBillParser());

    /** 认不出格式时，回显给管理员看的行数 */
    private static final int PREVIEW_ROWS = 3;

    /** 回显时每行最多显示多少个字符 */
    private static final int PREVIEW_ROW_LENGTH = 200;

    /**
     * 解析一份账单文件。
     *
     * @param bytes 文件字节
     * @return 解析结果，含渠道、记录与两个计数
     * @throws BillParseException 文件读不出来（{@code RECONCILE_BILL_ENCODING}）、
     *                            认不出表头或缺列（{@code RECONCILE_BILL_FORMAT}）
     */
    public static BillParseResult parse(byte[] bytes) {
        List<List<String>> rows = BillRowReaders.read(bytes);

        for (BillParser parser : PARSERS) {
            int headerIndex = parser.locateHeader(rows);
            if (headerIndex >= 0) {
                return parser.parse(rows, headerIndex);
            }
        }

        throw new BillParseException(ErrorCode.RECONCILE_BILL_FORMAT,
                "认不出这份账单的表头，请上传微信/支付宝导出的原始文件。"
                        + "文件开头是这样的：「" + preview(rows) + "」");
    }

    /**
     * 把文件开头几行拼成一句提示。
     *
     * <p>给管理员看的，所以<b>要短</b>：一整张表贴进提示框里没法读。
     * 取前几行、每行截断，足够他看出「自己传的是不是账单」以及「表头长什么样」。
     *
     * @param rows 全部行
     * @return 预览文本
     */
    private static String preview(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(rows.size(), PREVIEW_ROWS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            String line = String.join(" | ", rows.get(i));
            sb.append(line.length() > PREVIEW_ROW_LENGTH
                    ? line.substring(0, PREVIEW_ROW_LENGTH) + "…"
                    : line);
        }
        return sb.length() == 0 ? "（空文件）" : sb.toString();
    }

    /** 工具类，不实例化 */
    private BillParserDispatcher() {
    }
}
