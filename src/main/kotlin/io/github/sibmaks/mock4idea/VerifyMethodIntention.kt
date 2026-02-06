package io.github.sibmaks.mock4idea

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.codeInspection.util.IntentionName
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStaticStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

class VerifyMethodIntention : IntentionAction {
    override fun getText(): @IntentionName String {
        return "Mockito: wrap call with verify(...)"
    }

    override fun getFamilyName(): @IntentionFamilyName String {
        return "Mockito intentions"
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is PsiJavaFile) return false
        return resolveTargetCall(editor, file) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is PsiJavaFile) return
        val targetCall = resolveTargetCall(editor, file) ?: return

        val nl = System.lineSeparator()
        WriteCommandAction.runWriteCommandAction(project, "Mockito Verify Transform", null, {
            ensureVerifyMethodImport(file, project)

            val qualifier = targetCall.methodExpression.qualifierExpression?.text ?: return@runWriteCommandAction
            val methodName = targetCall.methodExpression.referenceName ?: return@runWriteCommandAction
            val argumentList = targetCall.argumentList.text
            val statement = PsiTreeUtil.getParentOfType(targetCall, PsiStatement::class.java) ?: return@runWriteCommandAction
            val factory = JavaPsiFacade.getElementFactory(project)
            val replaced = factory.createStatementFromText("verify($qualifier)$nl.$methodName$argumentList;", statement)
            val inserted = statement.replace(replaced)
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(inserted)
        }, file)
    }

    override fun startInWriteAction(): Boolean = false

    override fun isDumbAware(): Boolean = true

    private fun resolveTargetCall(editor: Editor, file: PsiJavaFile): PsiMethodCallExpression? {
        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val statement = PsiTreeUtil.getParentOfType(element, PsiExpressionStatement::class.java) ?: return null
        val call = statement.expression as? PsiMethodCallExpression ?: return null
        val qualifier = call.methodExpression.qualifierExpression ?: return null

        if (qualifier is PsiMethodCallExpression && qualifier.methodExpression.referenceName == "verify") {
            return null
        }

        return call
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

    private fun ensureVerifyMethodImport(
        file: PsiJavaFile,
        project: Project
    ) {
        val importList = file.importList ?: return
        val fqName = "org.mockito.Mockito"
        if (hasStaticOnDemandImport(file, fqName) || hasStaticMemberImport(file, fqName, "verify")) {
            return
        }

        val facade = JavaPsiFacade.getInstance(project)
        val mockitoClass = facade.findClass(fqName, GlobalSearchScope.allScope(project)) ?: return
        val factory = JavaPsiFacade.getElementFactory(project)
        val statement = factory.createImportStaticStatement(mockitoClass, "verify")
        importList.add(statement)
    }
}
