package com.example.metrics.util;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BlockComment;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * 度量指标收集器（使用访问者模式遍历AST）

 * 设计模式：Visitor（访问者模式）

 * 基本原理：
 * - AST（抽象语法树）是一个树形数据结构
 * - 创建一个继承ASTVisitor的类
 * - 重写 visit(Xxx node) 方法，在遍历到特定类型的节点时执行代码
 * - 调用 compilationUnit.accept(visitor) 启动遍历

 * 例如：visit(MethodDeclaration node) 会在每个方法被遍历到时被调用

 * 收集的度量指标包括：
 * - 圈复杂度（方法中分支数量）
 * - 耦合度CBO（类依赖的其他类数量）
 * - 继承深度DIT
 * - 内聚度LCOM
 * - 等等
 */
public class MetricsVisitor extends ASTVisitor {
    // 这些类型属于常见基础类型或集合类型。
    // 计算 CBO 时把它们排除掉，可以避免耦合度因为 String/List 之类的类型虚高。
    private static final Set<String> IGNORED_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Short", "Byte", "Character",
            "List", "ArrayList", "LinkedList", "Set", "HashSet", "Map", "HashMap", "Collection", "Object"
    );

    private final String sourceCode;
    private CompilationUnit compilationUnit;
    private final List<ClassMetrics> classes = new ArrayList<>();
    private final Deque<ClassMetrics> classStack = new ArrayDeque<>();
    private final Deque<MethodMetrics> methodStack = new ArrayDeque<>();
    private final Map<String, Integer> childClassCount = new HashMap<>();

    public MetricsVisitor(String sourceCode) {
        this.sourceCode = sourceCode == null ? "" : sourceCode;
    }
// ========== ASTVisitor的生命周期方法 ==========
    // 每个 visit 方法在进入节点时调用，返回true表示继续遍历子节点
    // 每个 endVisit 方法在退出节点时调用

    /**
     * 进入编译单元时调用
     * CompilationUnit 是整个Java文件的根节点
     * 保存它以便后续获取行号信息
     */
    @Override
    public boolean visit(CompilationUnit node) {
        // CompilationUnit 可以理解成整段 Java 源码对应的 AST 根节点。
        // 先把它保存下来，后面统计行号、查找类型时都要用到。
        this.compilationUnit = node;
        return true;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        // 每遇到一个类，就创建这个类自己的度量容器，
        // 后续字段、方法、复杂度、耦合等统计都会挂在这里。
        ClassMetrics metrics = new ClassMetrics();
        metrics.name = node.getName().getIdentifier();
        metrics.depthOfInheritance = resolveDepth(node);
        metrics.isInterface = node.isInterface();
        metrics.declaredMethods = node.getMethods().length;
        metrics.responseMethods.addAll(extractDeclaredMethodNames(node.getMethods()));
        classes.add(metrics);
        classStack.push(metrics);

        Type superType = node.getSuperclassType();
        if (superType != null) {
            // 父类既影响继承深度，也会影响子类数量和耦合度。
            String superName = superType.toString();
            childClassCount.merge(superName, 1, Integer::sum);
            registerTypeCoupling(metrics, superType);
        }
        for (Object superInterface : node.superInterfaceTypes()) {
            // 实现接口也是一种类型依赖关系。
            registerTypeCoupling(metrics, (Type) superInterface);
        }
        return true;
    }

    @Override
    public void endVisit(TypeDeclaration node) {
        ClassMetrics metrics = classStack.pop();
        // 类扫描结束后，才能最终确定：
        // 1. 这个类有多少子类（NOC）
        // 2. 这个类的简化 LCOM 值
        metrics.numberOfChildren = childClassCount.getOrDefault(metrics.name, 0);
        metrics.lcom = Math.max(metrics.declaredMethods == 0 ? 0 : metrics.declaredMethods - metrics.fieldUsageByMethods.size(), 0);
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        ClassMetrics currentClass = currentClass();
        if (currentClass == null) {
            return true;
        }

        // 字段会影响属性数量、实例变量数量；
        // 同时字段的类型也属于类之间的一种依赖关系。
        currentClass.fieldCount += node.fragments().size();
        for (Object fragmentObject : node.fragments()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) fragmentObject;
            currentClass.fieldNames.add(fragment.getName().getIdentifier());
        }
        if (!Modifier.isStatic(node.getModifiers())) {
            currentClass.instanceVariableCount += node.fragments().size();
        }
        registerTypeCoupling(currentClass, node.getType());
        return true;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        ClassMetrics currentClass = currentClass();
        if (currentClass == null) {
            return true;
        }

        // 每个方法的基础复杂度先记为 1，
        // 后面遇到 if/for/while 等分支节点时再继续累加。
        MethodMetrics methodMetrics = new MethodMetrics();
        methodMetrics.name = node.getName().getIdentifier();
        methodMetrics.visibility = Modifier.isPublic(node.getModifiers()) ? "public"
                : Modifier.isProtected(node.getModifiers()) ? "protected"
                : Modifier.isPrivate(node.getModifiers()) ? "private" : "package";
        methodMetrics.complexity = 1;
        methodMetrics.loc = countNodeLines(node);

        currentClass.weightedMethodCount += 1;
        currentClass.totalComplexity += 1;
        currentClass.methodMetrics.add(methodMetrics);
        methodStack.push(methodMetrics);

        // public 方法数会影响 NPM。
        if (Modifier.isPublic(node.getModifiers())) {
            currentClass.publicMethodCount++;
        }
        // 非 static 方法视为实例方法。
        if (!Modifier.isStatic(node.getModifiers())) {
            currentClass.instanceMethodCount++;
        }
        for (Object modifier : node.modifiers()) {
            if (modifier instanceof Annotation) {
                Annotation annotation = (Annotation) modifier;
                // 带 @Override 的方法用于统计 NVO。
                if (isOverride(annotation)) {
                    currentClass.overrideCount++;
                }
            }
        }
        // 返回值类型、参数类型同样会带来耦合关系。
        if (node.getReturnType2() != null) {
            registerTypeCoupling(currentClass, node.getReturnType2());
        }
        for (Object parameterObject : node.parameters()) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) parameterObject;
            registerTypeCoupling(currentClass, parameter.getType());
        }
        return true;
    }

    @Override
    public void endVisit(MethodDeclaration node) {
        // 方法扫描结束后，把它从当前方法栈里弹出。
        methodStack.pop();
    }

    @Override
    public boolean visit(SimpleType node) {
        // 普通类型名节点出现时，也尝试记录耦合关系。
        registerTypeCoupling(currentClass(), node);
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        // new 一个对象，说明当前类依赖了该对象类型。
        registerTypeCoupling(currentClass(), node.getType());
        MethodMetrics method = currentMethod();
        if (method != null) {
            // 顺便记下方法中创建过哪些对象，便于前端明细展示。
            method.invokedMethods.add("new " + node.getType());
        }
        return true;
    }

    @Override
    public boolean visit(MethodInvocation node) {
        MethodMetrics method = currentMethod();
        ClassMetrics currentClass = currentClass();
        if (method != null) {
            method.invokedMethods.add(node.getName().getIdentifier());
        }
        if (currentClass != null) {
            // RFC 不只看类自己声明的方法，
            // 也看运行过程中可能触发到的方法响应集合。
            currentClass.responseMethods.add(node.getName().getIdentifier());
            Expression expression = node.getExpression();
            if (expression != null) {
                registerNameCoupling(currentClass, expression);
            }
        }
        return true;
    }

    @Override
    public boolean visit(FieldAccess node) {
        MethodMetrics method = currentMethod();
        ClassMetrics currentClass = currentClass();
        if (method != null && currentClass != null) {
            String fieldName = node.getName().getIdentifier();
            // 记录“某字段被哪些方法使用”，供简化 LCOM 计算使用。
            if (currentClass.fieldNames.contains(fieldName)) {
                currentClass.fieldUsageByMethods.computeIfAbsent(fieldName, key -> new HashSet<>()).add(method.name);
            }
        }
        return true;
    }

    @Override
    public boolean visit(org.eclipse.jdt.core.dom.SimpleName node) {
        MethodMetrics method = currentMethod();
        ClassMetrics currentClass = currentClass();
        if (method != null && currentClass != null) {
            String identifier = node.getIdentifier();
            // 有些字段访问只会表现成简单名称，因此这里补记一次。
            if (currentClass.fieldNames.contains(identifier)) {
                currentClass.fieldUsageByMethods.computeIfAbsent(identifier, key -> new HashSet<>()).add(method.name);
            }
        }
        return true;
    }
    // ========== 圈复杂度计算：各种分支/循环语句都会增加复杂度 ==========

    /**
     * 圈复杂度（Cyclomatic Complexity）的定义：
     * 由Thomas McCabe提出，用于度量程序的控制流复杂度
     * 公式：V(G) = E - N + 2（其中E是边数，N是节点数）
     * 简化版：每增加一个分支节点，复杂度+1
     *
     * 以下所有节点类型都会增加圈复杂度：
     */
    @Override public boolean visit(IfStatement node) { addDecisionPoint(); return true; }
    @Override public boolean visit(ForStatement node) { addDecisionPoint(); return true; }
    @Override public boolean visit(EnhancedForStatement node) { addDecisionPoint(); return true; }
    @Override public boolean visit(WhileStatement node) { addDecisionPoint(); return true; }
    @Override public boolean visit(DoStatement node) { addDecisionPoint(); return true; }
    @Override public boolean visit(CatchClause node) { addDecisionPoint(); return true; }
    @Override public boolean visit(ConditionalExpression node) { addDecisionPoint(); return true; }
    @Override public boolean visit(SwitchStatement node) { addDecisionPoint(); return true; }
    @Override public boolean visit(SwitchExpression node) { addDecisionPoint(); return true; }
    @Override public boolean visit(SwitchCase node) { if (!node.isDefault()) addDecisionPoint(); return true; }

    @Override
    public boolean visit(InfixExpression node) {
        // 条件与/条件或会增加执行路径数量，因此也计入圈复杂度。
        if (node.getOperator() == InfixExpression.Operator.CONDITIONAL_AND
                || node.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
            addDecisionPoint();
        }
        return true;
    }
    /**
     * 构建最终的分析结果

     * 将遍历过程中收集的数据汇总成一个AnalysisSummary对象
     */
    public AnalysisSummary buildSummary() {
        // AST 遍历结束后，把节点级的零散统计汇总成一个 Summary，
        // 交给 Service 层继续做估算和前端返回。
        int loc = countPhysicalLoc(sourceCode);
        int blankLines = countBlankLines(sourceCode);
        int commentLines = countCommentLines();
        int classCount = classes.size();
        int methodCount = classes.stream().mapToInt(metrics -> metrics.declaredMethods).sum();
        int fieldCount = classes.stream().mapToInt(metrics -> metrics.fieldCount).sum();
        int wmc = classes.stream().mapToInt(metrics -> metrics.weightedMethodCount).sum();
        int complexity = classes.stream().mapToInt(metrics -> metrics.totalComplexity).sum();
        int cbo = classes.stream().mapToInt(metrics -> metrics.coupledClasses.size()).sum();
        int rfc = classes.stream().mapToInt(metrics -> metrics.responseMethods.size()).sum();
        int lcom = classes.stream().mapToInt(metrics -> metrics.lcom).sum();
        int dit = classes.stream().mapToInt(metrics -> metrics.depthOfInheritance).max().orElse(0);
        int noc = classes.stream().mapToInt(metrics -> metrics.numberOfChildren).sum();
        int npm = classes.stream().mapToInt(metrics -> metrics.publicMethodCount).sum();
        int noa = fieldCount;
        int niv = classes.stream().mapToInt(metrics -> metrics.instanceVariableCount).sum();
        int nvo = classes.stream().mapToInt(metrics -> metrics.overrideCount).sum();

        List<Map<String, Object>> classBreakdown = new ArrayList<>();
        for (ClassMetrics classMetrics : classes) {
            Map<String, Object> classMap = new LinkedHashMap<>();
            classMap.put("name", classMetrics.name);
            classMap.put("WMC", classMetrics.weightedMethodCount);
            classMap.put("CBO", classMetrics.coupledClasses.size());
            classMap.put("RFC", classMetrics.responseMethods.size());
            classMap.put("LCOM", classMetrics.lcom);
            classMap.put("DIT", classMetrics.depthOfInheritance);
            classMap.put("NOC", classMetrics.numberOfChildren);
            classMap.put("NOA", classMetrics.fieldCount);
            classMap.put("NPM", classMetrics.publicMethodCount);
            classMap.put("complexity", classMetrics.totalComplexity);
            classMap.put("methods", classMetrics.methodMetrics.stream().map(MethodMetrics::toMap).toList());
            classBreakdown.add(classMap);
        }

        return new AnalysisSummary(loc, blankLines, commentLines, classCount, methodCount, fieldCount,
                wmc, complexity, cbo, rfc, lcom, dit, noc, npm, noa, niv, nvo, classBreakdown);
    }

    private void addDecisionPoint() {
        MethodMetrics method = currentMethod();
        ClassMetrics currentClass = currentClass();
        if (method == null || currentClass == null) {
            return;
        }
        // 圈复杂度既要体现在当前方法上，也要体现在类的总复杂度上。
        method.complexity++;
        currentClass.totalComplexity++;
    }
    /**
     * 计算继承深度

     * 例如：
     * class A {}                    → DIT=0
     * class B extends A {}         → DIT=1
     * class C extends B {}         → DIT=2

     * DIT是CK度量中的一个重要指标：
     * - 深度太大：设计过于复杂，难以理解
     * - 深度太小：可能错过代码复用的机会
     */
    private int resolveDepth(TypeDeclaration node) {
        int depth = 0;
        Type current = node.getSuperclassType();
        while (current != null) {
            depth++;
            if (!(current instanceof SimpleType)) {
                break;
            }
            SimpleType simpleType = (SimpleType) current;
            String typeName = simpleType.getName().getFullyQualifiedName();
            TypeDeclaration matchingParent = findTypeDeclaration(typeName);
            if (matchingParent == null) {
                break;
            }
            current = matchingParent.getSuperclassType();
        }
        return depth;
    }
    /**
     * 在当前编译单元中查找类型声明
     */
    private TypeDeclaration findTypeDeclaration(String typeName) {
        for (Object type : compilationUnit.types()) {
            if (type instanceof TypeDeclaration) {
                TypeDeclaration declaration = (TypeDeclaration) type;
                if (declaration.getName().getIdentifier().equals(typeName)) {
                return declaration;
                }
            }
        }
        return null;
    }
    /**
     * 注册类型耦合（CBO）

     * CBO (Coupling Between Objects) 度量一个类依赖其他类的程度
     * 如果类A使用了类B（作为字段类型、方法参数、返回值等），就算一次耦合

     * 排除：
     * - 基本类型（int, boolean等）
     * - 常见集合类型（List, Map等）
     * - 与自己耦合
     */
    private void registerTypeCoupling(ClassMetrics currentClass, Type type) {
        if (currentClass == null || type == null) {
            return;
        }
        String typeName = type.toString().replace("[]", "");
        if (typeName.contains("<")) {
            typeName = typeName.substring(0, typeName.indexOf('<'));
        }
        if (!typeName.isBlank() && !IGNORED_TYPES.contains(typeName) && !typeName.equals(currentClass.name)) {
            currentClass.coupledClasses.add(typeName);
        }
    }
    /**
     * 通过名称注册耦合
     */
    private void registerNameCoupling(ClassMetrics currentClass, Expression expression) {
        if (currentClass == null || expression == null) {
            return;
        }
        if (expression instanceof Name) {
            Name name = (Name) expression;
            String coupledName = name.getFullyQualifiedName();
            if (!coupledName.isBlank() && Character.isUpperCase(coupledName.charAt(0))
                    && !coupledName.equals(currentClass.name) && !IGNORED_TYPES.contains(coupledName)) {
                currentClass.coupledClasses.add(coupledName);
            }
        }
    }

    private boolean isOverride(Annotation annotation) {
        if (annotation instanceof MarkerAnnotation) {
            MarkerAnnotation marker = (MarkerAnnotation) annotation;
            return "Override".equals(marker.getTypeName().getFullyQualifiedName());
        }
        return annotation.toString().contains("Override");
    }
    /**
     * 提取声明的所有方法名
     */
    private Set<String> extractDeclaredMethodNames(MethodDeclaration[] methods) {
        Set<String> methodNames = new LinkedHashSet<>();
        for (MethodDeclaration method : methods) {
            methodNames.add(method.getName().getIdentifier());
        }
        return methodNames;
    }
    /**
     * 计算方法所占的行数
     */
    private int countNodeLines(MethodDeclaration node) {
        int start = compilationUnit.getLineNumber(node.getStartPosition());
        int end = compilationUnit.getLineNumber(node.getStartPosition() + node.getLength());
        return Math.max(end - start + 1, 1);
    }
    /**
     * 统计物理代码行数（非空非注释行）
     */
    private int countPhysicalLoc(String code) {
        if (code.isBlank()) {
            return 0;
        }
        String[] lines = code.split("\\R");
        int count = 0;
        for (String line : lines) {
            // 这里的 LoC 统计口径是“非空白物理行数”，
            // 不是只统计真正可执行的语句行。
            if (!line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int countBlankLines(String code) {
        if (code.isBlank()) {
            return 0;
        }
        String[] lines = code.split("\\R");
        int count = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }
    /**
     * 统计注释行数

     * 注意：多行注释（/* *\/）如果跨多行，每一行都算
     * 行注释（//）算一行
     */
    private int countCommentLines() {
        if (compilationUnit == null) {
            return 0;
        }
        int commentLines = 0;
        for (Object commentObject : compilationUnit.getCommentList()) {
            int start = 0;
            int end = 0;
            if (commentObject instanceof BlockComment) {
                BlockComment blockComment = (BlockComment) commentObject;
                start = compilationUnit.getLineNumber(blockComment.getStartPosition());
                end = compilationUnit.getLineNumber(blockComment.getStartPosition() + blockComment.getLength());
            } else if (commentObject instanceof org.eclipse.jdt.core.dom.LineComment) {
                org.eclipse.jdt.core.dom.LineComment lineComment =
                        (org.eclipse.jdt.core.dom.LineComment) commentObject;
                start = compilationUnit.getLineNumber(lineComment.getStartPosition());
                end = start;
            }
            commentLines += Math.max(end - start + 1, 0);
        }
        return commentLines;
    }

    private ClassMetrics currentClass() {
        return classStack.peek();
    }

    private MethodMetrics currentMethod() {
        return methodStack.peek();
    }
    /**
     * 分析结果记录（Java record类型）

     * record是Java 16引入的特性，相当于不可变的数据载体
     * 自动生成构造函数、getter、equals、hashCode、toString
     */
    public record AnalysisSummary(
            int loc,
            int blankLines,
            int commentLines,
            int classCount,
            int methodCount,
            int fieldCount,
            int wmc,
            int complexity,
            int cbo,
            int rfc,
            int lcom,
            int dit,
            int noc,
            int npm,
            int noa,
            int niv,
            int nvo,
            List<Map<String, Object>> classBreakdown
    ) {}
    /**
     * 类级别的度量数据容器
     */
    private static class ClassMetrics {
        private String name;
        private boolean isInterface;
        private int depthOfInheritance;
        private int numberOfChildren;
        private int declaredMethods;
        private int fieldCount;
        private int publicMethodCount;
        private int instanceVariableCount;
        private int instanceMethodCount;
        private int overrideCount;
        private int weightedMethodCount;
        private int totalComplexity;
        private int lcom;
        private final Set<String> coupledClasses = new LinkedHashSet<>();
        private final Set<String> responseMethods = new LinkedHashSet<>();
        private final Set<String> fieldNames = new LinkedHashSet<>();
        private final Map<String, Set<String>> fieldUsageByMethods = new LinkedHashMap<>();
        private final List<MethodMetrics> methodMetrics = new ArrayList<>();
    }
    /**
     * 方法级别的度量数据容器
     */
    private static class MethodMetrics {
        private String name;
        private String visibility;
        private int complexity;
        private int loc;
        private final Set<String> invokedMethods = new LinkedHashSet<>();

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("visibility", visibility);
            map.put("complexity", complexity);
            map.put("loc", loc);
            map.put("calls", new ArrayList<>(invokedMethods));
            return map;
        }
    }
}
