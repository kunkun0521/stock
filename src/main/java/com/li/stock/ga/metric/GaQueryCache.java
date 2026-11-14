package com.li.stock.ga.metric;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 看板只读查询的轻量 TTL 缓存。
 *
 * <p>看板/错误分析这些接口的数据来自 ga_prediction 大表聚合，即使加了覆盖索引，
 * 单次也要数百毫秒；而前端总览/分析页会同时请求多个接口、用户也会快速切换或重复点击。
 * 这里按「请求参数归一化后的 key」缓存结果几秒，让并发与重复请求只算一次。</p>
 *
 * <p>注意边界：只用于<strong>只读看板</strong>，写操作（进化/回放/晋升/清理/任务）不会经过这里；
 * TTL 默认 5 秒，保证新产生的预测/指标最多延迟几秒就能看到，不影响正确性。</p>
 */
@Component
public class GaQueryCache {

    /** 默认缓存秒数。 */
    private static final long DEFAULT_TTL_SECONDS = 5;

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(long expireAt, Object value) {
    }

    /**
     * 命中则返回缓存值，否则用 supplier 计算、缓存并返回。
     *
     * @param key  业务键（须包含所有影响结果的参数，如 horizon/from/to/board）
     * @param load 未命中时的计算逻辑
     */
    public <T> T get(String key, Supplier<T> load) {
        return get(key, DEFAULT_TTL_SECONDS, load);
    }

    /**
     * @param ttlSeconds 缓存秒数，<=0 表示不缓存（等价直算）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, long ttlSeconds, Supplier<T> load) {
        if (ttlSeconds <= 0) return load.get();
        long now = System.currentTimeMillis();
        Entry e = cache.get(key);
        if (e != null && e.expireAt() > now) {
            return (T) e.value();
        }
        T value = load.get();
        cache.put(key, new Entry(now + ttlSeconds * 1000, value));
        // 防止长期运行后 key 无限增长（参数组合有界，这里设一个宽松上限）
        if (cache.size() > 4096) {
            cache.entrySet().removeIf(en -> en.getValue().expireAt() <= now);
        }
        return value;
    }

    /** 作废某个 key（写操作后调用，可选）。 */
    public void invalidate(String key) {
        cache.remove(key);
    }
}
