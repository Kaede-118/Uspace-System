package com.kaede.uspace.order.reconcile;

/**
 * 账单里本系统用得上的那几列。
 *
 * <p>包内可见 —— 它只是 {@link AbstractBillParser} 与三个格式定义类之间的一个约定，
 * 不是对外的模型。对外的模型是 {@link BillRecord}。
 *
 * <p><b>为什么要抽象出这一层，而不是直接用列名</b>：三种格式的列名各不相同
 *（微信叫「交易单号」、支付宝叫「交易订单号」、标准模板又叫回「交易单号」），
 * 而解析逻辑只有一份。这一层就是那份逻辑与三种列名之间的翻译。
 *
 * <p>每项自带一个中文名，用于「缺了哪一列」的错误提示 ——
 * 那句话说给管理员听，说「缺少列 PAYMENT_NO」等于没说。
 */
enum BillColumn {

    /** 交易时间。对账窗口的起止由它算出 */
    TRADE_TIME("交易时间"),

    /** 交易单号。匹配的主键，两边归一化之后比对 */
    PAYMENT_NO("交易单号"),

    /** 金额（元）。与系统侧凭证的 {@code amount} 用 {@code compareTo} 比 */
    AMOUNT("金额"),

    /** 收 / 支方向。只有收入参与匹配，退款与不计收支的只计数 */
    DIRECTION("收/支"),

    /**
     * 交易类型。用来把「与店铺收款无关的往来」挡在门外 ——
     * 个人账单里的转账、红包、别处的退款都以「收入」的形态出现，
     * 不筛的话每一笔都会变成一条找不到凭证的假差异。
     *
     * <p>只有微信账单有这一列；其余两种格式没有，按「不限」处理，
     * 见 {@code AbstractBillParser#incomeTypes()}。
     */
    TRADE_TYPE("交易类型"),

    /** 交易状态。只有成功的才算收到钱 */
    STATUS("交易状态"),

    /** 商品或备注。只用于人工看差异时多一个上下文，不参与任何判断 */
    SUMMARY("商品/备注");

    /** 面向管理员的中文列名，用在「缺少某某列」的提示里 */
    private final String label;

    BillColumn(String label) {
        this.label = label;
    }

    /**
     * 取中文列名。
     *
     * @return 列的中文名称
     */
    String getLabel() {
        return label;
    }
}
