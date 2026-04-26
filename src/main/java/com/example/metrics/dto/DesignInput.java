package com.example.metrics.dto;

public class DesignInput {
    private int actors;
    private int useCases;
    private int classes;
    private int subclasses;
    private int decisions;
    private int transactions;
    private int entities;

    public int getActors() {
        return actors;
    }

    public void setActors(int actors) {
        this.actors = Math.max(actors, 0);
    }

    public int getUseCases() {
        return useCases;
    }

    public void setUseCases(int useCases) {
        this.useCases = Math.max(useCases, 0);
    }

    public int getClasses() {
        return classes;
    }

    public void setClasses(int classes) {
        this.classes = Math.max(classes, 0);
    }

    public int getSubclasses() {
        return subclasses;
    }

    public void setSubclasses(int subclasses) {
        this.subclasses = Math.max(subclasses, 0);
    }

    public int getDecisions() {
        return decisions;
    }

    public void setDecisions(int decisions) {
        this.decisions = Math.max(decisions, 0);
    }

    public int getTransactions() {
        return transactions;
    }

    public void setTransactions(int transactions) {
        this.transactions = Math.max(transactions, 0);
    }

    public int getEntities() {
        return entities;
    }

    public void setEntities(int entities) {
        this.entities = Math.max(entities, 0);
    }
}
