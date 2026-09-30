package com.kaede.uspace.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.notice.entity.Notice;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 公告的数据访问接口。
 *
 * <p><b>本表只增不改</b>（管理员手写的那几条除外）：自动公告是「什么时刻发生了什么事」
 * 的记录，机台修好不会把那条改掉，而是再产生一条新的。所以这里
 * <b>没有「撤销」「失效」「按来源 upsert」</b>这类方法 —— 它们都属于
 * 「把公告当成状态投影」的思路，与消息流的定位冲突。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>：全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法，对注解里手写的 SQL 不生效 ——
 * 漏掉的后果是「已下架的手写公告还挂在首页上」，从界面上完全看不出来。
 */
public interface NoticeMapper extends BaseMapper<Notice> {

    /**
     * 取最新的若干条公告，即首页公告栏看到的内容。
     *
     * <p><b>没有任何时间条件</b>：公告没有「生效/失效」的概念，
     * 一条消息发出来就是可见的，不会到点自动消失。
     * 首页只展示最近几条，更早的留在表里，需要时翻后台。
     *
     * <p>排序只用 {@code id DESC}。主键自增且不可变，倒序就是严格的时间倒序；
     * 用 {@code created_at} 反而会因为同秒多条而出现不稳定的顺序
     * （两条同一秒写入的公告每次查询可能换个位置，页面看起来像在抖动）。
     *
     * <p>带 {@code LIMIT} 是因为首页公告栏只展示最近几条，
     * 全量拉回来再由前端截断是白费一次网络传输。
     *
     * @param limit 最多返回几条
     * @return 公告，按 id 倒序（最新在前）；没有则返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_notice
             WHERE deleted = 0
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<Notice> selectLatest(@Param("limit") int limit);

    /**
     * 后台分页查询公告。
     *
     * <p>可选条件用 {@code (#{x} IS NULL OR ...)} 的写法表达，而不是动态 SQL 的
     * {@code <if>} —— 与 {@code OrderMapper#selectPageForAdmin} 同一套路，
     * 好处是假 Mapper 只需实现一个确定的方法名，不必模拟动态 SQL 的拼接过程。
     *
     * <p>唯一的筛选项是发布方式，因为它是后台唯一需要区分的东西：
     * 想改文案时得先知道「哪些是能改的」，自动公告改了也会在下一次
     * 状态变化时被新消息淹没，找不到改它的意义。
     *
     * @param page        分页参数，由 MyBatis-Plus 的分页插件处理
     * @param publishMode 发布方式筛选，可空
     * @return 分页结果，按 id 倒序
     */
    @Select("""
            SELECT *
              FROM biz_notice
             WHERE deleted = 0
               AND (#{publishMode} IS NULL OR #{publishMode} = '' OR publish_mode = #{publishMode})
             ORDER BY id DESC
            """)
    IPage<Notice> selectPageForAdmin(IPage<Notice> page,
                                     @Param("publishMode") String publishMode);

    /**
     * 全量替换一条手写公告的可编辑字段。
     *
     * <p><b>为什么不用 {@code updateById}</b>：MyBatis-Plus 默认的字段策略会跳过
     * null 字段，而 {@code content} 传 null 在这里是<b>有语义的</b>（清空正文）。
     * 走 {@code updateById} 的话这个动作做不到，而且<b>不报任何错</b> ——
     * 表现为「改了半天没变化」。理由同 {@code DeviceMapper#updateDevice}。
     *
     * <p><b>语句里带了 {@code publish_mode = 'MANUAL'} 的条件</b>：这是数据库层的
     * 第二道保险。Service 已经先查过一次并挡掉了自动公告，但那一次「查」与这一次「改」
     * 之间理论上有窗口。把条件写进 WHERE 之后，即使那个窗口被撞上，
     * 自动公告也不会被改写 —— 受影响行数为 0，调用方据此可以发现问题。
     *
     * <p><b>不改 {@code publish_mode} 与 {@code source_*} 列</b>：
     * 公告的身份不该因为一次编辑而改变。
     *
     * @param id      公告 ID
     * @param title   新标题
     * @param content 新正文，可为 null（清空）
     * @return 受影响行数；0 表示公告不存在、已删，或它不是手写公告
     */
    @Update("""
            UPDATE biz_notice
               SET title      = #{title},
                   content    = #{content},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
               AND publish_mode = 'MANUAL'
            """)
    int updateManualFields(@Param("id") Long id,
                           @Param("title") String title,
                           @Param("content") String content);

    // 说明：新增公告直接用 MyBatis-Plus 自带的 insert 即可 ——
    // 自动公告就是插一行，没有幂等、没有 upsert，不必手写 SQL。
    // 按 ID 查单条同理。这里不再写同义的方法，免得读代码的人多面对一个
    // 需要判断「两者有什么区别」的选择。
}
