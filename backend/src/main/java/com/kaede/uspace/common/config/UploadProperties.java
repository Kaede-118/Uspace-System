package com.kaede.uspace.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片上传配置，对应配置文件中的 {@code uspace.upload.*}。
 *
 * <p><b>它为什么住在公共层而不是 {@code user} 包</b>：这份配置有两个消费方 ——
 * {@link WebMvcConfig}（把上传目录映射成静态资源路径）与
 * {@code user} 包的 {@code ImageUploadService}（把文件写进去）。
 * 若把它放进 {@code user}，公共层就要反过来依赖业务包，
 * 「{@code common ← 业务包}」的单向依赖就破了。
 *
 * @see com.kaede.uspace.common.config.WebMvcConfig 静态资源映射
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.upload")
public class UploadProperties {

    /**
     * 上传文件的落盘根目录。
     *
     * <p>默认写成相对路径 {@code uploads}，解析时相对于 JVM 的工作目录
     * （开发期即 {@code backend/}）。也可以配成绝对路径。
     *
     * <p>⚠️ <b>生产环境必须指向应用目录之外的绝对路径</b>：留在应用目录里的话，
     * 重新部署（覆盖整个目录、或从压缩包解压）会把用户已经上传的图一并清掉，
     * 而库里那些 {@code avatar} / {@code banner} 路径还指着它们 ——
     * 表现是所有用户的头像与背景图同时变成破图，且没有任何报错。
     */
    private String dir = "uploads";

    /**
     * 对外暴露的 URL 前缀，同时也是存进库的那段路径的前缀。
     *
     * <p>库里的 {@code avatar} / {@code banner} 存的是
     * {@code /uploads/avatar/xiaofeng_12.png} 这样的<b>站内相对路径</b>，
     * 前端 {@code <img :src="avatar">} 直接加载即可。
     *
     * <p><b>刻意不存完整 URL</b>：完整 URL 会把域名写进库，
     * 换域名时要 {@code UPDATE sys_user SET avatar = REPLACE(avatar, ...)} 全表刷一遍。
     *
     * <p>改这个值时注意 {@code SecurityConfig.PUBLIC_PATHS} 里那条放行规则也要跟着改 ——
     * 图是 {@code <img src>} 加载的，浏览器不会为图片请求带 {@code Authorization} 头。
     */
    private String urlPrefix = "/uploads";

    /**
     * 单张图片的字节上限。默认 2MB（2097152 字节）。
     *
     * <p>它与 {@code spring.servlet.multipart.max-file-size} 是<b>两道独立的闸门</b>，
     * 都要配：
     * <ul>
     *   <li>multipart 那道由 Spring 在解析请求时就拦下，超限抛
     *       {@code MaxUploadSizeExceededException}，请求体不会被整个读进内存</li>
     *   <li>本值由 Service 再校验一次 —— 万一 multipart 的上限被调大
     *       （比如为了别的接口），这道还在</li>
     * </ul>
     */
    private long maxImageBytes = 2097152L;
}
