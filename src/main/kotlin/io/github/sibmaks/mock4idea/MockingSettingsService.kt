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
        var rules: MutableList<Rule> = mutableListOf(
            Rule("java.lang.String", "UUID.randomUUID().toString()")
        )
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
        if (state.rules.isEmpty()) {
            state.rules.add(Rule("java.lang.String", "UUID.randomUUID().toString()"))
        }
    }

    companion object {
        fun getInstance(): MockingSettingsService {
            return ApplicationManager.getApplication().service()
        }
    }
}
