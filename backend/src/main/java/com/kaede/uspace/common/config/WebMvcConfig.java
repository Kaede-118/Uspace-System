package com.kaede.uspace.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Spring MVC 配置：把上传目录映射成静态资源路径。
 *
 * <p>本项目的第一个 {@code WebMvcConfigurer}。加它是因为用户上传的头像与背景图
 * 需要能通过 URL 直接访问 —— {@code <img src="/uploads/avatar/...">} 这类请求
 * 不经过任何 Controller，得由静态资源处理器接住。
 *
 * <p><b>光有这里的映射还不够，必须同时改三处，漏一处的症状见下面的表</b>：
 *
 * <table border="1">
 *   <caption>头像 / Banner 能正常显示所需的三处配置</caption>
 *   <tr><th>位置</th><th>作用</th><th>漏掉的症状</th></tr>
 *   <tr>
 *     <td>{@code WebMvcConfig}（本类）</td>
 *     <td>把目录映射成 URL</td>
 *     <td>图片 404，后端日志里一行都没有</td>
 *   </tr>
 *   <tr>
 *     <td>{@code SecurityConfig.PUBLIC_PATHS}</td>
 *     <td>放行 {@code /uploads/**}</td>
 *     <td><b>页面上头像与背景图全裂</b>，而后端日志里什么都看不到 ——
 *         图是 {@code <img src>} 加载的，浏览器不会为图片请求带
 *         {@code Authorization} 头，于是被过滤器链拦成 401</td>
 *   </tr>
 *   <tr>
 *     <td>{@code backend/.gitignore}</td>
 *     <td>忽略 {@code uploads/}</td>
 *     <td>用户上传的图被提交进仓库，仓库体积只增不减</td>
 *   </tr>
 * </table>
 *
 * @see UploadProperties 上传目录与前缀的配置来源
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;

    public WebMvcConfig(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    /**
     * 注册上传目录的静态资源映射。
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ⚠️ 用 Paths.get(...).toUri() 而不是字符串拼 "file:" + dir ——
        // Windows 上 C:\a\b 拼出来是 file:C:\a\b，那个冒号会被当成协议分隔符，
        // 得到的是一条谁都不报错的死映射：图片全 404，日志里一行都没有。
        // 本项目就在 Windows 上开发，这不是理论问题。
        String location = Paths.get(uploadProperties.getDir())
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();

        // ⚠️ 结尾必须有斜杠。Spring 解析资源路径时是「在 location 后面接上相对路径」，
        // 少了这个斜杠就会变成「替换掉最后一段」，于是 /uploads/a.jpg 会被解析成
        // 与 uploads 同级的 a.jpg，映射失效。
        //
        // 之所以不能指望 toUri() 自己带上：它只在【该目录已存在】时才补这个斜杠。
        // 全新部署时上传目录还没建出来（要等第一次上传），
        // 于是映射在「启动后到第一次上传之间」是坏的，第一次上传后不作重启也不会自愈。
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        registry.addResourceHandler(uploadProperties.getUrlPrefix() + "/**")
                .addResourceLocations(location);
    }
}
