package com.kaede.uspace.order.reconcile;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统标准模板的格式定义。
 *
 * <p>这是本系统<b>自己定义</b>的一份格式，用于「微信与支付宝导出的文件都读不出来」
 * 时的退路：管理员照下面的表头把账单内容整理一份 CSV 传上来。
 *
 * <pre>
 * 交易时间,交易单号,金额(元),收/支,交易状态,备注
 * 2026-09-01 10:12:03,4200001234202609301234567890,8.00,收入,交易成功,舞萌
 * </pre>
 *
 * <h3>两个刻意的宽松</h3>
 *
 * <p><b>一、「收/支」与「交易状态」两列可以省略</b>（见 {@link #optionalColumns()}）。
 * 手工整理时最容易漏的就是这两列，而它们在本店场景下几乎恒为「收入」与「交易成功」——
 * 为一次漏填让整份文件解析不出来，代价与收益不成比例。
 * 这是三种格式里唯一允许缺列的一个，真实账单不行：那是机器导出的，
 * 缺列意味着格式变了，宁可报错也不要按缺省的语义猜。
 *
 * <p><b>二、表头标记只有「交易时间」与「交易单号」</b>，比微信那份少一个
 *「当前状态」—— 正因为少，它才能充当兜底：微信与支付宝的标记都更严，
 * 它们认不出的文件才轮到这里。而标准模板文件本身因为<b>没有</b>「当前状态」列，
 * 不会被微信抢走。
 *
 * <p><b>本类里没有逻辑</b>，只有数据，纪律见 {@link WechatBillParser} 的类注释。
 */
class StandardBillParser extends AbstractBillParser {

    @Override
    public ReconcileChannel channel() {
        return ReconcileChannel.STANDARD;
    }

    /**
     * 兜底的标记，只要求两列 —— 见类注释里关于「为什么少一个标记反而有用」的说明。
     */
    @Override
    protected List<String> headerMarkers() {
        return List.of("交易时间", "交易单号");
    }

    @Override
    protected Map<BillColumn, List<String>> aliases() {
        return Map.of(
                BillColumn.TRADE_TIME, List.of("交易时间", "交易日期时间", "交易创建时间", "创建时间", "时间"),
                BillColumn.PAYMENT_NO, List.of("交易单号", "订单号", "交易订单号", "交易流水号", "流水号", "交易号"),
                BillColumn.AMOUNT, List.of("金额(元)", "金额", "交易金额", "发生额"),
                BillColumn.DIRECTION, List.of("收/支", "收支", "收/支类型", "资金方向"),
                BillColumn.STATUS, List.of("交易状态", "当前状态", "状态"),
                BillColumn.SUMMARY, List.of("备注", "商品", "商品说明", "商品名称", "交易对方"));
    }

    /** 手工整理时「收」「收入」「收钱」都可能写，都收下 */
    @Override
    protected List<String> incomeValues() {
        return List.of("收入", "收", "收钱");
    }

    /** 同理，手工整理时「成功」与「交易成功」都可能写 */
    @Override
    protected List<String> successValues() {
        return List.of("交易成功", "成功", "支付成功", "已收钱");
    }

    /**
     * 允许缺席的列：收支方向与交易状态（另加基类默认就放行的「商品 / 备注」与「交易类型」）。
     *
     * <p>缺席时基类按「收入」与「成功」处理 —— 那两个缺省值就是这家店的真实情况。
     *
     * <p>⚠️ 这里必须把基类的默认值一并列上：覆写是<b>替换</b>而不是叠加，
     * 漏写 {@link BillColumn#SUMMARY} 的话，一份没填备注的标准模板会被判成缺列。
     */
    @Override
    protected Set<BillColumn> optionalColumns() {
        return Set.of(BillColumn.DIRECTION, BillColumn.STATUS,
                BillColumn.SUMMARY, BillColumn.TRADE_TYPE);
    }

    /**
     * <b>不按交易类型筛。</b>
     *
     * <p>这份文件是管理员照模板手工整理的，列什么、不列什么由他决定 ——
     * 而「整理时要不要写交易类型」这件事，多一个必填项就多一次填错的机会。
     * 目录本来就该只放本店的收款，与微信那份「从一堆个人往来里挑出本店的」不是同一件事。
     *
     * @return 空列表，表示不筛
     */
    @Override
    protected List<String> incomeTypes() {
        return List.of();
    }
}
