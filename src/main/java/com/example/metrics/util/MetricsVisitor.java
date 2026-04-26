package com.example.metrics.util;

import org.eclipse.jdt.core.dom.*;
import java.util.HashMap;
import java.util.Map;

public class MetricsVisitor extends ASTVisitor {
    private int wmc = 0;        // 加权方法数
    private int dit = 0;        // 继承树深度
    private int loc = 0;        // 代码行数
    private int complexity = 0; // 圈复杂度

    @Override
    public boolean visit(CompilationUnit node) {
        // 计算行数：获取最后一行行号
        loc = node.getLineNumber(node.getLength() - 1);
        return true;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        // CK模型：DIT (简单判定：有父类则计为1)
        if (node.getSuperclassType() != null) dit = 1;
        return true;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        wmc++; // 每个方法计1分
        complexity++; // 每个方法基础复杂度为1
        return true;
    }

    // 统计逻辑分支：If, For, While, Do, Catch, Switch Case
    @Override public boolean visit(IfStatement node) { complexity++; return true; }
    @Override public boolean visit(ForStatement node) { complexity++; return true; }
    @Override public boolean visit(WhileStatement node) { complexity++; return true; }
    @Override public boolean visit(SwitchCase node) { if(!node.isDefault()) complexity++; return true; }

    public Map<String, Object> getResults() {
        Map<String, Object> results = new HashMap<>();
        results.put("LoC", loc);
        results.put("WMC", wmc);
        results.put("DIT", dit);
        results.put("Complexity", complexity);
        return results;
    }
}