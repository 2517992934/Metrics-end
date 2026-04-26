package com.example.metrics.controller;

import com.example.metrics.util.MetricsVisitor;
import org.eclipse.jdt.core.dom.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // 解决前后端分离跨域问题
public class MetricsController {

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody String code) {
        // 1. 初始化 AST 解析器
        ASTParser parser = ASTParser.newParser(AST.JLS16);
        parser.setSource(code.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        // 2. 创建 AST 树
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);

        // 3. 运行访问器提取指标数据
        MetricsVisitor visitor = new MetricsVisitor();
        cu.accept(visitor);

        // 4. 返回结果给前端
        return visitor.getResults();
    }
}