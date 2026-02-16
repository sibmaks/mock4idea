package io.github.sibmaks.mock4idea

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.codeInspection.util.IntentionName
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

class MockChainIntention : IntentionAction {
    private data class TransformData(
        val variable: PsiLocalVariable,
        val initCall: PsiMethodCallExpression,
        val callChain: List<PsiMethodCallExpression>
    )

    override fun getText(): @IntentionName String {
        return "Mockito: mock method chain (step-by-step stubs)"
    }

    override fun getFamilyName(): @IntentionFamilyName String {
        return "Mockito intentions"
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is PsiJavaFile) return false
        val targets = findTargetStatements(editor, file)
        return targets.isNotEmpty() && targets.all { resolveTransformData(it) != null }
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is PsiJavaFile) return
        val targets = findTargetStatements(editor, file)
        if (targets.isEmpty()) return

        WriteCommandAction.runWriteCommandAction(project, "Mockito Mock Chain Transform", null, {
            val factory = JavaPsiFacade.getElementFactory(project)
            ensureMockitoMethodImport(file, project)

            targets.sortedByDescending { it.textRange.startOffset }
                .forEach { statement -> transformStatement(project, factory, statement) }
        }, file)
    }

    override fun startInWriteAction(): Boolean = false

    override fun isDumbAware(): Boolean = true

    private fun findTargetStatements(editor: Editor, file: PsiFile): List<PsiStatement> {
        val selection = editor.selectionModel
        return if (selection.hasSelection()) {
            val start = selection.selectionStart
            val end = selection.selectionEnd
            val elementStart = file.findElementAt(start) ?: return emptyList()
            val elementEnd = file.findElementAt((end - 1).coerceAtLeast(start)) ?: return emptyList()

            val common = PsiTreeUtil.findCommonParent(elementStart, elementEnd) ?: return emptyList()
            PsiTreeUtil.findChildrenOfType(common, PsiStatement::class.java)
                .filter { it.textRange.startOffset >= start && it.textRange.endOffset <= end }
                .toList()
        } else {
            val offset = editor.caretModel.offset
            val element = file.findElementAt(offset) ?: return emptyList()
            val statement = PsiTreeUtil.getParentOfType(element, PsiStatement::class.java) ?: return emptyList()
            listOf(statement)
        }
    }

    private fun resolveTransformData(statement: PsiStatement): TransformData? {
        val declaration = statement as? PsiDeclarationStatement ?: return null
        val variables = declaration.declaredElements.filterIsInstance<PsiLocalVariable>()
        if (variables.size != 1) return null

        val variable = variables.single()
        if (variable.nameIdentifier == null) return null
        val init = variable.initializer as? PsiMethodCallExpression ?: return null
        if (isMockitoMockExpression(init)) return null

        val chain = collectCallChain(init)
        if (chain.size < 2) return null
        if (chain.dropLast(1).any { it.type == null }) return null
        return TransformData(variable, init, chain)
    }

    private fun transformStatement(project: Project, factory: PsiElementFactory, statement: PsiStatement) {
        val data = resolveTransformData(statement) ?: return
        val variable = data.variable
        val targetName = variable.name
        val targetType = (data.initCall.type ?: variable.type).canonicalText
        val nl = System.lineSeparator()

        val existingNames = collectExistingLocalNames(statement).toMutableSet()
        existingNames.add(targetName)

        val generatedStatements = mutableListOf<String>()
        var previousMockName: String? = null

        for (index in 0 until data.callChain.lastIndex) {
            val call = data.callChain[index]
            val callType = call.type ?: return
            val callTypeCanonical = callType.canonicalText
            val mockName = suggestChainVariableName(
                index = index,
                call = call,
                type = callType,
                targetName = targetName,
                previousMockName = previousMockName,
                usedNames = existingNames
            )
            existingNames.add(mockName)

            val callText = if (index == 0) {
                call.text
            } else {
                buildCallText(call, previousMockName ?: return)
            }
            generatedStatements += "$callTypeCanonical $mockName = ${resolveMockStatement(callTypeCanonical)};"
            generatedStatements += "when($callText)$nl.thenReturn($mockName);"

            previousMockName = mockName
        }

        val finalWhenCall = buildCallText(data.initCall, previousMockName ?: return)
        generatedStatements += "$targetType $targetName = ${resolveMockStatement(targetType)};"
        generatedStatements += "when($finalWhenCall)$nl.thenReturn($targetName);"

        val parent = statement.parent
        val inserted = generatedStatements.map { text ->
            parent.addBefore(factory.createStatementFromText(text, statement), statement)
        }
        statement.delete()

        val style = JavaCodeStyleManager.getInstance(project)
        inserted.forEach { style.shortenClassReferences(it) }
    }

    private fun collectCallChain(targetInit: PsiMethodCallExpression): List<PsiMethodCallExpression> {
        val reversed = mutableListOf<PsiMethodCallExpression>()
        var current: PsiMethodCallExpression? = targetInit
        while (current != null) {
            reversed += current
            current = current.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }
        val chain = reversed.asReversed()
        var from = 0
        while (chain.size - from > 1 && isStaticMethodCall(chain[from])) {
            from++
        }
        return chain.subList(from, chain.size)
    }

    private fun isStaticMethodCall(call: PsiMethodCallExpression): Boolean {
        val resolved = call.resolveMethod() ?: return false
        return resolved.hasModifierProperty(PsiModifier.STATIC)
    }

    private fun buildCallText(call: PsiMethodCallExpression, qualifierReplacement: String): String {
        val methodName = call.methodExpression.referenceName ?: return call.text
        return "$qualifierReplacement.$methodName${call.argumentList.text}"
    }

    private fun suggestChainVariableName(
        index: Int,
        call: PsiMethodCallExpression,
        type: PsiType,
        targetName: String,
        previousMockName: String?,
        usedNames: Set<String>
    ): String {
        val methodName = call.methodExpression.referenceName.orEmpty()
        val simpleType = (type as? PsiClassType)?.className.orEmpty()

        val base = when {
            index == 0 && simpleType == "Optional" -> targetName + "Optional"
            index == 0 -> {
                val qualifierRef = call.methodExpression.qualifierExpression as? PsiReferenceExpression
                val qualifierName = qualifierRef?.referenceName
                if (!qualifierName.isNullOrBlank() && methodName.isNotBlank()) {
                    qualifierName + capitalize(methodName)
                } else {
                    decapitalizeType(simpleType.ifBlank { "mockedValue" })
                }
            }
            else -> {
                val prev = previousMockName ?: "mockedValue"
                if (methodName.isBlank()) prev else prev + capitalize(methodName)
            }
        }

        if (base !in usedNames && base != targetName) return base
        var i = 1
        while (true) {
            val candidate = "$base$i"
            if (candidate !in usedNames && candidate != targetName) return candidate
            i++
        }
    }

    private fun decapitalizeType(typeName: String): String {
        if (typeName.isEmpty()) return "mockedValue"
        return typeName.replaceFirstChar { it.lowercaseChar() }
    }

    private fun capitalize(text: String): String {
        if (text.isEmpty()) return text
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    private fun collectExistingLocalNames(statement: PsiStatement): Set<String> {
        val codeBlock = PsiTreeUtil.getParentOfType(statement, PsiCodeBlock::class.java) ?: return emptySet()
        return PsiTreeUtil.findChildrenOfType(codeBlock, PsiLocalVariable::class.java)
            .mapNotNull { it.name }
            .toSet()
    }

    private fun isMockitoMockExpression(init: PsiExpression): Boolean {
        val call = init as? PsiMethodCallExpression ?: return false
        val methodExpression = call.methodExpression
        if (methodExpression.referenceName != "mock") return false

        val resolved = call.resolveMethod()
        if (resolved != null) {
            return resolved.containingClass?.qualifiedName == "org.mockito.Mockito"
        }

        val qualifier = methodExpression.qualifierExpression?.text
        return qualifier == null || qualifier == "Mockito" || qualifier == "org.mockito.Mockito"
    }

    private fun resolveMockStatement(typeCanonical: String): String {
        val configured = MockingSettingsService.getInstance().resolveMockExpression(typeCanonical)
        return configured ?: "mock()"
    }

    private fun hasStaticOnDemandImport(file: PsiJavaFile, classFqn: String): Boolean {
        val importList = file.importList ?: return false
        return importList.allImportStatements
            .filterIsInstance<PsiImportStaticStatement>()
            .any { it.isOnDemand && it.importReference?.qualifiedName == classFqn }
    }

    private fun hasStaticMemberImport(file: PsiJavaFile, classFqn: String, member: String): Boolean {
        val importList = file.importList ?: return false
        return importList.allImportStatements
            .filterIsInstance<PsiImportStaticStatement>()
            .any { !it.isOnDemand && it.importReference?.qualifiedName == "$classFqn.$member" }
    }

    private fun ensureMockitoMethodImport(file: PsiJavaFile, project: Project) {
        val importList = file.importList ?: return
        val fqName = "org.mockito.Mockito"
        if (hasStaticOnDemandImport(file, fqName)) return

        val facade = JavaPsiFacade.getInstance(project)
        val mockitoClass = facade.findClass(fqName, GlobalSearchScope.allScope(project)) ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        fun addMemberIfNeeded(member: String) {
            if (hasStaticMemberImport(file, fqName, member)) return
            importList.add(factory.createImportStaticStatement(mockitoClass, member))
        }

        addMemberIfNeeded("mock")
        addMemberIfNeeded("when")
    }
}
