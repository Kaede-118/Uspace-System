package com.kaede.uspace.product;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.upload.ImageStorage;
import com.kaede.uspace.product.dto.ProductCoverVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProductCoverService} 的单元测试（不启 Spring、不连数据库）。
 *
 * <p>⚠️ <b>本类组装被测对象只需要一个 {@code ImageStorage}，没有任何 Mapper</b> ——
 * 这不是巧合，它就是「上传只落盘、不写库」这条语义的结构性证明。
 * 哪天有人把写库逻辑加进 {@code uploadCover}，这个类的构造就会先炸。
 *
 * <p>落盘目录用 JUnit 的 {@code @TempDir}，跑完自动清理，
 * 不碰开发库也不碰仓库里的 {@code backend/uploads/}。
 */
class ProductCoverServiceTests {

    /** 一个合法的 JPEG 文件头（含 JFIF 标识），长度满足最短判定要求 */
    private static final byte[] JPEG_BYTES = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x02, 0x03};

    /** 一个合法的 PNG 文件头 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    /** 一段 JSP 源码 —— 冒充图片的典型攻击载荷 */
    private static final byte[] JSP_BYTES =
            "<%@ page import=\"java.util.*\" %><% out.print(1); %>".getBytes(StandardCharsets.UTF_8);

    /** 封面的 URL 前缀，与 {@code ProductCoverService.KIND_COVER} 对应 */
    private static final String COVER_PREFIX = "/uploads/product/";

    /** 上传根目录，每个用例一个临时目录 */
    @TempDir
    Path tempDir;

    /** 被测组件的配置 */
    private UploadProperties props;

    /** 被测对象 */
    private ProductCoverService service;

    /**
     * 组装被测对象：临时上传目录 + 公共层的落盘组件。
     */
    @BeforeEach
    void setUp() {
        props = new UploadProperties();
        props.setDir(tempDir.resolve("uploads").toString());
        props.setUrlPrefix("/uploads");
        props.setMaxImageBytes(2 * 1024 * 1024);

        service = new ProductCoverService(new ImageStorage(props));
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("上传：落在 product 子目录下，文件真的写进磁盘")
    void uploadCover_landsUnderProductDir() {
        BizResult<ProductCoverVo> result =
                service.uploadCover(file("cover.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess());
        String cover = result.getData().getCover();
        assertTrue(cover.startsWith(COVER_PREFIX), "路径应当在 product 子目录下：" + cover);
        assertTrue(cover.endsWith(".jpg"), "扩展名应当来自文件头：" + cover);
        assertTrue(Files.exists(fileOf(cover)), "文件应当真的落盘：" + cover);
    }

    @Test
    @DisplayName("上传：文件名里没有任何用户可控的片段")
    void uploadCover_fileNameHasNoUserControlledPart() {
        // 原始文件名本身就是攻击载荷 —— 服务端一个字符都不该采信
        BizResult<ProductCoverVo> result =
                service.uploadCover(file("../../../../evil.jsp", "image/jpeg", JPEG_BYTES));

        String name = result.getData().getCover().substring(COVER_PREFIX.length());
        // 32 位小写 hex（去掉横杠的 UUID）+ 由文件头推出的扩展名
        assertTrue(Pattern.matches("^[0-9a-f]{32}\\.jpg$", name), "文件名不合规：" + name);
        assertEquals(1, countFilesUnder(props.getDir()), "只该落下一个文件");
    }

    @Test
    @DisplayName("上传：两次上传给出两个不同的路径，先传的那张仍在")
    void uploadCover_twiceGivesTwoDistinctPaths() {
        String first = service.uploadCover(file("a.jpg", "image/jpeg", JPEG_BYTES)).getData().getCover();
        String second = service.uploadCover(file("b.png", "image/png", PNG_BYTES)).getData().getCover();

        assertNotEquals(first, second, "两次上传不该落到同一个路径");

        // ⚠️ 这一条不是「顺便验一下」。商品封面【不删旧图】，文件会一直累积，
        // 这是「只返回路径」语义的已知取舍（见 ProductCoverService 的类注释）。
        // 哪天有人把它当 bug、改成固定文件名（像头像那样），这条用例会红 ——
        // 那是刻意的：固定名意味着「传了图又取消」会把正在用的那张覆盖掉，
        // 而数据库里还指着老路径，等于封面被静默改掉了
        assertTrue(Files.exists(fileOf(first)), "先传的那张不该被删掉");
        assertTrue(Files.exists(fileOf(second)));
    }

    // ==================================================================
    // 校验失败
    // ==================================================================

    @Test
    @DisplayName("上传：没带文件 / 空文件 / 假图 → 40001，且不留下任何文件")
    void uploadCover_rejectsInvalidFiles() {
        BizResult<ProductCoverVo> missing = service.uploadCover(null);
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, missing.getError());
        // 文案在 BizResult.message 里 —— 少了它前端只会看到笼统的格式提示
        assertEquals("请选择要上传的图片", missing.resolveMessage());

        BizResult<ProductCoverVo> empty =
                service.uploadCover(file("a.jpg", "image/jpeg", new byte[0]));
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, empty.getError());

        BizResult<ProductCoverVo> fake =
                service.uploadCover(file("shell.jsp", "image/png", JSP_BYTES));
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, fake.getError());

        assertNoFilesUnder(props.getDir(), "被拒的上传不该留下任何文件");
    }

    @Test
    @DisplayName("上传：超过大小上限 → 41300，且不留下任何文件")
    void uploadCover_rejectsOversized() {
        props.setMaxImageBytes(10);

        BizResult<ProductCoverVo> result =
                service.uploadCover(file("big.jpg", "image/jpeg", JPEG_BYTES));

        assertEquals(ErrorCode.UPLOAD_FILE_TOO_LARGE, result.getError());
        assertNoFilesUnder(props.getDir(), "被拒的上传不该留下任何文件");
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 造一个上传文件。
     *
     * @param originalName 原始文件名，服务端应当完全忽略它
     * @param contentType  客户端声称的类型，同样不参与判定
     * @param bytes        文件内容
     * @return MultipartFile
     */
    private static MultipartFile file(String originalName, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", originalName, contentType, bytes);
    }

    /**
     * 把站内相对路径还原成磁盘上的文件路径。
     *
     * @param url 站内相对路径，如 {@code /uploads/product/9f3c….jpg}
     * @return 绝对路径
     */
    private Path fileOf(String url) {
        return Paths.get(props.getDir()).toAbsolutePath().normalize()
                .resolve(url.substring(props.getUrlPrefix().length() + 1));
    }

    /**
     * 数上传目录下的文件个数。
     *
     * @param dir 上传目录
     * @return 常规文件的数量；目录不存在时返回 0
     */
    private static long countFilesUnder(String dir) {
        Path root = Paths.get(dir);
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new AssertionError("遍历上传目录失败", e);
        }
    }

    /**
     * 断言上传目录下没有任何文件。
     *
     * @param dir 上传目录
     * @param msg 失败提示
     */
    private static void assertNoFilesUnder(String dir, String msg) {
        assertEquals(0, countFilesUnder(dir), msg);
    }
}
