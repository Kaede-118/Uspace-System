package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.upload.ImageStorage;
import com.kaede.uspace.common.upload.StoredImage;
import com.kaede.uspace.order.dto.PayQrImageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 收款码图片的上传（模块 8 的支付能力）。
 *
 * <p><b>只把文件存下来、返回站内路径，不碰数据库</b> —— 与商品封面
 *（{@code ProductCoverService}）是同一套语义，三条理由也一样：
 * 新增时还没有记录 ID 可写、表单是「填一半可以取消」的、
 * 传错可以反复换一张而不改动任何数据。
 *
 * <p>⚠️ <b>本类构造器只有一个依赖、没有 Mapper</b> —— 这不是巧合，
 * 它就是「上传不写库」这条语义的结构性证明。
 *
 * <p><b>文件名用 UUID，不采信显示名</b>：显示名是中文自由文本（含空格、
 * 斜杠、emoji），清洗成安全文件名的那套规则没有测试能穷尽；
 * 而 {@code {名称}_{ID}} 这种写法在新增时根本算不出来（还没有 ID）。
 * 顺带买到：不会重名（不存在「重传覆盖同一路径」，也就没有删旧图那套判断），
 * 以及 URL 每次都变，前端不需要防缓存。
 *
 * <p><b>关于格式</b>：{@link ImageStorage#store} 按文件头只认 JPEG / PNG / WebP。
 * 收款码常见透明底 PNG，本条链路不做任何转码，原样落盘 ——
 * <b>转码发生在浏览器端</b>（{@code utils/image.js} 的 {@code payqr} 处理器
 * 专门输出 PNG），因为把透明底转成 JPEG 会让背景变黑、码就扫不出来了。
 *
 * @see com.kaede.uspace.product.ProductCoverService 同一套语义的商品封面版本
 */
@Slf4j
@Service
public class PayQrImageService {

    /**
     * 收款码图片的子目录名，同时也是落库路径里的一段。
     *
     * <p>包级可见：{@code PayQrService} 校验 {@code imageUrl} 前缀时要用同一个值。
     * 两处各写一份字符串的话，改了一处而另一处没改，结果是<b>上传成功但保存失败</b>，
     * 报的还是「图片路径不合法」这种让人摸不着头脑的话。
     *
     * <p>改它会同时让历史收款码路径失效 —— 与 {@code ProductCoverService}
     * 上那条警告同理。
     */
    static final String KIND_PAY_QR = "payqr";

    private final ImageStorage imageStorage;

    /**
     * 构造器注入。
     *
     * @param imageStorage 公共层的图片落盘组件（与头像 / 背景图 / 商品封面共用同一份实现）
     */
    public PayQrImageService(ImageStorage imageStorage) {
        this.imageStorage = imageStorage;
    }

    /**
     * 上传一张收款码图片。
     *
     * @param file 上传的图片，可为 null（表示请求里没带 file 部分）
     * @return 成功时返回站内路径；校验不过时返回对应错误码
     */
    public BizResult<PayQrImageVo> upload(MultipartFile file) {
        String baseName = UUID.randomUUID().toString().replace("-", "");

        BizResult<StoredImage> stored = imageStorage.store(KIND_PAY_QR, baseName, file);
        if (!stored.isSuccess()) {
            // 连附加文案一起透传（「请选择要上传的图片」那句提示要靠它才留得住）
            return BizResult.fail(stored.getError(), stored.getMessage());
        }

        String url = stored.getData().url();
        log.info("[收款码] 图片已上传 url={}", url);
        return BizResult.ok(PayQrImageVo.of(url));
    }
}
