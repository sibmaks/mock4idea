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

/**
 * @author drobyshev-ma
 * @since 0.0.1
 */
class MockStatementIntention : IntentionAction {
    override fun getText(): @IntentionName String {
        return "Mockito: stub selected declarations (mock + when/thenReturn)"
    }

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?
    ): Boolean {
        if (editor == null || file !is PsiJavaFile) {
            return false
        }
        val targets = findTargetStatements(editor, file)
        return targets.isNotEmpty() && targets.all { canTransform(it) }
    }

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?
    ) {
        if (editor == null || file !is PsiJavaFile) {
            return
        }

        val targets = findTargetStatements(editor, file)
        if (targets.isEmpty()) {
            return
        }

        WriteCommandAction.runWriteCommandAction(project, "Mockito Stub Transform", null, {
            val factory = JavaPsiFacade.getElementFactory(project)

            ensureImport("java.util.UUID", file, project)
            ensureMockitoMethodImport(file, project)

            targets.sortedByDescending { it.textRange.startOffset }
                .forEach { statement -> transformStatement(project, factory, statement) }
        }, file)
    }

    override fun startInWriteAction(): Boolean = false

    override fun isDumbAware(): Boolean = true

    override fun getFamilyName(): @IntentionFamilyName String {
        return "Mockito intentions"
    }

    private fun findTargetStatements(editor: Editor, file: PsiFile): List<PsiStatement> {
        val selection = editor.selectionModel
        return if (selection.hasSelection()) {
            val start = selection.selectionStart
            val end = selection.selectionEnd
            val elementStart = file.findElementAt(start) ?: return emptyList()
            val elementEnd = file.findElementAt((end - 1).coerceAtLeast(start)) ?: return emptyList()

            val common = PsiTreeUtil.findCommonParent(elementStart, elementEnd) ?: return emptyList()
            PsiTreeUtil.findChildrenOfType(common, PsiStatement::class.java)
                .filter {
                    it.textRange.startOffset >= start && it.textRange.endOffset <= end
                }
                .toList()
        } else {
            val offset = editor.caretModel.offset
            val element = file.findElementAt(offset) ?: return emptyList()
            val statement = PsiTreeUtil.getParentOfType(element, PsiStatement::class.java) ?: return emptyList()
            listOf(statement)
        }
    }

    private fun canTransform(statement: PsiStatement): Boolean {
        val declaration = statement as? PsiDeclarationStatement ?: return false
        val locals = declaration.declaredElements.filterIsInstance<PsiLocalVariable>()
        if (locals.size != 1) return false
        val v = locals.first()

        val init = v.initializer ?: return false

        if (v.nameIdentifier == null) {
            return false
        }
        if (init.text.isBlank()) return false
        if (isMockitoMockExpression(init)) return false
        return true
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

    private fun transformStatement(
        project: Project,
        factory: PsiElementFactory,
        statement: PsiStatement
    ) {
        val declaration = statement as PsiDeclarationStatement
        val v = declaration.declaredElements.filterIsInstance<PsiLocalVariable>().single()
        val init = v.initializer ?: return

        val varName = v.name
        val typeCanonical = resolveTypeCanonicalText(init, v.type)

        val mockStatementText = "$typeCanonical $varName = ${resolveMockStatement(typeCanonical)};"
        val whenStatementText = "when(${init.text})\n.thenReturn($varName);\n"

        val mockStatement = factory.createStatementFromText(mockStatementText, statement)
        val whenStatement = factory.createStatementFromText(whenStatementText, statement)

        val parent = statement.parent
        val insertedMock = parent.addBefore(mockStatement, statement)
        val insertedWhen = parent.addBefore(whenStatement, statement)
        statement.delete()

        val style = JavaCodeStyleManager.getInstance(project)
        style.shortenClassReferences(insertedMock)
        style.shortenClassReferences(insertedWhen)
    }

    private fun resolveMockStatement(
        typeCanonical: String
    ): String {
        return if (typeCanonical == "java.lang.String") {
            "UUID.randomUUID().toString()"
        } else {
            "mock()"
        }
    }

    private fun resolveTypeCanonicalText(
        init: PsiExpression,
        fallback: PsiType
    ): String {
        val type = init.type ?: fallback
        return type.canonicalText
    }

    private fun hasStaticOnDemandImport(
        file: PsiJavaFile,
        classFqn: String
    ): Boolean {
        val importList = file.importList ?: return false
        return importList.allImportStatements
            .filterIsInstance<PsiImportStaticStatement>()
            .any { it.isOnDemand && it.importReference?.qualifiedName == classFqn }
    }

    private fun hasOnDemandImport(
        file: PsiJavaFile,
        classFqn: String
    ): Boolean {
        val importList = file.importList ?: return false
        return importList.allImportStatements
            .filterIsInstance<PsiImportStatement>()
            .any { it.isOnDemand && it.importReference?.qualifiedName == classFqn }
    }

    private fun hasStaticMemberImport(
        file: PsiJavaFile,
        classFqn: String,
        member: String
    ): Boolean {
        val importList = file.importList ?: return false
        return importList.allImportStatements
            .filterIsInstance<PsiImportStaticStatement>()
            .any { !it.isOnDemand && it.importReference?.qualifiedName == "$classFqn.$member" }
    }

    private fun ensureMockitoMethodImport(
        file: PsiJavaFile,
        project: Project
    ) {
        val importList = file.importList ?: return

        val fqName = "org.mockito.Mockito"
        if (hasStaticOnDemandImport(file, fqName)) {
            return
        }

        val facade = JavaPsiFacade.getInstance(project)
        val mockitoClass = facade.findClass(fqName, GlobalSearchScope.allScope(project)) ?: return

        val factory = JavaPsiFacade.getElementFactory(project)

        fun addMemberIfNeeded(member: String) {
            if (hasStaticMemberImport(file, fqName, member)) {
                return
            }

            val statement = factory.createImportStaticStatement(mockitoClass, member)
            importList.add(statement)
        }

        addMemberIfNeeded("mock")
        addMemberIfNeeded("when")
    }

    private fun ensureImport(
        fqName: String,
        file: PsiJavaFile,
        project: Project
    ) {
        val importList = file.importList ?: return

        if (hasOnDemandImport(file, fqName)) {
            return
        }

        val facade = JavaPsiFacade.getInstance(project)
        val mockitoClass = facade.findClass(fqName, GlobalSearchScope.allScope(project)) ?: return

        val factory = JavaPsiFacade.getElementFactory(project)

        val statement = factory.createImportStatement(mockitoClass)
        importList.add(statement)
    }
}
