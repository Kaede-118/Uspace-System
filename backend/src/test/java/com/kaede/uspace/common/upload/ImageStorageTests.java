package com.kaede.uspace.common.upload;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageStorage} 的单元测试（公共层，不连库）。
 *
 * <p><b>与 {@code ImageUploadServiceTests} 的分工</b>：那边测的是
 * 「写到哪个用户的哪一列」（用户不存在怎么办、换格式要不要删旧图），
 * 这边测的是<b>文件这一层本身</b> —— 落盘落哪儿、叫什么名字、什么不该被写下来、
 * 什么不该被删掉。两者共享同一段实现，但断言的角度不同：那边是回归护栏
 * （抽取前后行为必须一致），这边是新组件的对外契约。
 *
 * <p>这里面的用例大多是安全性质的：路径穿越、扩展名跟着原始文件名走、
 * 被拒的上传却留下了文件 —— 这些失败方式<b>都不会报错</b>，
 * 只在出事时才暴露。
 *
 * <p>落盘目录用 JUnit 的 {@code @TempDir}，跑完自动清理，
 * 不碰开发库也不碰仓库里的 {@code backend/uploads/}。
 */
class ImageStorageTests {

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

    /** 上传根目录，每个用例一个临时目录 */
    @TempDir
    Path tempDir;

    /** 被测组件的配置 */
    private UploadProperties props;

    /** 被测对象 */
    private ImageStorage storage;

    /**
     * 组装被测对象：临时上传目录 + 默认的 URL 前缀与大小上限。
     */
    @BeforeEach
    void setUp() {
        props = new UploadProperties();
        props.setDir(tempDir.resolve("uploads").toString());
        props.setUrlPrefix("/uploads");
        props.setMaxImageBytes(2 * 1024 * 1024);

        storage = new ImageStorage(props);
    }

    // ==================================================================
    // 落盘
    // ==================================================================

    @Test
    @DisplayName("落盘：url 与 path 指向同一个文件，内容就是上传的那些字节")
    void store_urlAndPathPointToTheSameFile() throws IOException {
        BizResult<StoredImage> result =
                storage.store("product", "9f3c", file("whatever.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess(), "正常图片应当上传成功");
        StoredImage image = result.getData();
        assertEquals("/uploads/product/9f3c.jpg", image.url());

        // ⚠️ 这条是整个类的核心不变量：拿 url 反推出来的路径，必须就是 path()。
        // 一旦两者算不到一块去，「删旧图删错文件」只是时间问题
        assertEquals(fileOf(image.url()), image.path(), "url 反推的路径必须等于 path()");
        assertArrayEquals(JPEG_BYTES, Files.readAllBytes(image.path()), "落盘内容必须与上传的一致");
    }

    @Test
    @DisplayName("落盘：扩展名由文件头推出，原始文件名一个字符都不采信")
    void store_extensionComesFromMagicBytes() {
        // 内容是 PNG，原始文件名却叫 evil.jsp —— 扩展名必须跟着内容走
        BizResult<StoredImage> result =
                storage.store("product", "abc", file("evil.jsp", "image/jpeg", PNG_BYTES));

        assertEquals("/uploads/product/abc.png", result.getData().url());

        // 上传目录下只该有那一个文件，且它叫 .png
        assertEquals(1, countFilesUnder(props.getDir()), "只该落下一个文件");
        assertTrue(Files.exists(fileOf(result.getData().url())));
    }

    @Test
    @DisplayName("落盘：子目录不存在时自动创建")
    void store_createsSubdirectory() {
        // 目标子目录在临时目录里并不存在（连 uploads 本身都没有）
        assertFalse(Files.exists(Paths.get(props.getDir())));

        BizResult<StoredImage> result =
                storage.store("deep_sub", "x", file("a.jpg", "image/jpeg", JPEG_BYTES));

        assertTrue(result.isSuccess());
        assertTrue(Files.exists(result.getData().path()));
    }

    // ==================================================================
    // 校验
    // ==================================================================

    @Test
    @DisplayName("校验：不是 JPG/PNG/WebP → 40001，且不留下任何文件")
    void store_rejectsNonImageWithoutLeavingFiles() {
        BizResult<StoredImage> result =
                storage.store("avatar", "u1", file("shell.jsp", "image/png", JSP_BYTES));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, result.getError());
        // 「校验失败却已经把文件写下去了」是这类实现最容易犯的错，且完全静默
        assertNoFilesUnder(props.getDir(), "被拒的上传不该留下任何文件");
    }

    @Test
    @DisplayName("校验：超过大小上限 → 41300，且不留下任何文件")
    void store_rejectsOversizedWithoutLeavingFiles() {
        props.setMaxImageBytes(10);

        BizResult<StoredImage> result =
                storage.store("avatar", "u1", file("big.jpg", "image/jpeg", JPEG_BYTES));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.UPLOAD_FILE_TOO_LARGE, result.getError());
        assertNoFilesUnder(props.getDir(), "被拒的上传不该留下任何文件");
    }

    @Test
    @DisplayName("校验：没带文件 → 40001，并附上「请选择要上传的图片」")
    void store_nullFileCarriesTheHintMessage() {
        // ⚠️ 那个强转不能省：加了 store(byte[]) 重载之后，裸的 null 两个都匹配得上，
        // 编译期直接报「对 store 的引用不明确」。这里要测的是网页上传那条路
        BizResult<StoredImage> result = storage.store("avatar", "u1", (MultipartFile) null);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, result.getError());
        // 文案存在 BizResult.message 里，resolveMessage 才取得到 —— 少了它前端只会看到
        // 一句笼统的「只支持 JPG、PNG、WebP 格式的图片」
        assertEquals("请选择要上传的图片", result.resolveMessage());
    }

    @Test
    @DisplayName("校验：路径片段里带分隔符或 .. → 直接抛参数异常")
    void store_rejectsPathSegmentThatChangesTarget() {
        // kind 里带 / 能把文件写到另一个子目录去，而那个落点【仍在上传根目录内】——
        // normalize + startsWith 那道防线看不见它，只能在拼路径之前挡
        assertThrows(IllegalArgumentException.class, () ->
                storage.store("avatar/../banner", "u1", file("a.jpg", "image/jpeg", JPEG_BYTES)));
        assertThrows(IllegalArgumentException.class, () ->
                storage.store("avatar", "../../evil", file("a.jpg", "image/jpeg", JPEG_BYTES)));
        assertThrows(IllegalArgumentException.class, () ->
                storage.store("", "u1", file("a.jpg", "image/jpeg", JPEG_BYTES)));

        assertNoFilesUnder(props.getDir(), "被拒的上传不该留下任何文件");
    }

    // ==================================================================
    // 删除
    // ==================================================================

    @Test
    @DisplayName("删除：正常路径删得掉")
    void deleteByUrl_removesTheFile() {
        StoredImage image =
                storage.store("avatar", "u1", file("a.jpg", "image/jpeg", JPEG_BYTES)).getData();
        assertTrue(Files.exists(image.path()));

        storage.deleteByUrl(image.url());

        assertFalse(Files.exists(image.path()), "旧图应当被删掉");
    }

    @Test
    @DisplayName("删除：路径越出上传目录时拒绝，目标文件安然无恙")
    void deleteByUrl_refusesToEscapeUploadDir() throws IOException {
        // 在上传目录【之外】造一个文件，再用 ../ 指过去
        Path outside = tempDir.resolve("outside.jpg");
        Files.write(outside, JPEG_BYTES);

        storage.deleteByUrl("/uploads/../outside.jpg");

        // 只比字符串开头的话，"/uploads/../outside.jpg" 是能通过检查的 ——
        // 必须 normalize() 之后再比前缀，这一条就是那道防线
        assertTrue(Files.exists(outside), "上传目录之外的文件绝不该被删除");
    }

    @Test
    @DisplayName("删除：null / 空 / 外站 URL 一律不抛异常、不误删")
    void deleteByUrl_ignoresBlankAndForeignUrls() {
        StoredImage image =
                storage.store("avatar", "u1", file("a.jpg", "image/jpeg", JPEG_BYTES)).getData();

        assertDoesNotThrow(() -> storage.deleteByUrl(null));
        assertDoesNotThrow(() -> storage.deleteByUrl(""));
        assertDoesNotThrow(() -> storage.deleteByUrl("   "));
        // 前缀对不上的一律不碰：这些要么是人工改过的值，要么根本不是我方路径
        assertDoesNotThrow(() -> storage.deleteByUrl("http://evil.example.com/uploads/avatar/u1.jpg"));
        assertDoesNotThrow(() -> storage.deleteByUrl("/other/avatar/u1.jpg"));
        assertDoesNotThrow(() -> storage.deleteByUrl("uploads/avatar/u1.jpg"));

        assertTrue(Files.exists(image.path()), "文件不该被这些 URL 删掉");
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
     * @param url 站内相对路径，如 {@code /uploads/avatar/u1.jpg}
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
