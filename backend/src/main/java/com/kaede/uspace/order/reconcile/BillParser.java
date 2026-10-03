package com.kaede.uspace.order.reconcile;

import java.util.List;

/**
 * 把「行 × 列」解析成一批收款记录。
 *
 * <p>三种格式（微信 / 支付宝 / 标准模板）各有一个实现，
 * 但它们<b>共用同一份解析逻辑</b> —— 见 {@link AbstractBillParser}。
 * 本接口只是让 {@link BillParserDispatcher} 有个统一的抓手。
 *
 * <p>与 {@code BillRowReader} 的分工：那一层管「文件怎么变成格子」，
 * 这一层管「哪些格子有意义」。所以本层的实现<b>完全不知道</b>
 * 文件本来是 xlsx 还是 CSV，这正是把读取层单独抽出来的目的。
 */
public interface BillParser {

    /**
     * 本实现负责哪种账单。
     *
     * <p>这个值会写进批次的 {@code channel} 列，也决定页面上显示「微信 / 支付宝 / 标准模板」。
     *
     * @return 渠道
     */
    ReconcileChannel channel();

    /**
     * 找出表头在第几行。
     *
     * <p><b>为什么是「找表头」而不是「判断这是不是我的格式」</b>：
     * 这是同一个问题的两面，拆成两个方法会让调用方自己再扫一遍找表头行，
     * 白白多一次遍历，而且两处对「什么算表头」的判断还可能不一致。
     *
     * <p>真实账单的表头<b>不在第一行</b>：微信与支付宝导出的文件开头都有
     * 几行说明文字（「微信支付账单明细」「起始时间：…」之类），
     * 所以必须能跳过它们。
     *
     * @param rows 切好的行，可为空
     * @return 表头行的下标；不是本格式时返回 -1
     */
    int locateHeader(List<List<String>> rows);

    /**
     * 解析全部记录。
     *
     * @param rows        切好的行
     * @param headerIndex {@link #locateHeader} 给出的表头行下标
     * @return 解析结果
     * @throws BillParseException 缺少必需列时（{@code RECONCILE_BILL_FORMAT}）
     */
    BillParseResult parse(List<List<String>> rows, int headerIndex);
}
