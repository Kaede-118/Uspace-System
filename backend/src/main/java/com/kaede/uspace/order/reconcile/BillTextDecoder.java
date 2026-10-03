package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 把账单文件的字节解成文本。
 *
 * <p>纯静态、无状态、不碰 Spring —— 与 {@code OcrTextParser}、{@code CsvReader}
 * 同一类东西，可以脱离一切环境单测。
 *
 * <h3>为什么要专门一个类来判编码</h3>
 *
 * <p>因为两份真实账单的编码不一样：<b>微信导出的带 UTF-8 BOM，支付宝导出的没有 BOM
 * 且是 GBK</b>。硬编码任何一种都会让另一种整份文件变成乱码 ——
 * 而乱码的后果不是报错，是<b>表头认不出</b>（列名变成了对不上的字），
 * 于是管理员看到的提示是「认不出账单表头」，跟他心里想的「我明明传的就是账单」对不上。
 *
 * <p>判定顺序本身就是设计，从「最确定的证据」到「最不确定的猜测」：
 * <ol>
 *   <li><b>文件头</b> —— 一眼就能看出来的非文本文件（zip / xls / 图片 / PDF），
 *       直接说清「你传的是什么、该怎么办」，不浪费一次「认不出表头」</li>
 *   <li><b>BOM</b> —— 有 BOM 就是确定的，不必猜</li>
 *   <li><b>严格 UTF-8 试解</b> —— 失败才回退 GBK</li>
 * </ol>
 *
 * <p><b>为什么优先猜 UTF-8 而不是 GBK</b>：UTF-8 的字节序列约束强，
 * 一份 GBK 文件能「碰巧」全部构成合法 UTF-8 的概率极低；反过来，
 * 一份 UTF-8 文件几乎必然包含非法 GBK 序列（GBK 对高位字节的映射宽松，
 * 但 UTF-8 的多字节序列里常出现 GBK 未定义的位置）。
 * 也就是说，<b>「严格 UTF-8 解不动 → 那就是 GBK」这个推断方向是可靠的</b>，
 * 反过来则不成立。
 *
 * <p>万一判断错了会怎样：得到一份乱码文本 → 表头认不出 →
 * 报 {@code RECONCILE_BILL_FORMAT}。失败方式是安全的（明确报错），
 * 不会静默解析出一个金额错误的批次。
 */
public final class BillTextDecoder {

    /** UTF-8 BOM 的字节序列 */
    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * 字节 → 文本。
     *
     * @param bytes 账单文件的原始字节
     * @return 解码后的文本，BOM 已剥掉
     * @throws BillParseException 文件为空、是二进制文件、是 UTF-16，
     *                            或 UTF-8 与 GBK 都解不动
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING, "上传的文件是空的");
        }

        rejectBinary(bytes);

        if (startsWith(bytes, BOM_UTF8)) {
            return new String(bytes, BOM_UTF8.length, bytes.length - BOM_UTF8.length,
                    StandardCharsets.UTF_8);
        }

        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }

        String gbk = strictDecode(bytes, Charset.forName("GBK"));
        if (gbk != null) {
            return gbk;
        }

        throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                "这个文件的编码既不是 UTF-8 也不是 GBK，请用 Excel 另存为「CSV UTF-8」后重试");
    }

    /**
     * 挡掉一眼就能看出不是文本账单的文件。
     *
     * <p><b>这一段的全部价值在于「把话说准」</b>：不做它，一个 zip 包会被 GBK
     * 解成一堆乱码，最后报「认不出账单表头」—— 管理员拿着一个明明是账单的文件
     *（他刚从邮箱下载的），会觉得是系统坏了。
     * 而这里能直接告诉他「这是一个压缩包，请先解压」。
     *
     * <p>⚠️ <b>{@code PK} 那一条是第二道防线</b>：{@code PK} 开头意味着「是个 zip 容器」，
     * 而 xlsx 正是 zip —— 正常路径上这类文件由 {@code XlsxRowReader} 先接住
     *（它排在 {@code BillRowReaders} 的等待队列第一个）。
     * 留着它的理由与 {@code PaymentProofService} 校验 {@code proofUrl} 前缀相同：
     * 代价是几行代码，而漏掉的后果是「一个压缩包被当成 GBK 解成乱码，
     * 最后报『认不出表头』」—— 一句指错方向的提示。
     *
     * <p>老式 {@code .xls}（OLE2 复合文档）也要认出来：它和 csv 一样能从 Excel
     * 「另存为」得到，但它不是文本，本服务读不了 —— 提示要指向「另存为 xlsx 或 CSV」，
     * 而不是含糊的「格式不对」。
     *
     * @param bytes 文件字节
     * @throws BillParseException 认出二进制文件头时
     */
    private static void rejectBinary(byte[] bytes) {
        if (startsWithAscii(bytes, "PK")) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这是一个压缩包，请先解压，再上传里面的账单文件");
        }
        if (startsWith(bytes, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0})) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这是 Excel 的旧格式（.xls），本服务读不了。请用 Excel 打开后另存为 .xlsx 或 CSV");
        }
        if (startsWith(bytes, new byte[]{0x1F, (byte) 0x8B})) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这是一个 gzip 压缩包，请先解压，再上传里面的账单文件");
        }
        if (startsWithAscii(bytes, "%PDF")) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这是一个 PDF 文件，不是账单数据文件，请上传微信/支付宝导出的 CSV 或 xlsx");
        }
        if (startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G'})
                || startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这是一个图片文件，不是账单数据文件，请上传微信/支付宝导出的 CSV 或 xlsx");
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xFE})
                || startsWith(bytes, new byte[]{(byte) 0xFE, (byte) 0xFF})) {
            throw new BillParseException(ErrorCode.RECONCILE_BILL_ENCODING,
                    "这个文件是 UTF-16 编码，请用 Excel 另存为「CSV UTF-8」后重试");
        }
    }

    /**
     * 用指定字符集严格解码。
     *
     * <p><b>「严格」是这里唯一重要的事</b>：默认的 {@code new String(bytes, charset)}
     * 遇到非法字节会悄悄替换成 {@code U+FFFD}（那个黑色的问号方块）而不报错 ——
     * 于是 UTF-8 试解永远"成功"，GBK 那一支永远走不到，支付宝的账单全部读成乱码。
     * 把 {@code MalformedInput} 与 {@code UnmappableCharacter} 都设成
     * {@code REPORT}，解码失败才会抛异常、才有可能回退。
     *
     * @param bytes   文件字节
     * @param charset 目标字符集
     * @return 解码后的文本；有非法字节时返回 {@code null}
     */
    private static String strictDecode(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 判断字节数组是否以给定前缀开头。
     *
     * @param bytes  待检查的字节
     * @param prefix 前缀
     * @return 是则 true；{@code bytes} 比前缀还短时返回 false
     */
    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字节数组是否以给定的 ASCII 字符串开头。
     *
     * <p>给 {@code PK} 与 {@code %PDF} 这类「本身就是 ASCII」的文件头用，
     * 写起来比一串十六进制字面量好读。
     *
     * @param bytes  待检查的字节
     * @param prefix ASCII 前缀
     * @return 是则 true
     */
    private static boolean startsWithAscii(byte[] bytes, String prefix) {
        return startsWith(bytes, prefix.getBytes(StandardCharsets.US_ASCII));
    }

    /** 工具类，不实例化 */
    private BillTextDecoder() {
    }
}
