package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;
import lombok.Getter;

/**
 * 账单文件读不出来或解析不出记录。
 *
 * <p>只被 {@code order/reconcile} 子包内部抛出，由 {@code ReconcileService}
 * 统一接住、转成 {@code BizResult} 返回给管理员。
 *
 * <p><b>为什么用异常而不是返回值</b>：读取与解析是一条自顶向下的流水线
 *（解码 → 切分 → 找表头 → 逐行清洗），失败点在链路的每一层上。
 * 用返回值的话，每一层都要写一个「上一层的失败要原样传下去」的判断，
 * 而那种代码最容易漏 —— 漏一次就会把「表头没认出来」当成「解析出 0 条记录」，
 * 于是管理员看到的是「这个文件里没有可对账的收款记录」，
 * 而真正的原因（列名对不上）被吞掉了。
 *
 * <p>与 {@code BizResult} 的分工是清楚的：本异常在<b>子包内部</b>传递失败，
 * 到了 Service 边界就变成 {@code BizResult}。项目里「参数错误抛异常、
 * 业务失败走返回值」那条约定说的是 Service 的对外契约，这里是它的内部实现。
 *
 * <p><b>{@link #getMessage()} 是给管理员看的</b>，会经 {@code BizResult.message}
 * 一路展示到页面上，所以每一处都要说清「哪里不对、该怎么办」，
 * 而不是「parse failed」这种只有开发者看得懂的话。
 */
@Getter
public class BillParseException extends RuntimeException {

    /** 对应的业务错误码，决定最终返回给前端的 HTTP 状态与默认文案 */
    private final ErrorCode error;

    /**
     * 构造。
     *
     * @param error  业务错误码，取 {@code RECONCILE_BILL_ENCODING} /
     *               {@code RECONCILE_BILL_FORMAT} / {@code RECONCILE_BILL_EMPTY}
     * @param detail 具体原因，面向管理员，含「该怎么做」
     */
    public BillParseException(ErrorCode error, String detail) {
        super(detail);
        this.error = error;
    }
}
