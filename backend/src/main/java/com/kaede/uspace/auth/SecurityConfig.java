package com.kaede.uspace.auth;

import com.kaede.uspace.user.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置（模块 2）。
 *
 * <p><b>用 {@code SecurityFilterChain} Bean 而不是 {@code WebSecurityConfigurerAdapter}</b>：
 * 后者在 Spring Boot 3 / Spring Security 6 中已被移除，
 * 网上大量基于旧版本的示例代码在这个版本下根本编译不过。
 *
 * <p>几个必须显式关掉的东西，理由各不相同：
 * <ul>
 *   <li><b>CSRF</b> —— 它的防御前提是「浏览器会自动带上 Cookie」，
 *       而本系统用 {@code Authorization} 头携带凭证，浏览器不会自动附带，
 *       攻击者伪造的请求天然带不上凭证，CSRF 无从谈起。
 *       留着它反而会拦掉所有 POST 请求，让人一头雾水</li>
 *   <li><b>formLogin / httpBasic</b> —— 这两个默认开启的入口会让未认证请求
 *       收到一个登录页 HTML 或浏览器弹窗，而不是我们要的 JSON。
 *       关掉后统一走 {@code RestAuthenticationEntryPoint}</li>
 *   <li><b>logout</b> —— 它默认的行为是清服务端 session，而本方案根本无 session。
 *       登出的语义见 {@code AuthController}</li>
 *   <li><b>session</b> —— 设为无状态，服务端不存任何会话数据。
 *       这是 JWT 方案能「多端一致」的前提</li>
 * </ul>
 *
 * <p><b>CORS 必须配在这里而不是 {@code WebMvcConfigurer}</b>：跨域预检请求
 * （OPTIONS）不带凭证，若 CORS 由 MVC 层处理，请求会先被 Security 过滤器链
 * 以「未认证」拦下返回 401，浏览器只报一个含义模糊的跨域错误，
 * 让人往错误的方向排查很久。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 允许匿名访问的路径。
     *
     * <p>注册、登录、门店营业状态、公告、包场时间表、机台陈列与类型列表、
     * 用户上传的图片，以及两个支付回调。
     * 其余接口一律要求已认证 ——
     * 这是「默认拒绝」的写法，新增接口时忘了配权限的后果是「访问不了」，
     * 而不是「所有人都能访问」。后者的代价大得多。
     *
     * <p>{@code /api/store/status} 放行的理由：门店名称、地址与「此刻是否营业」
     * 是面向公众的信息，相当于店门口挂的牌子 —— 要求先注册才能知道店在哪、
     * 开没开门，是把新顾客挡在门外。响应体里不含停业原因与包场人信息，
     * 那道边界在 {@code StoreStatusVo} 上。
     *
     * <p>{@code /api/store/notices} 同理，且更需要放行：<b>店门口的告示牌
     * 不需要注册才能看</b>。而未注册的人恰恰更需要它 —— 看到「今晚 19:00–21:00
     * 已被包场」才知道要错峰；要求先注册再看，等于让第一次来的人白跑一趟。
     * 响应体里不含包场人是谁、也不含任何运营内务（那道边界在
     * {@code NoticeContents} 与 {@code NoticeVo} 上）。
     *
     * <p>{@code /api/devices} 与 {@code /api/devices/types} 同理：店里有什么机器、
     * 每台是不是在维护，是顾客走进店门就能亲眼看到的事，没有理由要求先注册。
     * 响应体里不含运营备注（那道边界在 {@code DeviceDisplayVo} 上），
     * 也不含任何「谁正在用」的信息 —— 本系统不做设备级使用记录。
     *
     * <p><b>两个支付回调放行的理由，以及它为什么不等于是个口子</b>：
     * 微信与支付宝的服务器发起回调时当然不带本系统的 JWT，不放行就收不到支付结果。
     * 但这里的鉴权并非被取消，而是<b>换了一种</b> —— 改由支付平台的
     * <b>签名验证</b>承担：回调报文里带着平台私钥签出的签名，验签不过一律拒绝处理。
     * 也就是说，匿名可达的是这个<b>端点</b>，而不是「谁都能伪造一笔支付成功」。
     *
     * <p><b>关于这里的匹配规则，有一个容易被人误解的地方</b>：
     * 写完 {@code /api/payments/notify/wxpay} 并不会顺带放行 {@code /api/payments} ——
     * 一个<b>字面路径只匹配它自己</b>，没有前缀语义。
     * 但这<b>不等于</b>「{@code String...} 这个重载不支持通配符」：
     * 它走的是 Ant 风格匹配，{@code *} 与 {@code **} 都能用（下面
     * {@code /uploads/**} 那条就依赖它）。两件事不矛盾，别因为前半句而不敢写通配符。
     *
     * <p>将来新增回调端点（如模块 9 月卡的通道）时，记得同步加到这里。
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/user/register",
            "/api/auth/login",
            "/api/store/status",
            "/api/store/notices",
            "/api/store/bookings",
            "/api/devices",
            "/api/devices/types",
            // 用户上传的头像与背景图。图是 <img src> 加载的，
            // 浏览器不会为图片请求带 Authorization 头 —— 不放行的话，
            // 页面上所有头像与背景图都会裂，而后端日志里一行都看不到。
            // 这一条必须与 WebMvcConfig 里注册的资源处理器成对出现：
            // 少了这边是 401，少了那边是 404，症状不同但都是「全裂」。
            "/uploads/**",
            "/api/payments/notify/wxpay",
            "/api/payments/notify/alipay",
    };

    /**
     * 安全过滤器链。
     *
     * <p>{@code JwtAuthenticationFilter} 在这里 new 出来而不做成 Bean ——
     * 见该类注释（避免被 Servlet 容器重复注册）。
     *
     * @param http                            HttpSecurity 构造器
     * @param jwtService                      JWT 服务
     * @param userService                     用户服务，供过滤器按 ID 查用户
     * @param authEntryPoint                  401 出口
     * @param accessDeniedHandler             403 出口
     * @param corsConfigurationSource         跨域配置
     * @return 装配好的过滤器链
     * @throws Exception 构建失败时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            UserService userService,
            RestAuthenticationEntryPoint authEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource) throws Exception {

        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userService);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 跨域预检不带凭证，放行；否则浏览器侧只会看到一个笼统的跨域失败
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 错误分发同样会经过过滤器链。不放行的话，
                        // 匿名请求出错时会被拦成 401，真正的错误信息反而看不到
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器。
     *
     * <p>用 BCrypt 而非 MD5 / SHA —— 后两者是快速哈希，算力足够时可以每秒
     * 尝试数十亿次，在离线爆破面前与明文无异。BCrypt 自带随机盐且可调计算成本，
     * 专门为存密码设计。
     *
     * <p>强度参数取 10：默认值，单次校验约几十毫秒。
     * 这个延迟在登录时用户感知不到，但让离线爆破的成本上升几个数量级。
     * 调到 12 以上会让登录接口在高并发下成为瓶颈，本项目没有必要。
     *
     * <p><b>这个 Bean 被模块 1 的 {@code UserService} 使用</b>（注册时加密、
     * 改密时校验），所以它属于「认证策略」而非仅属于本包 ——
     * 放在这里是为了让「密码怎么存」这件事只有一个决定的地方。
     *
     * @return BCrypt 编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 跨域配置。
     *
     * <p>开发期放开所有来源：前端脚手架尚未创建，端口未定，
     * 写死 localhost 反而会在换端口时白白排障一轮。
     *
     * <p><b>不开启 {@code allowCredentials}</b>：本系统用 {@code Authorization} 头
     * 携带凭证，不依赖 Cookie，因此不需要浏览器发送凭据。
     * 不开启也让「允许所有来源」不会与凭据规则冲突（浏览器禁止
     * 「来源为 * 且携带凭据」的组合）。
     *
     * <p>⚠️ 生产环境应当把来源收紧为实际的前端域名：
     * 允许任意来源意味着任何网站都能在用户浏览器里调用本系统的接口。
     *
     * @return 跨域配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        // 预检结果缓存一小时，避免每个请求都先发一次 OPTIONS
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
