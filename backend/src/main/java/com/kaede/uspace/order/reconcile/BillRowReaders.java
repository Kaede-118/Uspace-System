package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;

import java.util.List;

/**
 * 按文件头把账单文件挑给合适的读取器。
 *
 * <p>与 {@code BillParserDispatcher} 是两个方向的同一件事：
 * 那个按<b>表头</b>挑解析器（认的是格式），这个按<b>文件头</b>挑读取器
 *（认的是容器）。一个文件要先过这一层，再过那一层。
 *
 * <p><b>顺序是刻意的：先 xlsx，后 CSV。</b>
 * {@link CsvRowReader#supports} 恒返回 true（它是兜底），
 * 谁排在它后面都永远不会被问到 —— 这条规则在 {@link #READERS} 里体现为
 * 「兜底的那个排最后」，改动顺序会让 xlsx 文件被当成 GBK 文本读成乱码。
 *
 * <p><b>为什么不做成 Spring 的 {@code List<BillRowReader>} 注入</b>：
 * 那样顺序就取决于包扫描与 {@code @Order}，而「哪个排前面」在这里是<b>语义</b>
 * 而不是偏好 —— 注入的顺序不容易一眼看出来，也没法在单测里脱离 Spring 验证。
 * 这里的读取器全是无状态的纯逻辑，自己 new 出来反而更清楚。
 *（对照 {@code PaymentTargetHandler} 那一套：那是 Service 层的实现，有依赖，
 * 所以走注入是对的。工具类不走。）
 */
public final class BillRowReaders {

    /** 读取器，按「先具体、后兜底」排列，顺序有意义，见类注释 */
    private static final List<BillRowReader> READERS = List.of(
            new XlsxRowReader(),
            new CsvRowReader());

    /**
     * 读成「行 × 列」。
     *
     * @param bytes 账单文件字节
     * @return 行列表
     * @throws BillParseException 所有读取器都读不出来
     */
    public static List<List<String>> read(byte[] bytes) {
        for (BillRowReader reader : READERS) {
            if (reader.supports(bytes)) {
                return reader.read(bytes);
            }
        }
        // 走不到这里：CsvRowReader 恒支持。留着是为了「将来有人把兜底那个删掉」
        // 时能立刻发现问题，而不是拿到一个没有任何解释的空列表。
        throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                "这个文件读不出来，请上传微信/支付宝导出的 CSV 或 xlsx");
    }

    /** 工具类，不实例化 */
    private BillRowReaders() {
    }
}
