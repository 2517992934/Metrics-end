package com.example.metrics.dto;
/**
 * 工作量/成本估算参数DTO

 * 软件经济学中的核心概念：
 * - 工作量估算：需要多少人月完成项目
 * - 成本估算：需要多少资金
 * - 时间估算：需要多少日历时间

 * COCOMO模型等成本估算方法都需要这些参数
 */
public class EstimateInput {
    private int teamMembers = 3;
    private int monthlyRate = 15000;
    private double productivity = 1.0;

    public int getTeamMembers() {
        return teamMembers;
    }

    public void setTeamMembers(int teamMembers) {
        this.teamMembers = teamMembers <= 0 ? 3 : teamMembers;
    }

    public int getMonthlyRate() {
        return monthlyRate;
    }

    public void setMonthlyRate(int monthlyRate) {
        this.monthlyRate = monthlyRate <= 0 ? 15000 : monthlyRate;
    }

    public double getProductivity() {
        return productivity;
    }

    public void setProductivity(double productivity) {
        this.productivity = productivity <= 0 ? 1.0 : productivity;
    }
}
