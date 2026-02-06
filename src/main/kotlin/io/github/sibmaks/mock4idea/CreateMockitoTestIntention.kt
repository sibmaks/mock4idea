package io.github.sibmaks.mock4idea

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.codeInspection.util.IntentionName
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil

class CreateMockitoTestIntention : IntentionAction {
    override fun getText(): @IntentionName String {
        return "Mockito: create test class (@Mock + @InjectMocks)"
    }

    override fun getFamilyName(): @IntentionFamilyName String {
        return "Mockito intentions"
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is PsiJavaFile) return false
        return resolveContextClass(editor, file) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is PsiJavaFile) return
        val targetClass = resolveContextClass(editor, file) ?: return
        val dependencies = resolveDependencies(targetClass) ?: return

        val sourceFile = targetClass.containingFile as? PsiJavaFile ?: return
        val selectedTestRoot = chooseTestRootDirectory(project, targetClass) ?: return

        val testClassName = "${targetClass.name}Test"
        val packageName = sourceFile.packageName
        val subjectName = resolveSubjectVariableName(targetClass.name ?: "subject")
        val classType = targetClass.qualifiedName ?: (targetClass.name ?: "Object")

        val mockFields = dependencies.joinToString("\n") { param ->
            val typeText = resolveTypeText(param.type)
            val name = param.name ?: "dependency"
            "    @Mock\n    private $typeText $name;"
        }

        val testContent = buildString {
            if (packageName.isNotBlank()) {
                append("package $packageName;\n\n")
            }
            append("import org.junit.jupiter.api.extension.ExtendWith;\n")
            append("import org.mockito.InjectMocks;\n")
            append("import org.mockito.Mock;\n")
            append("import org.mockito.junit.jupiter.MockitoExtension;\n\n")
            append("@ExtendWith(MockitoExtension.class)\n")
            append("class $testClassName {\n")
            if (mockFields.isNotBlank()) {
                append(mockFields)
                append("\n\n")
            }
            append("    @InjectMocks\n")
            append("    private $classType $subjectName;\n")
            append("}\n")
        }

        WriteCommandAction.runWriteCommandAction(project, "Create Mockito Test Class", null, {
            val targetDirectory = ensurePackageDirectory(selectedTestRoot, packageName)
            if (targetDirectory.findFile("$testClassName.java") != null) {
                return@runWriteCommandAction
            }

            val psiFileFactory = PsiFileFactory.getInstance(project)
            val created = psiFileFactory.createFileFromText(
                "$testClassName.java",
                sourceFile.language,
                testContent
            ) as? PsiJavaFile ?: return@runWriteCommandAction

            val added = targetDirectory.add(created) as? PsiJavaFile ?: return@runWriteCommandAction
            CodeStyleManager.getInstance(project).reformat(added)
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
        }, file)
    }

    override fun startInWriteAction(): Boolean = false

    override fun isDumbAware(): Boolean = true

    private fun resolveContextClass(editor: Editor, file: PsiJavaFile): PsiClass? {
        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val constructor = findConstructor(element)
        if (constructor != null) {
            val clazz = constructor.containingClass ?: return null
            return if (hasSingleConstructor(clazz)) clazz else null
        }

        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return null
        if (clazz.containingClass != null) return null
        return if (hasSingleConstructor(clazz) || hasRequiredArgsConstructor(clazz)) clazz else null
    }

    private fun findConstructor(element: PsiElement): PsiMethod? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        return if (method.isConstructor) method else null
    }

    private fun hasSingleConstructor(clazz: PsiClass): Boolean {
        val constructors = clazz.constructors
        return constructors.size == 1
    }

    private fun hasRequiredArgsConstructor(clazz: PsiClass): Boolean {
        return clazz.modifierList?.annotations?.any {
            val qn = it.qualifiedName ?: return@any false
            qn == "lombok.RequiredArgsConstructor" || qn == "RequiredArgsConstructor"
        } == true
    }

    private fun resolveDependencies(clazz: PsiClass): List<PsiParameter>? {
        val constructors = clazz.constructors
        if (constructors.size == 1) {
            return constructors.single().parameterList.parameters.toList()
        }

        if (!hasRequiredArgsConstructor(clazz)) {
            return null
        }

        val project = clazz.project
        val factory = JavaPsiFacade.getElementFactory(project)
        return clazz.fields
            .asSequence()
            .filter { field ->
                val hasFinal = field.hasModifierProperty(PsiModifier.FINAL)
                val hasNonNull = field.annotations.any { ann ->
                    val qn = ann.qualifiedName ?: return@any false
                    qn == "lombok.NonNull" || qn == "NonNull"
                }
                (hasFinal || hasNonNull) && field.initializer == null
            }
            .mapNotNull { field ->
                val name = field.name
                factory.createParameter(name, field.type)
            }
            .toList()
    }

    private fun resolveTypeText(type: PsiType): String {
        return type.canonicalText
    }

    private fun resolveSubjectVariableName(className: String): String {
        val words = Regex("[A-Z]+(?=$|[A-Z][a-z])|[A-Z]?[a-z0-9]+")
            .findAll(className)
            .map { it.value }
            .toList()

        val last = words.lastOrNull() ?: className
        return last.replaceFirstChar { ch -> ch.lowercaseChar() }
    }

    private fun chooseTestRootDirectory(project: Project, clazz: PsiClass): PsiDirectory? {
        val module = ModuleUtilCore.findModuleForPsiElement(clazz)
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val psiManager = PsiManager.getInstance(project)

        val rootFiles = mutableListOf<VirtualFile>()
        if (module != null) {
            rootFiles += ModuleRootManager.getInstance(module).sourceRoots
                .filter { fileIndex.isInTestSourceContent(it) }
        }
        if (rootFiles.isEmpty()) {
            rootFiles += ProjectRootManager.getInstance(project).contentSourceRoots
                .filter { fileIndex.isInTestSourceContent(it) }
        }

        val directories = rootFiles
            .distinct()
            .mapNotNull { root -> psiManager.findDirectory(root) }
            .sortedBy { it.virtualFile.path }

        if (directories.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "No test source roots found in project/module.",
                "Create Mockito Test Class"
            )
            return null
        }
        if (directories.size == 1) {
            return directories.first()
        }

        val options = directories.map { it.virtualFile.path }.toTypedArray()
        val selected = Messages.showChooseDialog(
            project,
            "Select test source root for the generated test class:",
            "Create Mockito Test Class",
            null,
            options,
            options.first()
        )
        if (selected < 0) return null
        return directories[selected]
    }

    private fun ensurePackageDirectory(root: PsiDirectory, packageName: String): PsiDirectory {
        if (packageName.isBlank()) return root
        var current = root
        packageName.split('.').forEach { segment ->
            current = current.findSubdirectory(segment) ?: current.createSubdirectory(segment)
        }
        return current
    }
}
