package com.example.metrics;

import com.example.metrics.dto.AnalyzeRequest;
import com.example.metrics.dto.DesignInput;
import com.example.metrics.service.MetricsAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MetricsApplicationTests {
    @Autowired
    private MetricsAnalysisService metricsAnalysisService;

    @Test
    void contextLoads() {
    }

    @Test
    void analyzeReturnsStructuredMetrics() {
        AnalyzeRequest request = new AnalyzeRequest();
        request.setCode("""
                public class Demo extends BaseService {
                    private String name;
                    private int count;

                    @Override
                    public void run() {
                        if (count > 0 && name != null) {
                            count++;
                        }
                    }

                    public int sum(int a, int b) {
                        return a + b;
                    }
                }

                class BaseService {
                    public void run() {
                    }
                }
                """);
        DesignInput design = new DesignInput();
        design.setActors(2);
        design.setUseCases(3);
        design.setDecisions(2);
        request.setDesign(design);

        Map<String, Object> result = metricsAnalysisService.analyze(request);

        assertNotNull(result.get("overview"));
        assertNotNull(result.get("ckMetrics"));
        assertEquals(2, ((Map<?, ?>) result.get("overview")).get("classes"));
        assertTrue((Integer) result.get("WMC") >= 3);
        assertTrue((Integer) result.get("Complexity") >= 4);
    }

    @Test
    void analyzeFilesReturnsAggregatedMetrics() throws Exception {
        MockMultipartFile fileA = new MockMultipartFile(
                "files",
                "Alpha.java",
                "text/x-java-source",
                """
                public class Alpha {
                    private Beta beta;

                    public void run() {
                        if (beta != null) {
                            beta.work();
                        }
                    }
                }
                """.getBytes()
        );
        MockMultipartFile fileB = new MockMultipartFile(
                "files",
                "Beta.java",
                "text/x-java-source",
                """
                public class Beta {
                    public void work() {
                    }
                }
                """.getBytes()
        );

        DesignInput design = new DesignInput();
        design.setClasses(2);
        design.setUseCases(2);

        Map<String, Object> result = metricsAnalysisService.analyzeFiles(
                new MockMultipartFile[]{fileA, fileB},
                design,
                null
        );

        Map<?, ?> overview = (Map<?, ?>) result.get("overview");
        Map<?, ?> uploadSummary = (Map<?, ?>) result.get("uploadSummary");

        assertEquals(2, overview.get("classes"));
        assertEquals(2, uploadSummary.get("fileCount"));
        assertTrue((Integer) result.get("CBO") >= 1);
        assertNotNull(result.get("classMetrics"));
    }

}
