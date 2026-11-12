package com.li.stock.ga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/ga/* 契约测试（规格 15.1 第 10 条）：覆盖全部查询接口、分页、空数据与非法参数。
 *
 * <p>用真实 Spring 上下文 + 本地 MySQL（表由 GaSchemaInitializer 幂等创建），
 * 关闭定时触发避免测试进程跑重任务；只验证返回外壳与结构，不写入业务预测数据。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "stock.score-schedule.enabled=false",
        "stock.ga.enabled=false",
        "stock.scheduled-crawl.enabled=false"
})
class GaApiContractTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** Spring Boot 4 的 webmvc-test starter 不含 MockMvc 自动配置，直接用 spring-test 构建。 */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static final String BASE = "/api/ga";

    /** 全部查询接口在空数据下也必须返回 code=0 与契约结构，不得 500。 */
    @Test
    void allQueryEndpointsReturnOkEnvelope() throws Exception {
        String[] paths = {
                "/overview",
                "/overview?horizon=5&from=2026-09-01&to=2026-11-12&board=MAIN&strict=true&includeReplay=true",
                "/overview/trend?horizon=5&window=20&from=2026-09-01&to=2026-11-12",
                "/overview/calendar?horizon=5&month=2026-11",
                "/predictions?page=1&size=5&sort=confidence,desc&inPool=true&board=MAIN",
                "/stock/SH/600519?horizon=5&from=2026-05-01&to=2026-11-12",
                "/analysis/buckets?horizon=5&dim=vol",
                "/analysis/reliability?horizon=5",
                "/analysis/confusion?horizon=5",
                "/analysis/top-errors?horizon=5&topN=10",
                "/analysis/quantile?horizon=5",
                "/pool?page=1&size=5",
                "/pool/log?code=600519&market=SH",
                "/evolution/runs?horizon=5&page=1&size=5",
                "/evolution/runs/1/generations",
                "/individuals?horizon=5",
                "/champions?horizon=5",
                "/factors/ic?horizon=5&days=60",
                "/picks?date=2026-11-12&topN=10&direction=1&minConsensus=1",
                "/picks?date=2026-11-12&topN=10&direction=-1&minConsensus=1",
                "/config?scope=GLOBAL",
                "/jobs/status",
        };
        for (String path : paths) {
            mvc.perform(get(BASE + path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    /** 非法日期参数由 Controller 兜底为默认值，不应抛 400。 */
    @Test
    void malformedParamsFallbackInsteadOfCrashing() throws Exception {
        mvc.perform(get(BASE + "/overview?from=not-a-date&to=&horizon="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(get(BASE + "/predictions?page=0&size=999&sort=unknown,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(200));
    }

    /** CSV 导出返回文件流，带 UTF-8 BOM 与表头。 */
    @Test
    void csvExportReturnsTextCsv() throws Exception {
        mvc.perform(get(BASE + "/predictions/export?from=2026-09-01&to=2026-11-12"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("tradeDate")));
    }

    /** 写接口的非法参数返回 code=1 且带可读原因，而不是 500。 */
    @Test
    void writeEndpointsRejectIllegalParams() throws Exception {
        mvc.perform(post(BASE + "/jobs/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"job\":\"NO_SUCH_JOB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        mvc.perform(post(BASE + "/pool/override").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"600519\",\"market\":\"SH\",\"action\":\"ELIMINATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        mvc.perform(post(BASE + "/champions/rollback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horizon\":5,\"versionTag\":\"NOPE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        mvc.perform(post(BASE + "/evolution/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-11-12\",\"to\":\"2026-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    /** 配置读取 / 保存：写回同一个默认值，不污染用户配置。 */
    @Test
    void configReadAndWrite() throws Exception {
        mvc.perform(get(BASE + "/config?scope=GLOBAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.defaults").isMap());
        mvc.perform(put(BASE + "/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"GLOBAL\",\"paramKey\":\"color_scheme\",\"paramValue\":\"CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
    }
}
