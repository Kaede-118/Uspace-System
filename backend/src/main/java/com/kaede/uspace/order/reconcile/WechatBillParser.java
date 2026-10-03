package com.kaede.uspace.order.reconcile;

import java.util.List;
import java.util.Map;

/**
 * 微信账单（App 里导出的 xlsx）的格式定义。
 *
 * <p><b>本类里没有逻辑</b> —— 全部解析逻辑在 {@link AbstractBillParser}，
 * 这里只有「列叫什么名字、哪些取值算收入、哪些算成功、哪些交易类型算本店收款」
 * 这四组数据。纪律是：<b>不许有 {@code if}，不许有循环</b>，
 * 只该出现 {@code List.of(...)} 与 {@code Map.of(...)}。
 *
 * <h3>已按真实账单校准（2026-09-30，个人零钱账户导出）</h3>
 *
 * <p>表头与下面的别名表<b>逐字对上</b>，实测确认的事实：
 * <ul>
 *   <li>表头在<b>第 18 行</b>，前面 17 行是说明文字；</li>
 *   <li><b>空行在 XML 里被整个省略</b>（第 6、16 行根本不存在），
 *       所以列定位必须走 {@code r="A18"} 的引用，数数会整体串位；</li>
 *   <li>{@code 交易时间} 存的是 <b>Excel 日期序列号</b>（如 {@code 46295.84104166667}），
 *       不是文本 —— 由基类的 {@code parseExcelSerial} 接住；</li>
 *   <li>{@code 金额(元)} 存的是<b>纯数字</b>：{@code ¥#,##0.00} 只是样式表里的
 *       显示格式（{@code numFmt 165}），文件里既没有货币符号也没有千分位逗号。</li>
 * </ul>
 *
 * <p>⚠️ 这是<b>个人零钱账户</b>的账单。将来换成经营账户，导出的文件可能又是另一套
 *（表头不同，或只是交易类型取值不同）—— 那时参照本次的做法再校准一轮：
 * 表头不同会在报错里原样带出来，类型不同会在「一条记录都没有」的报错里列出来。
 */
class WechatBillParser extends AbstractBillParser {

    @Override
    public ReconcileChannel channel() {
        return ReconcileChannel.WXPAY;
    }

    /**
     * 微信特有的「当前状态」列是这里的关键 ——
     * 它是把微信与标准模板区分开的那一列（后者的状态列叫「交易状态」）。
     * 少了它，微信账单会被标准模板认领走，渠道就记错了。
     */
    @Override
    protected List<String> headerMarkers() {
        return List.of("交易时间", "交易单号", "当前状态");
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 「商户单号」<b>刻意不在单号的别名里</b>：那是商户自己的号，
     * 与平台账单上那一串不是一回事，拿它去对账永远对不上。
     * 别名列表里靠前的「交易单号」才是平台的号。
     */
    @Override
    protected Map<BillColumn, List<String>> aliases() {
        return Map.of(
                BillColumn.TRADE_TIME, List.of("交易时间", "交易日期时间", "交易创建时间", "创建时间", "时间"),
                BillColumn.PAYMENT_NO, List.of("交易单号", "订单号", "交易流水号", "流水号", "交易号"),
                BillColumn.AMOUNT, List.of("金额(元)", "金额", "交易金额", "发生额"),
                BillColumn.DIRECTION, List.of("收/支", "收支", "收/支类型", "资金方向"),
                BillColumn.TRADE_TYPE, List.of("交易类型", "交易分类"),
                BillColumn.STATUS, List.of("当前状态", "交易状态", "状态"),
                // 「交易对方」排在最前，而不是「商品」——
                // 真实表头里这两列都有，取第一个命中的那个，于是原来的顺序取到的是
                // 「收款方备注:二维码收款」这种写死的废话；而交易对方是付款人的微信昵称
                //（如「zmh 夏巧 (Natsutakum1)」），管理员看差异时最需要的就是「这谁付的」。
                BillColumn.SUMMARY, List.of("交易对方", "商品", "商品说明", "商品名称", "备注"));
    }

    /** 微信的收支列只有「收入」与「支出」两种取值 */
    @Override
    protected List<String> incomeValues() {
        return List.of("收入");
    }

    /**
     * 只认「二维码收款」—— 整份账单里<b>唯一真正属于本店</b>的交易类型。
     *
     * <p>实测的那份 40 笔账单是这样的：
     * <pre>
     * 二维码收款    3 笔     6.09 元   ← 只有这些是本店收的钱
     * 转账         27 笔 46407.35 元   ← 个人往来（房租、家人、朋友还钱）
     * 微信红包      1 笔    10.00 元
     * 各种「-退款」  9 笔  1340.80 元   ← 本人在别处消费的退款
     * </pre>
     * 后三类每一笔的「收/支」都是<b>收入</b>、状态都是「已存入零钱」这类成功态，
     * 靠方向与状态一条都拦不住 —— 不筛的话，解出来的 31 条记录里 28 条是假的。
     *
     * <p>⚠️ <b>换成经营账户后若收款类型不叫这个名字，在这里补一行即可</b> ——
     * 会先在「一条记录都没有」的报错里看到该补什么，见 {@link BillParseResult#excludedTypes()}。
     */
    @Override
    protected List<String> incomeTypes() {
        return List.of("二维码收款");
    }

    /**
     * 算「钱收到了」的状态取值。
     *
     * <p>列得细一些是必要的：<b>精确匹配</b>意味着漏一个取值就少认一笔账。
     * 而放宽成「包含『成功』」会把「退款成功」也算成收款 —— 那属于凭空多出一笔钱，
     * 是最坏的一类错误。两害相权，宁可少认（少认看得见，多认看不见）。
     *
     * <p>实测确认：{@code 二维码收款}那些行是「已收钱」。
     * 后面几个（{@code 已存入零钱} / {@code 已转账}）是转账与红包的状态 ——
     * 当前被 {@link #incomeTypes()} 挡在门外，留着是因为哪天要把转账也算作本店收款，
     * 它们立刻就用得上。
     */
    @Override
    protected List<String> successValues() {
        return List.of("支付成功", "已收钱", "对方已收钱", "已存入零钱", "已转账", "交易成功");
    }
}
