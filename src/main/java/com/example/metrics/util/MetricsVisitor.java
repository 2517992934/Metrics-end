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

public class MetricsVisitor extends ASTVisitor {
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

    @Override
    public boolean visit(CompilationUnit node) {
        this.compilationUnit = node;
        return true;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
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
            String superName = superType.toString();
            childClassCount.merge(superName, 1, Integer::sum);
            registerTypeCoupling(metrics, superType);
        }
        for (Object superInterface : node.superInterfaceTypes()) {
            registerTypeCoupling(metrics, (Type) superInterface);
        }
        return true;
    }

    @Override
    public void endVisit(TypeDeclaration node) {
        ClassMetrics metrics = classStack.pop();
        metrics.numberOfChildren = childClassCount.getOrDefault(metrics.name, 0);
        metrics.lcom = Math.max(metrics.declaredMethods == 0 ? 0 : metrics.declaredMethods - metrics.fieldUsageByMethods.size(), 0);
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        ClassMetrics currentClass = currentClass();
        if (currentClass == null) {
            return true;
        }

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

        if (Modifier.isPublic(node.getModifiers())) {
            currentClass.publicMethodCount++;
        }
        if (!Modifier.isStatic(node.getModifiers())) {
            currentClass.instanceMethodCount++;
        }
        for (Object modifier : node.modifiers()) {
            if (modifier instanceof Annotation) {
                Annotation annotation = (Annotation) modifier;
                if (isOverride(annotation)) {
                    currentClass.overrideCount++;
                }
            }
        }
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
        methodStack.pop();
    }

    @Override
    public boolean visit(SimpleType node) {
        registerTypeCoupling(currentClass(), node);
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        registerTypeCoupling(currentClass(), node.getType());
        MethodMetrics method = currentMethod();
        if (method != null) {
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
            if (currentClass.fieldNames.contains(identifier)) {
                currentClass.fieldUsageByMethods.computeIfAbsent(identifier, key -> new HashSet<>()).add(method.name);
            }
        }
        return true;
    }

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
        if (node.getOperator() == InfixExpression.Operator.CONDITIONAL_AND
                || node.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
            addDecisionPoint();
        }
        return true;
    }

    public AnalysisSummary buildSummary() {
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
        method.complexity++;
        currentClass.totalComplexity++;
    }

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

    private Set<String> extractDeclaredMethodNames(MethodDeclaration[] methods) {
        Set<String> methodNames = new LinkedHashSet<>();
        for (MethodDeclaration method : methods) {
            methodNames.add(method.getName().getIdentifier());
        }
        return methodNames;
    }

    private int countNodeLines(MethodDeclaration node) {
        int start = compilationUnit.getLineNumber(node.getStartPosition());
        int end = compilationUnit.getLineNumber(node.getStartPosition() + node.getLength());
        return Math.max(end - start + 1, 1);
    }

    private int countPhysicalLoc(String code) {
        if (code.isBlank()) {
            return 0;
        }
        String[] lines = code.split("\\R");
        int count = 0;
        for (String line : lines) {
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
