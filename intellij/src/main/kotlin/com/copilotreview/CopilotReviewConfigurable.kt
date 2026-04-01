package com.copilotreview

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.*

class CopilotReviewConfigurable(private val project: Project) : Configurable {

    private var enabledCheckbox: JCheckBox? = null
    private var debounceField: JTextField? = null
    private var excludedField: JTextField? = null
    private var scopeCombo: JComboBox<String>? = null
    private var modelCombo: JComboBox<String>? = null

    override fun getDisplayName(): String = "Copilot Code Review"

    override fun createComponent(): JComponent {
        val settings = CopilotReviewSettings.getInstance(project).state

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        enabledCheckbox = JCheckBox("Enable auto-review on save", settings.enabled)
        panel.add(enabledCheckbox)
        panel.add(Box.createVerticalStrut(10))

        val debouncePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        debouncePanel.add(JLabel("Debounce delay (ms): "))
        debounceField = JTextField(settings.debounceMs.toString(), 10)
        debouncePanel.add(debounceField)
        debouncePanel.add(Box.createHorizontalGlue())
        panel.add(debouncePanel)
        panel.add(Box.createVerticalStrut(10))

        val excludedPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        excludedPanel.add(JLabel("Excluded extensions (comma-separated): "))
        excludedField = JTextField(settings.excludedExtensions, 20)
        excludedPanel.add(excludedField)
        excludedPanel.add(Box.createHorizontalGlue())
        panel.add(excludedPanel)
        panel.add(Box.createVerticalStrut(10))

        val scopePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        scopePanel.add(JLabel("Review scope on save: "))
        scopeCombo = JComboBox(arrayOf("file", "project"))
        scopeCombo!!.selectedItem = settings.scope
        scopePanel.add(scopeCombo)
        scopePanel.add(Box.createHorizontalGlue())
        panel.add(scopePanel)
        panel.add(Box.createVerticalStrut(10))

        val modelPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        modelPanel.add(JLabel("Model: "))
        modelCombo = JComboBox(arrayOf(
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001",
            "gpt-4o",
            "gpt-4o-mini",
            "o3-mini"
        ))
        modelCombo!!.selectedItem = settings.model
        modelPanel.add(modelCombo)
        modelPanel.add(Box.createHorizontalGlue())
        panel.add(modelPanel)

        panel.add(Box.createVerticalGlue())
        return panel
    }

    override fun isModified(): Boolean {
        val settings = CopilotReviewSettings.getInstance(project).state
        return enabledCheckbox?.isSelected != settings.enabled
                || debounceField?.text != settings.debounceMs.toString()
                || excludedField?.text != settings.excludedExtensions
                || scopeCombo?.selectedItem != settings.scope
                || modelCombo?.selectedItem != settings.model
    }

    override fun apply() {
        val settings = CopilotReviewSettings.getInstance(project)
        settings.loadState(CopilotReviewSettings.State(
            enabled = enabledCheckbox?.isSelected ?: true,
            debounceMs = debounceField?.text?.toLongOrNull() ?: 2000,
            excludedExtensions = excludedField?.text ?: "txt",
            scope = scopeCombo?.selectedItem as? String ?: "file",
            model = modelCombo?.selectedItem as? String ?: "claude-opus-4-6"
        ))
    }

    override fun reset() {
        val settings = CopilotReviewSettings.getInstance(project).state
        enabledCheckbox?.isSelected = settings.enabled
        debounceField?.text = settings.debounceMs.toString()
        excludedField?.text = settings.excludedExtensions
        scopeCombo?.selectedItem = settings.scope
        modelCombo?.selectedItem = settings.model
    }
}
