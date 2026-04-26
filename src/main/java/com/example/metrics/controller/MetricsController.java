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

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody AnalyzeRequest request) {
        return metricsAnalysisService.analyze(request);
    }

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
