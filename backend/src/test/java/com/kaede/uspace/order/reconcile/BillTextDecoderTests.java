package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BillTextDecoder} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>
 *
 * <p>钉住的是「两份真实账单的编码不一样」这件事：微信带 UTF-8 BOM、支付宝是无 BOM 的 GBK。
 * 判错的后果不是报错，而是<b>整份文件解成乱码 → 表头认不出</b>，
 * 于是管理员拿到一句「认不出账单表头」，跟他心里想的「我传的就是账单」对不上。
 */
class BillTextDecoderTests {

    /** UTF-8 BOM 的三个字节 */
    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** UTF-16 LE 的 BOM */
    private static final byte[] BOM_UTF16_LE = {(byte) 0xFF, (byte) 0xFE};

    // ==================================================================
    // 正常的三种
    // ==================================================================

    @Test
    @DisplayName("带 UTF-8 BOM 的文件：解码后首字符不能还是 BOM")
    void decode_stripsUtf8Bom() {
        String text = BillTextDecoder.decode(concat(BOM_UTF8, "交易时间,交易单号"));

        assertEquals("交易时间,交易单号", text,
                "BOM 必须剥掉。留着的话首列名会变成「﻿交易时间」，"
                        + "与别名表对不上，整份文件的表头都认不出来");
        assertFalse(text.startsWith("﻿"),
                "这是上一条的真正落点：首字符不是零宽不换行空格");
    }

    @Test
    @DisplayName("没有 BOM 的 UTF-8 直接解")
    void decode_utf8WithoutBom() {
        byte[] bytes = "交易时间,交易单号".getBytes(StandardCharsets.UTF_8);

        assertEquals("交易时间,交易单号", BillTextDecoder.decode(bytes));
    }

    @Test
    @DisplayName("GBK 文件回退到 GBK —— 支付宝导出的是这种")
    void decode_gbk() {
        byte[] bytes = "交易时间,交易订单号".getBytes(Charset.forName("GBK"));

        assertEquals("交易时间,交易订单号", BillTextDecoder.decode(bytes),
                "GBK 的中文字节不是合法 UTF-8，严格解码会失败，"
                        + "于是回退 GBK。回退不了的话整份文件是乱码");
    }

    @Test
    @DisplayName("纯 ASCII 走 UTF-8 分支，结果是同一份文本")
    void decode_ascii() {
        byte[] bytes = "a,b,c".getBytes(StandardCharsets.US_ASCII);

        assertEquals("a,b,c", BillTextDecoder.decode(bytes),
                "ASCII 在 UTF-8 与 GBK 下的解释完全一致，走哪一支都对");
    }

    // ==================================================================
    // 二进制文件：要把话说准
    // ==================================================================

    @Test
    @DisplayName("zip 包给出「请先解压」而不是「认不出表头」")
    void decode_rejectsZip() {
        BillParseException e = assertThrows(BillParseException.class,
                () -> BillTextDecoder.decode(concat(new byte[]{'P', 'K', 0x03, 0x04}, "abc")));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError(),
                "这是「编码 / 文件类型」问题，不是「表头」问题 —— 两种提示要管理员做的事完全不同");
        assertTrue(e.getMessage().contains("解压"),
                "文案要指向动作（先解压），而不是只说「格式不对」。实际文案：" + e.getMessage());
    }

    @Test
    @DisplayName("旧版 xls 给出「另存为 xlsx 或 CSV」")
    void decode_rejectsOle2() {
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0x00, 0x00};

        BillParseException e = assertThrows(BillParseException.class,
                () -> BillTextDecoder.decode(ole2));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError());
        assertTrue(e.getMessage().contains("xlsx"),
                "要告诉管理员该另存成什么格式。实际文案：" + e.getMessage());
    }

    @Test
    @DisplayName("UTF-16 文件给出「另存为 CSV UTF-8」")
    void decode_rejectsUtf16() {
        BillParseException e = assertThrows(BillParseException.class,
                () -> BillTextDecoder.decode(concat(BOM_UTF16_LE, "a")));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError());
        assertTrue(e.getMessage().contains("UTF-16"), "要点明是哪种编码。实际文案：" + e.getMessage());
    }

    @Test
    @DisplayName("图片给出「这不是账单数据文件」")
    void decode_rejectsImage() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};

        BillParseException e = assertThrows(BillParseException.class,
                () -> BillTextDecoder.decode(png));

        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING, e.getError(),
                "图片走「文件类型」而不是「认不出表头」—— 后者会让管理员去反复检查表头");
    }

    @Test
    @DisplayName("空文件与 null 都报「文件是空的」")
    void decode_rejectsEmpty() {
        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING,
                assertThrows(BillParseException.class, () -> BillTextDecoder.decode(null)).getError(),
                "null 输入要落到「文件是空的」这个码上，而不是 NPE 冒到 Service 外面");
        assertEquals(ErrorCode.RECONCILE_BILL_ENCODING,
                assertThrows(BillParseException.class,
                        () -> BillTextDecoder.decode(new byte[0])).getError(),
                "零字节同理");
    }

    @Test
    @DisplayName("很短但正常的文本照常解出来，不该误判成二进制")
    void decode_shortButValidText() {
        assertEquals("a", BillTextDecoder.decode(new byte[]{'a'}),
                "一个字节的正常文本不能被二进制检测误伤 —— 那几个文件头都是 2~4 字节起判的");
    }

    /**
     * 拼字节。
     *
     * <p>写成辅助方法而不是在用例里数组拷贝：BOM 这几个字节在断言里出现得太频繁，
     * 每处都抄一遍 {@code System.arraycopy} 只会把用例的重点淹掉。
     *
     * @param prefix 前缀字节（BOM 之类）
     * @param text   其后的 UTF-8 文本
     * @return 拼好的字节
     */
    private static byte[] concat(byte[] prefix, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }
}
