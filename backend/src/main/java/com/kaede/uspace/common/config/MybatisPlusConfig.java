package com.kaede.uspace.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>做两件事：扫描 Mapper 接口、注册分页插件。
 *
 * <p><b>Mapper 接口的扫描路径</b>用通配写法 {@code **.mapper}，
 * 这样各业务包只需按约定把 Mapper 放进自己的 {@code mapper} 子包，
 * 新增模块时不必回来改这个配置 —— 少一处「加新模块要记得改的地方」，
 * 就少一次「忘了改、启动后报找不到 Bean」的排查。
 */
@Configuration
@MapperScan("com.kaede.uspace.**.mapper")
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器。
     *
     * <p><b>分页插件必须显式注册</b>，MyBatis-Plus 不会自动装配它 ——
     * 不注册的话分页查询不会报错，只是<b>静默地返回全部数据</b>，
     * 这种「不报错的错」最难发现，务必留意。
     *
     * <p>限制单页最大条数：防止前端传个 {@code size=100000} 把整表捞出来，
     * 拖垮数据库也拖垮内存。
     *
     * @return 装配好的拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限。超出时插件会自动按上限截断，而不是拒绝请求
        pagination.setMaxLimit(100L);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
