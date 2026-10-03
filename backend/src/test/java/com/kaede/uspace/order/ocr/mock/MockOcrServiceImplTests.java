package com.kaede.uspace.order.ocr.mock;

import com.kaede.uspace.order.ocr.OcrFields;
import com.kaede.uspace.order.ocr.OcrResult;
import com.kaede.uspace.order.ocr.OcrTextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MockOcrServiceImpl} 的单元测试。
 *
 * <p>被测的是一个「什么都不做」的实现，所以用例也少 ——
 * 但它钉住的两条都是要紧的：
 *
 * <ol>
 *   <li><b>它不能返回假的识别结果。</b>造一条「金额 8.00、单号 4200…」出来，
 *       管理员在后台看到的就是一个不存在的事实。这个类存在的意义就是不说假话</li>
 *   <li><b>它返回的是成功而不是失败。</b>没配识别引擎不是「出错了」，
 *       调用方对两者的处置相同，但日志里要分得清 —— 混起来的话，
 *       真出故障时满屏都是同一种记录，看不出是哪一种</li>
 * </ol>
 *
 * @see com.kaede.uspace.order.ocr.mock.MockOcrServiceImpl
 */
class MockOcrServiceImplTests {

    private final MockOcrServiceImpl service = new MockOcrServiceImpl();

    @Test
    @DisplayName("识别 → 恒返回「未识别出」，一个字都不编")
    void recognize_returnsNothing() {
        OcrResult result = service.recognize(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        assertTrue(result.isSuccess(), "「没识别能力」不是错误，返回失败会让日志把常态当故障");
        assertTrue(result.getLines().isEmpty(),
                "模拟实现不能编造识别结果 —— 编出来的金额与单号会被管理员当成事实");
    }

    @Test
    @DisplayName("识别 → 无论传什么图都是同一个结果")
    void recognize_ignoresImageContent() {
        // 就算传进来一段「像是付款页」的文本，也不该被当成识别结果
        OcrResult result = service.recognize(
                "交易单号 4200001234202609301234567890".getBytes(StandardCharsets.UTF_8));

        assertTrue(result.getLines().isEmpty(), "本实现根本不看图片内容");
    }

    @Test
    @DisplayName("接入解析器后 → 全链路得到 EMPTY，与「识别不出」是同一条路")
    void pipeline_yieldsEmptyFields() {
        OcrFields fields = OcrTextParser.parse(service.recognize(new byte[]{1, 2, 3}).getLines());

        assertEquals(OcrFields.EMPTY, fields,
                "mock 档下每次上传都会走到这里。它必须是「三个字段全空」这条正常路径，"
                        + "而不是某种需要特殊处理的状态 —— 前端照常让用户手填单号即可");
    }
}
