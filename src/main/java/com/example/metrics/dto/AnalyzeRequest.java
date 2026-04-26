package com.example.metrics.dto;

public class AnalyzeRequest {
    private String code;
    private DesignInput design = new DesignInput();
    private EstimateInput estimate = new EstimateInput();

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public DesignInput getDesign() {
        return design;
    }

    public void setDesign(DesignInput design) {
        this.design = design;
    }

    public EstimateInput getEstimate() {
        return estimate;
    }

    public void setEstimate(EstimateInput estimate) {
        this.estimate = estimate;
    }
}
