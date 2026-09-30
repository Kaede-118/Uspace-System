package com.kaede.uspace.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 站点自身的 Web 配置，对应配置文件里的 {@code uspace.web.*}。
 *
 * <p><b>为什么住在公共层</b>：目前只有「分享链接的域名前缀」这一项，
 * 消费方是 {@code order} 包的邀请令牌服务。但站点地址是个全局概念 ——
 * 将来支付回跳地址、公告里的图片绝对路径都会用到它，
 * 放进某个业务包会让其余的包反过来依赖那一个。
 * 与 {@link UploadProperties} 是同一个理由。
 *
 * <p>与 {@code uspace.upload.url-prefix} 的分工：那个是<b>站内相对路径</b>的前缀
 * （存进库、由前端直接加载），本项是<b>对外完整地址</b>的前缀
 * （拼给用户看的、要能粘到微信里打开的那种）。两者不是一回事，
 * 改域名时只需要动这一项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.web")
public class WebProperties {

    /**
     * 站点对外访问地址，末尾不带斜杠。
     *
     * <p>只用于<b>拼分享链接</b>（包场邀请链接是当前唯一的消费方）。
     *
     * <p>默认值取前端开发服务器的地址，方便本地把链接粘到手机上试；
     * <b>生产环境必须改成真实域名</b>，否则发出去的邀请链接指向 localhost，
     * 别人点开只会看到自己的手机。
     *
     * <p><b>刻意不从请求的 {@code Origin} 头推域名</b>：那个头由客户端填写，
     * 伪造它就能让接口吐出一条指向任意域名的分享链接。前端另有一条更好的路径 ——
     * 用 {@code location.origin + path} 自己拼，那样拿到的是「当前这台设备
     * 访问得到的地址」，开发期比后端配的任何值都准。
     */
    private String baseUrl = "http://localhost:5173";
}
