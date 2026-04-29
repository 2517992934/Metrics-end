package com.example.metrics.controller;

import com.example.metrics.dto.AnalyzeRequest;
import com.example.metrics.dto.DesignInput;
import com.example.metrics.dto.EstimateInput;
import com.example.metrics.service.MetricsAnalysisService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class MetricsController {
    private final MetricsAnalysisService metricsAnalysisService;

    public MetricsController(MetricsAnalysisService metricsAnalysisService) {
        this.metricsAnalysisService = metricsAnalysisService;
    }

    // 文本分析接口：
    // 前端把代码文本、设计阶段输入、估算参数一起作为 JSON 提交。
    // 控制器不做具体计算，只负责把请求转交给 Service。
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody AnalyzeRequest request) {
        return metricsAnalysisService.analyze(request);
    }

    // 文件上传分析接口：
    // 前端上传一个或多个 .java 文件，同时把设计参数和估算参数作为表单字段提交。
    // 这里依旧只负责收参和组装 DTO，具体分析逻辑放在 Service 层。
    /**
     * 文件上传分析接口

     * 使用场景：用户上传.java文件（可多选）

     * HTTP请求格式：multipart/form-data（文件上传专用格式）

     * 前端表单字段说明：
     * - files: 上传的.java文件（可以多个）
     * - actors/useCases/... : 设计阶段的各项输入
     * - teamMembers/monthlyRate/productivity : 经济估算参数

     * 注意：使用 @RequestPart 接收文件，@RequestParam 接收普通表单字段
     *
     * @param files 上传的Java源文件数组
     * @param actors 参与者数量（默认0）
     * @param useCases 用例数量（默认0）
     * @param classes 类数量（默认0）
     * @param subclasses 子类数量（默认0）
     * @param decisions 决策/判定节点数（默认0）
     * @param transactions 事务数量（默认0）
     * @param entities 实体数量（默认0）
     * @param teamMembers 团队成员数（默认3人）
     * @param monthlyRate 人均月薪（默认15000元）
     * @param productivity 生产力系数（默认1.0）
     * @return 包含各种度量指标的Map
     * @throws IOException 文件读取失败时抛出
     */
    @PostMapping(value = "/analyze/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> analyzeUpload(
            @RequestPart("files") MultipartFile[] files,
            @RequestParam(value = "actors", defaultValue = "0") int actors,
            @RequestParam(value = "useCases", defaultValue = "0") int useCases,
            @RequestParam(value = "classes", defaultValue = "0") int classes,
            @RequestParam(value = "subclasses", defaultValue = "0") int subclasses,
            @RequestParam(value = "decisions", defaultValue = "0") int decisions,
            @RequestParam(value = "transactions", defaultValue = "0") int transactions,
            @RequestParam(value = "entities", defaultValue = "0") int entities,
            @RequestParam(value = "teamMembers", defaultValue = "3") int teamMembers,
            @RequestParam(value = "monthlyRate", defaultValue = "15000") int monthlyRate,
            @RequestParam(value = "productivity", defaultValue = "1.0") double productivity
    ) throws IOException {
        // 控制器层尽量保持轻量：
        // 这里只把零散表单参数整理成 DTO，避免把业务计算写进 Controller。
        DesignInput design = new DesignInput();
        design.setActors(actors);
        design.setUseCases(useCases);
        design.setClasses(classes);
        design.setSubclasses(subclasses);
        design.setDecisions(decisions);
        design.setTransactions(transactions);
        design.setEntities(entities);

        EstimateInput estimate = new EstimateInput();
        estimate.setTeamMembers(teamMembers);
        estimate.setMonthlyRate(monthlyRate);
        estimate.setProductivity(productivity);

        return metricsAnalysisService.analyzeFiles(files, design, estimate);
    }
}
