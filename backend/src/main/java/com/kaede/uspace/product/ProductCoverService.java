package com.kaede.uspace.product;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.upload.ImageStorage;
import com.kaede.uspace.common.upload.StoredImage;
import com.kaede.uspace.product.dto.ProductCoverVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 商品封面的上传（商品包）。
 *
 * <p><b>只把文件存下来、返回站内路径，不碰数据库</b> —— 前端拿到路径后填进表单，
 * 随「新增 / 修改商品」一起提交时才写进 {@code biz_product.cover}。三条理由：
 * <ol>
 *   <li><b>新增商品时还没有 ID</b>：落库式接口（如换头像）要求对象已存在，
 *       而「先传封面、再保存商品」恰恰是管理员的操作顺序</li>
 *   <li><b>表单是「填一半可以取消」的语义</b>：上传即改库的话，
 *       点取消之后封面已经变了，回不去</li>
 *   <li><b>传错可以反复换</b>：传一张看看、不满意再传一张，中途不改动任何数据</li>
 * </ol>
 *
 * <p>⚠️ <b>本类构造器只有一个依赖、没有 Mapper</b> —— 这不是巧合，
 * 它就是「上传不写库」这条语义的结构性证明。
 *
 * <p>⚠️ <b>代价：孤儿文件</b>。每次上传都产生一个新文件，而「传了图又取消」
 * 「反复重传」「删掉商品」都会让一些文件不再被任何记录引用。
 * 它们只占磁盘、不影响功能，是上面那条语义的<b>固有结果而非 bug</b>。
 * 将来要清理的话，办法是拿目录列表与 {@code SELECT cover FROM biz_product}
 * 做差集 —— 本期不做，只在注释里记着。
 */
@Slf4j
@Service
public class ProductCoverService {

    /** 封面的子目录名。同时也是落库路径里的一段，改它会让历史封面路径失效 */
    private static final String KIND_COVER = "product";

    private final ImageStorage imageStorage;

    /**
     * 构造器注入。
     *
     * @param imageStorage 公共层的图片落盘组件（与头像 / 背景图共用同一份实现）
     */
    public ProductCoverService(ImageStorage imageStorage) {
        this.imageStorage = imageStorage;
    }

    /**
     * 上传一张商品封面。
     *
     * <p><b>文件名用 UUID，不采信商品名</b>：商品名是中文自由文本
     * （含空格、斜杠、引号，Windows 下还有 {@code CON} / {@code PRN} 这类保留名），
     * 想把它们清洗成安全的文件名，那套规则没有任何测试能穷尽。
     * 头像能用 {@code {用户名}_{ID}}，是因为注册校验把用户名限死在
     * {@code [a-zA-Z0-9_]{3,20}} —— 商品名没有这个前提。
     * 何况新增商品时还没有 ID，{@code {名称}_{ID}} 根本算不出来。
     *
     * <p>顺带买到两件事：文件名天然不重复（所以不存在「重传覆盖同一路径」，
     * 也就没有删旧图那套判断），以及 <b>URL 每次都变 —— 前端不需要防缓存</b>。
     *
     * @param file 上传的图片，可为 null（表示请求里没带 file 部分）
     * @return 成功时返回站内路径；校验不过时返回对应错误码
     */
    public BizResult<ProductCoverVo> uploadCover(MultipartFile file) {
        String baseName = UUID.randomUUID().toString().replace("-", "");

        BizResult<StoredImage> stored = imageStorage.store(KIND_COVER, baseName, file);
        if (!stored.isSuccess()) {
            // 连附加文案一起透传（「请选择要上传的图片」那句提示要靠它才留得住）
            return BizResult.fail(stored.getError(), stored.getMessage());
        }

        String url = stored.getData().url();
        log.info("[商品] 封面已上传 url={}", url);
        return BizResult.ok(ProductCoverVo.of(url));
    }
}
