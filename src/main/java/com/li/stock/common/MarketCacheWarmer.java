package com.li.stock.common;

import com.li.stock.service.MarketService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 市场看板缓存预热器。
 *
 * <p>两个触发时机：</p>
 * <ul>
 *   <li>应用启动完成后：后台异步预热，第一个用户请求直接命中缓存，不拖慢启动；</li>
 *   <li>抓取任务写入新行情后：调用 {@link #refreshAsync()} 清空旧缓存并重新加载，
 *       保证新增数据立即进入缓存。</li>
 * </ul>
 *
 * <p>预热在独立守护线程中串行执行，多次触发自动排队，不会与请求线程争抢连接池。</p>
 */
@Component
public class MarketCacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(MarketCacheWarmer.class);

    private final MarketService marketService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "market-cache-warmer");
        thread.setDaemon(true);
        return thread;
    });

    public MarketCacheWarmer(MarketService marketService) {
        this.marketService = marketService;
    }

    /** 启动完成后异步预热，避免把聚合查询塞进启动关键路径。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        refreshAsync();
    }

    /** 抓取任务结束后调用：失效旧缓存并在后台重新加载最新数据。 */
    public void refreshAsync() {
        executor.submit(() -> {
            try {
                marketService.warmUp();
            } catch (RuntimeException ex) {
                log.warn("市场看板缓存刷新失败：{}", ex.getMessage());
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
