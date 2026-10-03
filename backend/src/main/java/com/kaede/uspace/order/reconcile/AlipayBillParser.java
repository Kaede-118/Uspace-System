package com.kaede.uspace.order.reconcile;

import java.util.List;
import java.util.Map;

/**
 * 支付宝账单（导出的 CSV）的格式定义。
 *
 * <p><b>本类里没有逻辑</b>，只有三组数据，纪律见 {@link WechatBillParser} 的类注释。
 *
 * <p>公开格式的列（2026-09-30 记，待真文件校准）：
 * <pre>
 * 交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
 * </pre>
 *
 * <p>⚠️ 真文件到手后要注意它的<b>编码</b>：支付宝导出的是 GBK 而不是 UTF-8
 *（微信那份带 UTF-8 BOM）。这一条已经由 {@code BillTextDecoder} 处理，
 * 但校准列名时要用同一份文件验证，否则可能在 UTF-8 的样本上试通了、
 * 真文件却是乱码。
 */
class AlipayBillParser extends AbstractBillParser {

    @Override
    public ReconcileChannel channel() {
        return ReconcileChannel.ALIPAY;
    }

    /**
     * 支付宝的单号列叫「交易订单号」而不是微信的「交易单号」——
     * 这个差别正好是区分两份账单的依据。
     */
    @Override
    protected List<String> headerMarkers() {
        return List.of("交易时间", "交易订单号", "交易状态");
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 「商家订单号」同样<b>刻意不在单号的别名里</b>，理由见
     * {@link WechatBillParser#aliases()}。
     */
    @Override
    protected Map<BillColumn, List<String>> aliases() {
        return Map.of(
                BillColumn.TRADE_TIME, List.of("交易时间", "交易日期时间", "交易创建时间", "创建时间", "时间"),
                BillColumn.PAYMENT_NO, List.of("交易订单号", "订单号", "交易单号", "交易流水号", "流水号", "交易号"),
                BillColumn.AMOUNT, List.of("金额(元)", "金额", "交易金额", "发生额"),
                BillColumn.DIRECTION, List.of("收/支", "收支", "收/支类型", "资金方向"),
                BillColumn.STATUS, List.of("交易状态", "当前状态", "状态"),
                BillColumn.SUMMARY, List.of("商品说明", "商品", "商品名称", "交易对方", "备注"));
    }

    /** 支付宝的收支列取值与微信一致 */
    @Override
    protected List<String> incomeValues() {
        return List.of("收入");
    }

    /** 见 {@link WechatBillParser#successValues()} 里关于「精确匹配」的说明 */
    @Override
    protected List<String> successValues() {
        return List.of("交易成功", "已收钱", "收款成功", "交易完成");
    }

    /**
     * <b>暂时不按交易类型筛。</b>
     *
     * <p>不是判断支付宝没有这个问题 —— 问题一样存在（个人支付宝账单里也混着转账、
     * 红包、别处的退款，而它们的「收/支」同样是收入）。<b>只是手上没有真实样本</b>，
     * 而类型取值这件事猜不出来：写一个错的取值进白名单，结果是<b>一条记录都没有</b>；
     * 不写，结果是多一堆假差异。两者都不好，但前者至少一眼看得见。
     *
     * <p>真文件到手后照微信那条思路补上 —— 上面那份公开格式里，
     * 对应的列多半叫「交易分类」（见类注释）。
     *
     * @return 空列表，表示不筛
     */
    @Override
    protected List<String> incomeTypes() {
        return List.of();
    }
}
