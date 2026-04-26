package com.example.metrics.service;

import com.example.metrics.dto.AnalyzeRequest;
import com.example.metrics.dto.DesignInput;
import com.example.metrics.dto.EstimateInput;
import com.example.metrics.util.MetricsVisitor;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MetricsAnalysisService {

    public Map<String, Object> analyze(AnalyzeRequest request) {
        String code = request == null || request.getCode() == null ? "" : request.getCode();
        DesignInput design = request == null || request.getDesign() == null ? new DesignInput() : request.getDesign();
        EstimateInput estimate = request == null || request.getEstimate() == null ? new EstimateInput() : request.getEstimate();

        List<SourceUnit> sources = new ArrayList<>();
        sources.add(new SourceUnit("InlineSnippet.java", code));
        return analyzeSources(sources, design, estimate, "text");
    }

    public Map<String, Object> analyzeFiles(MultipartFile[] files, DesignInput design, EstimateInput estimate) throws IOException {
        List<SourceUnit> sources = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String filename = file.getOriginalFilename() == null ? "UploadedFile.java" : file.getOriginalFilename();
                if (!filename.toLowerCase().endsWith(".java")) {
                    continue;
                }
                String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                sources.add(new SourceUnit(filename, content));
            }
        }
        return analyzeSources(sources, design == null ? new DesignInput() : design,
                estimate == null ? new EstimateInput() : estimate, "upload");
    }

    private Map<String, Object> analyzeSources(List<SourceUnit> sources, DesignInput design, EstimateInput estimate, String analysisMode) {
        AggregateSummary aggregate = new AggregateSummary();
        List<String> analyzedFiles = new ArrayList<>();

        for (SourceUnit source : sources) {
            if (source.content() == null || source.content().isBlank()) {
                continue;
            }
            analyzedFiles.add(source.name());
            MetricsVisitor.AnalysisSummary summary = parseSource(source.content()).buildSummary();
            aggregate.accumulate(summary, source.name());
        }

        int loc = aggregate.loc;
        double adjustedKloc = Math.max(loc / 1000.0, 0.1);
        double useCaseWeight = design.getActors() * 1.5 + design.getUseCases() * 2.0 + design.getTransactions() * 0.8;
        double designWeight = design.getClasses() * 0.5 + design.getEntities() * 0.4 + design.getDecisions() * 0.7;
        double complexityFactor = 1 + (aggregate.complexity / 25.0) + (aggregate.cbo / 20.0);
        double effort = (2.4 * Math.pow(adjustedKloc, 1.05) + useCaseWeight * 0.15 + designWeight * 0.08)
                * complexityFactor / estimate.getProductivity();
        double schedule = 2.5 * Math.pow(Math.max(effort, 0.5), 0.38);
        int recommendedPeople = Math.max((int) Math.ceil(effort / Math.max(schedule, 1.0)), 1);
        int configuredPeople = Math.max(estimate.getTeamMembers(), 1);
        int cost = (int) Math.round(effort * estimate.getMonthlyRate());

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("loc", loc);
        overview.put("commentLines", aggregate.commentLines);
        overview.put("blankLines", aggregate.blankLines);
        overview.put("classes", aggregate.classCount);
        overview.put("methods", aggregate.methodCount);
        overview.put("fields", aggregate.fieldCount);
        overview.put("commentDensity", percent(aggregate.commentLines, Math.max(loc + aggregate.commentLines, 1)));
        overview.put("effortPersonMonths", round(effort));
        overview.put("scheduleMonths", round(schedule));
        overview.put("estimatedCost", cost);
        overview.put("recommendedPeople", recommendedPeople);
        overview.put("configuredPeople", configuredPeople);
        overview.put("sourceFiles", analyzedFiles.size());
        overview.put("analysisMode", analysisMode);

        Map<String, Object> ckMetrics = new LinkedHashMap<>();
        ckMetrics.put("WMC", aggregate.wmc);
        ckMetrics.put("DIT", aggregate.dit);
        ckMetrics.put("CBO", aggregate.cbo);
        ckMetrics.put("RFC", aggregate.rfc);
        ckMetrics.put("LCOM", aggregate.lcom);
        ckMetrics.put("NOC", Math.max(aggregate.noc, design.getSubclasses()));

        Map<String, Object> lkMetrics = new LinkedHashMap<>();
        lkMetrics.put("NOA", aggregate.noa);
        lkMetrics.put("NPM", aggregate.npm);
        lkMetrics.put("NIV", aggregate.niv);
        lkMetrics.put("NVO", aggregate.nvo);
        lkMetrics.put("ClassCount", aggregate.classCount);
        lkMetrics.put("MethodCount", aggregate.methodCount);

        Map<String, Object> traditionalMetrics = new LinkedHashMap<>();
        traditionalMetrics.put("CyclomaticComplexity", aggregate.complexity);
        traditionalMetrics.put("AverageComplexity", round(aggregate.methodCount == 0 ? 0 : (double) aggregate.complexity / aggregate.methodCount));
        traditionalMetrics.put("LoC", loc);
        traditionalMetrics.put("BlankLines", aggregate.blankLines);
        traditionalMetrics.put("CommentLines", aggregate.commentLines);
        traditionalMetrics.put("MaintainabilityLevel", resolveMaintainability(aggregate, loc));

        Map<String, Object> designMetrics = new LinkedHashMap<>();
        designMetrics.put("Actors", design.getActors());
        designMetrics.put("UseCases", design.getUseCases());
        designMetrics.put("Classes", Math.max(design.getClasses(), aggregate.classCount));
        designMetrics.put("Subclasses", Math.max(design.getSubclasses(), aggregate.noc));
        designMetrics.put("Decisions", design.getDecisions());
        designMetrics.put("Transactions", design.getTransactions());
        designMetrics.put("Entities", design.getEntities());
        designMetrics.put("UseCasePoints", round(useCaseWeight + design.getEntities() * 0.6));
        designMetrics.put("DecisionAlignment", resolveDecisionAlignment(design.getDecisions(), aggregate.complexity));

        Map<String, Object> estimation = new LinkedHashMap<>();
        estimation.put("Effort", round(effort) + " 人月");
        estimation.put("Time", round(schedule) + " 月");
        estimation.put("Cost", "￥" + cost);
        estimation.put("People", recommendedPeople + " 人");
        estimation.put("ConfiguredPeople", configuredPeople + " 人");

        Map<String, Object> uploadSummary = new LinkedHashMap<>();
        uploadSummary.put("fileCount", analyzedFiles.size());
        uploadSummary.put("files", analyzedFiles);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", overview);
        result.put("ckMetrics", ckMetrics);
        result.put("lkMetrics", lkMetrics);
        result.put("traditionalMetrics", traditionalMetrics);
        result.put("designMetrics", designMetrics);
        result.put("estimation", estimation);
        result.put("classMetrics", aggregate.classBreakdown);
        result.put("riskSignals", buildRiskSignals(aggregate, loc, design));
        result.put("uploadSummary", uploadSummary);

        result.put("LoC", loc);
        result.put("WMC", aggregate.wmc);
        result.put("DIT", aggregate.dit);
        result.put("CBO", aggregate.cbo);
        result.put("RFC", aggregate.rfc);
        result.put("LCOM", aggregate.lcom);
        result.put("Complexity", aggregate.complexity);
        result.put("NOA", aggregate.noa);
        result.put("NPM", aggregate.npm);
        result.put("NIV", aggregate.niv);
        result.put("NVO", aggregate.nvo);
        result.put("Effort", estimation.get("Effort"));
        result.put("Time", estimation.get("Time"));
        result.put("Cost", estimation.get("Cost"));
        result.put("People", estimation.get("People"));
        return result;
    }

    private MetricsVisitor parseSource(String code) {
        ASTParser parser = ASTParser.newParser(AST.JLS16);
        parser.setSource(code.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(false);

        CompilationUnit compilationUnit = (CompilationUnit) parser.createAST(null);
        MetricsVisitor visitor = new MetricsVisitor(code);
        compilationUnit.accept(visitor);
        return visitor;
    }

    private List<Map<String, Object>> buildRiskSignals(AggregateSummary summary, int loc, DesignInput design) {
        List<Map<String, Object>> signals = new ArrayList<>();
        if (summary.cbo >= 8) {
            signals.add(signal("high", "耦合偏高", "CBO 较高，类之间依赖较多，后续维护和复用风险上升。"));
        }
        if (summary.complexity >= Math.max(summary.methodCount * 3, 10)) {
            signals.add(signal("medium", "复杂度偏高", "圈复杂度累计值较高，建议拆分条件分支密集的方法。"));
        }
        if (summary.lcom > summary.classCount) {
            signals.add(signal("medium", "内聚性一般", "LCOM 偏高，部分类承担了过多不相关职责。"));
        }
        if (design.getDecisions() > 0 && Math.abs(design.getDecisions() - summary.complexity) > 5) {
            signals.add(signal("low", "设计与代码存在偏差", "流程图判定数与代码复杂度差异较大，建议复核设计实现一致性。"));
        }
        if (summary.classCount == 0) {
            signals.add(signal("high", "未检测到有效 Java 类", "请确认上传的是 .java 文件，并且文件中包含可解析的类定义。"));
        }
        if (signals.isEmpty()) {
            signals.add(signal("low", "结构稳定", "当前样例未发现明显的高风险度量信号，适合作为课程实验演示。"));
        }
        return signals;
    }

    private Map<String, Object> signal(String level, String title, String detail) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("level", level);
        signal.put("title", title);
        signal.put("detail", detail);
        return signal;
    }

    private String resolveMaintainability(AggregateSummary summary, int loc) {
        int score = 100;
        score -= summary.complexity * 2;
        score -= summary.cbo * 3;
        score -= summary.lcom * 2;
        score += Math.min(summary.commentLines, 20);
        score -= loc > 300 ? 10 : 0;
        if (score >= 70) {
            return "良好";
        }
        if (score >= 45) {
            return "中等";
        }
        return "需优化";
    }

    private String resolveDecisionAlignment(int designedDecisions, int complexity) {
        if (designedDecisions <= 0) {
            return "未提供设计阶段判定数";
        }
        int delta = Math.abs(designedDecisions - complexity);
        if (delta <= 2) {
            return "高度一致";
        }
        if (delta <= 5) {
            return "基本一致";
        }
        return "偏差明显";
    }

    private double percent(int numerator, int denominator) {
        return round(denominator == 0 ? 0 : numerator * 100.0 / denominator);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record SourceUnit(String name, String content) {}

    private static class AggregateSummary {
        private int loc;
        private int blankLines;
        private int commentLines;
        private int classCount;
        private int methodCount;
        private int fieldCount;
        private int wmc;
        private int complexity;
        private int cbo;
        private int rfc;
        private int lcom;
        private int dit;
        private int noc;
        private int npm;
        private int noa;
        private int niv;
        private int nvo;
        private final List<Map<String, Object>> classBreakdown = new ArrayList<>();

        private void accumulate(MetricsVisitor.AnalysisSummary summary, String sourceName) {
            loc += summary.loc();
            blankLines += summary.blankLines();
            commentLines += summary.commentLines();
            classCount += summary.classCount();
            methodCount += summary.methodCount();
            fieldCount += summary.fieldCount();
            wmc += summary.wmc();
            complexity += summary.complexity();
            cbo += summary.cbo();
            rfc += summary.rfc();
            lcom += summary.lcom();
            dit = Math.max(dit, summary.dit());
            noc += summary.noc();
            npm += summary.npm();
            noa += summary.noa();
            niv += summary.niv();
            nvo += summary.nvo();

            for (Map<String, Object> classMetric : summary.classBreakdown()) {
                Map<String, Object> withSource = new LinkedHashMap<>(classMetric);
                withSource.put("sourceFile", sourceName);
                classBreakdown.add(withSource);
            }
        }
    }
}
