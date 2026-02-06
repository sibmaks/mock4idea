package io.github.sibmaks.mock4idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "Mock4IdeaMockingSettings",
    storages = [Storage("mock4idea.xml")]
)
class MockingSettingsService : PersistentStateComponent<MockingSettingsService.State> {
    data class Rule(
        var typeFqn: String = "",
        var expression: String = ""
    )

    data class State(
        var rules: MutableList<Rule> = defaultRules().toMutableList()
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        ensureDefaults()
    }

    fun getRules(): List<Rule> {
        ensureDefaults()
        return state.rules
    }

    fun setRules(rules: List<Rule>) {
        state.rules = rules.toMutableList()
    }

    fun resolveMockExpression(typeCanonical: String): String? {
        return getRules()
            .firstOrNull { it.typeFqn == typeCanonical }
            ?.expression
            ?.takeIf { it.isNotBlank() }
    }

    private fun ensureDefaults() {
        val existingTypes = state.rules.map { it.typeFqn }.toMutableSet()
        defaultRules().forEach { defaultRule ->
            if (defaultRule.typeFqn !in existingTypes) {
                state.rules.add(defaultRule)
                existingTypes.add(defaultRule.typeFqn)
            }
        }
    }

    companion object {
        val primitiveTypes: Set<String> = setOf(
            "boolean", "byte", "short", "int", "long", "float", "double", "char"
        )

        fun defaultRules(): List<Rule> {
            return listOf(
                Rule("java.lang.String", "UUID.randomUUID().toString()"),
                Rule("boolean", "false"),
                Rule("byte", "(byte) 0"),
                Rule("short", "(short) 0"),
                Rule("int", "0"),
                Rule("long", "0L"),
                Rule("float", "0.0f"),
                Rule("double", "0.0d"),
                Rule("char", "'\\u0000'")
            )
        }

        fun getInstance(): MockingSettingsService {
            return ApplicationManager.getApplication().service()
        }
    }
}
