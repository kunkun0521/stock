package com.li.stock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** 上下文加载测试里关闭评分自动计算，避免测试进程触发真实重算写库。 */
@SpringBootTest
@TestPropertySource(properties = "stock.score-schedule.enabled=false")
class StockApplicationTests {

    @Test
    void contextLoads() {
    }

}
