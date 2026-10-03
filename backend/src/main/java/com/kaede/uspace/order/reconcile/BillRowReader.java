package com.kaede.uspace.order.reconcile;

import java.util.List;

/**
 * 把一份账单文件读成「行 × 列」。
 *
 * <p>这是对账流水线的最前面一层，职责只有一句话：
 * <b>把文件变成一张全是字符串的表格，不做任何解释</b>。
 * 「哪一列是金额」「这一行算不算收入」统统留给 {@link BillParser} ——
 * 因为那些判断与格式无关（微信、支付宝、标准模板用的是同一套规则），
 * 而「文件怎么变成格子」才与格式有关（CSV 走文本切分、xlsx 走 zip + XML）。
 *
 * <p><b>刻意不做类型转换</b>：{@code <v>8</v>} 里是什么就吐什么字符串。
 * 日期与金额的清洗都发生在解析层 —— 那里才有「这一列是金额」这个上下文，
 * 也才有单测的落点。读取层自己猜一遍的话，同一个「这是什么」的判断
 * 就会分散在两个地方，改一处漏一处的表现是「金额被解析成了日期」这种不报错的错。
 *
 * <p>与 {@code LockService}、{@code PaymentGateway}、{@code OcrService}
 * 是同一个模式：接口在这里，实现按文件类型分，由 {@link BillRowReaders} 挑。
 */
public interface BillRowReader {

    /**
     * 这份文件归不归本实现读。
     *
     * <p>判定只看<b>文件头</b>，不看扩展名 —— 扩展名是用户给的，能随便改
     *（与 {@code ImageStorage} 按 magic bytes 判图片类型是同一条理由）。
     *
     * @param bytes 文件字节，可能为 null
     * @return 本实现能读则 true
     */
    boolean supports(byte[] bytes);

    /**
     * 读成「行 × 列」。
     *
     * @param bytes 文件字节
     * @return 行列表，每行是若干列。读不出来时抛 {@link BillParseException}
     * @throws BillParseException 文件类型不认得、内容损坏或超出体积上限
     */
    List<List<String>> read(byte[] bytes);
}
