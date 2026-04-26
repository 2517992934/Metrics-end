package com.example.metrics.dto;

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
