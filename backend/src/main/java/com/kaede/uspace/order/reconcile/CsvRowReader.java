package com.kaede.uspace.order.reconcile;

import java.util.List;

/**
 * 读 CSV 账单：解码 + 切分。
 *
 * <p>本类本身没有逻辑，只是把 {@link BillTextDecoder} 与 {@link CsvReader}
 * 串起来 —— 那两件事各自都值得单独测（一个管「字节怎么变成字」，
 * 一个管「字怎么变成格子」），所以分成两个纯静态类，
 * 这里只负责把它们接到 {@link BillRowReader} 这根线上。
 *
 * <p>{@link #supports} <b>恒返回 true</b>：它是兜底实现，
 * 「不是 xlsx 的都归我」。因此它在 {@link BillRowReaders} 的队列里<b>必须排最后</b>。
 */
public final class CsvRowReader implements BillRowReader {

    @Override
    public boolean supports(byte[] bytes) {
        return true;
    }

    @Override
    public List<List<String>> read(byte[] bytes) {
        return CsvReader.parse(BillTextDecoder.decode(bytes));
    }
}
