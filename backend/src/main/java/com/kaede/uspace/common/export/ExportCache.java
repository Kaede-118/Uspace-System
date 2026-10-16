package com.kaede.uspace.common.export;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导出文件的内存缓存。
 *
 * <p><b>为什么生成与下载要分两步</b>：导出是「点一下、等一会儿、再拿走」的动作，
 * 生成一次可能要查全库、拼几千行 —— 把它与下载放在同一个请求里，
 * 前端就只能一直转圈等一个不确定大小的响应；拆开之后，
 * 生成接口把结果放进这里并回一个短 token，下载接口再按 token 取文件。
 * 用户重复点下载、或用下载工具重试，都不会再算一遍。
 *
 * <p>⚠️ <b>只放内存，不落磁盘</b>：导出的是全量经营流水（含金额与顾客 ID），
 * 落盘就意味着多一份需要管理权限、清理、备份的文件 —— 而它的生命周期只有几分钟。
 * 重启丢失是可以接受的：重新点一次导出即可。
 *
 * <p>⚠️ <b>缓存有条数上限与过期时间</b>：没有上限的话，反复导出能把内存吃光；
 * 没有过期时间的话，昨天导出的账会一直留在这个管理员名下。
 * 过期条目在每次写入时顺手清理（惰性），不额外起定时任务 ——
 * 本项目的量级（单店、几个管理员）不需要为此引入调度。
 *
 * <p>⚠️ <b>取文件要校验归属</b>：token 是随机的，但仍按「只能下自己那份」处理 ——
 * 否则谁拿到 token 谁就能带走全量流水。
 */
@Component
public class ExportCache {

    /** 一份已生成好的文件。 */
    public record Entry(String fileName, byte[] content, long ownerId, Instant expiresAt) {
    }

    /** 有效期：够「生成 → 回前端 → 下载」走完，又不会久留 */
    private static final Duration TTL = Duration.ofMinutes(10);

    /** 同时存在的最大份数；超出时淘汰最早到期的 */
    private static final int MAX_ENTRIES = 20;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 放入一份新文件。
     *
     * @param fileName 下载时的文件名（含 .xlsx）
     * @param content  文件字节
     * @param ownerId  生成这份文件的管理员 ID
     * @return 下载用的 token
     */
    public String put(String fileName, byte[] content, long ownerId) {
        purge();
        String token = UUID.randomUUID().toString().replace("-", "");
        store.put(token, new Entry(fileName, content, ownerId, Instant.now().plus(TTL)));
        return token;
    }

    /**
     * 按 token 取文件。
     *
     * <p>取完<b>不删除</b>：用户可能点第二次下载、或浏览器重试 ——
     * 那时若 404，他只会以为「导出坏了」。真正的清理交给过期与条数上限。
     *
     * @param token   下载 token
     * @param ownerId 当前管理员 ID
     * @return 文件；不存在、已过期、或不属于该管理员时返回 null
     */
    public Entry get(String token, long ownerId) {
        if (token == null) {
            return null;
        }
        Entry entry = store.get(token);
        if (entry == null || entry.expiresAt().isBefore(Instant.now()) || entry.ownerId() != ownerId) {
            return null;
        }
        return entry;
    }

    /** 清掉过期条目；仍然超额时淘汰最早到期的若干份。 */
    private void purge() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        int overflow = store.size() - MAX_ENTRIES;
        if (overflow > 0) {
            List<String> oldest = store.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .limit(overflow)
                    .map(Map.Entry::getKey)
                    .toList();
            oldest.forEach(store::remove);
        }
    }
}
