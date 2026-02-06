package io.github.sibmaks.mock4idea

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class MockingConfigurable : Configurable {
    private var panel: JPanel? = null
    private var table: JTable? = null
    private var tableModel: DefaultTableModel? = null

    override fun getDisplayName(): String = "Mockito Tweaks"

    override fun getHelpTopic(): String = "io.github.sibmaks.mock4idea.MockingConfigurable"

    override fun createComponent(): JComponent {
        if (panel != null) {
            return panel!!
        }

        tableModel = object : DefaultTableModel(arrayOf("Type (FQN)", "Mock expression"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        val tableComponent = JBTable(tableModel)
        table = tableComponent

        val toolbarPanel = ToolbarDecorator.createDecorator(tableComponent)
            .setAddAction { tableModel?.addRow(arrayOf("", "")) }
            .setRemoveAction { _ ->
                val selected = table?.selectedRows ?: IntArray(0)
                selected.sortedDescending().forEach { row -> tableModel?.removeRow(row) }
                if ((tableModel?.rowCount ?: 0) == 0) {
                    tableModel?.addRow(arrayOf("", ""))
                }
            }
            .createPanel()

        panel = JPanel(BorderLayout()).apply {
            add(
                JBLabel("Configure type-based mock values. Type must be fully-qualified class name."),
                BorderLayout.NORTH
            )
            add(toolbarPanel, BorderLayout.CENTER)
        }

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val current = readRulesFromTable()
        val saved = MockingSettingsService.getInstance().getRules()
        return current != saved
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val rules = readRulesFromTable()
        validateRules(rules)
        MockingSettingsService.getInstance().setRules(rules)
    }

    override fun reset() {
        val saved = MockingSettingsService.getInstance().getRules()
        val model = tableModel ?: return
        model.setRowCount(0)
        saved.forEach { rule -> model.addRow(arrayOf(rule.typeFqn, rule.expression)) }
        if (model.rowCount == 0) {
            model.addRow(arrayOf("", ""))
        }
    }

    override fun disposeUIResources() {
        table = null
        tableModel = null
        panel = null
    }

    private fun readRulesFromTable(): List<MockingSettingsService.Rule> {
        val model = tableModel ?: return emptyList()
        val rules = mutableListOf<MockingSettingsService.Rule>()
        for (row in 0 until model.rowCount) {
            val type = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
            val expression = (model.getValueAt(row, 1) as? String)?.trim().orEmpty()
            if (type.isEmpty() && expression.isEmpty()) {
                continue
            }
            rules.add(MockingSettingsService.Rule(type, expression))
        }
        return rules
    }

    private fun validateRules(rules: List<MockingSettingsService.Rule>) {
        val seen = mutableSetOf<String>()
        rules.forEach { rule ->
            if (rule.typeFqn.isBlank()) {
                throw ConfigurationException("Type cannot be blank.")
            }
            if (!rule.typeFqn.contains('.')) {
                throw ConfigurationException("Type must be fully-qualified: ${rule.typeFqn}")
            }
            if (rule.expression.isBlank()) {
                throw ConfigurationException("Mock expression cannot be blank for type: ${rule.typeFqn}")
            }
            if (!seen.add(rule.typeFqn)) {
                throw ConfigurationException("Duplicate type mapping for: ${rule.typeFqn}")
            }
        }
    }
}
