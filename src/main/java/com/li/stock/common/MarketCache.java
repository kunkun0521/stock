package com.li.stock.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 市场看板接口的本地缓存（Caffeine）。
 *
 * <p>看板一次刷新会并发打 5~6 个聚合接口，每个接口都要对 stock_daily_all 视图做
 * 全市场去重分组（约 1.5 万行/次），日内数据在抓取任务跑完前是不变的，因此按
 * 参数维度缓存结果即可把重复聚合全部消掉。</p>
 *
 * <p>三层保障：① 写入后 10 分钟自动过期兜底；② 抓取任务写入新行情后由
 * {@code MarketCacheWarmer} 主动失效并重新预热；③ 应用启动时预热，
 * 第一个用户请求直接命中缓存。</p>
 */
@Component
public class MarketCache {

    /** 写入后过期时间：兜底用，正常靠抓取完成后的主动刷新保持新鲜。 */
    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofMinutes(10);

    /** 缓存条目上限（不同交易日 × 接口 × 参数组合），超出后按频率/热度淘汰。 */
    private static final int MAX_ENTRIES = 512;

    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(EXPIRE_AFTER_WRITE)
            .recordStats()
            .build();

    /**
     * 读缓存，未命中或过期时用 loader 加载并回填。
     * 同一 key 的并发加载由 Caffeine 保证只执行一次。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        return (T) cache.get(key, k -> loader.get());
    }

    /** 直接写入（预热时使用）。 */
    public void put(String key, Object value) {
        if (value != null) {
            cache.put(key, value);
        }
    }

    /** 抓取任务写入新行情后调用，让下一次加载拿到最新数据。 */
    public void evictAll() {
        cache.invalidateAll();
    }

    /** 缓存命中率等统计，供监控/日志使用。 */
    public String stats() {
        return cache.stats().toString();
    }
}
