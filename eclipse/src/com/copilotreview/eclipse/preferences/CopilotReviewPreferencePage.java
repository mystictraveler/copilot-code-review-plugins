package com.copilotreview.eclipse.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import com.copilotreview.eclipse.Activator;

public class CopilotReviewPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public CopilotReviewPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Copilot Code Review on Save settings");
    }

    @Override
    public void createFieldEditors() {
        addField(new BooleanFieldEditor(
                PreferenceConstants.ENABLED,
                "Enable auto-review on save",
                getFieldEditorParent()));

        addField(new IntegerFieldEditor(
                PreferenceConstants.DEBOUNCE_MS,
                "Debounce delay (ms):",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                PreferenceConstants.EXCLUDED_EXTENSIONS,
                "Excluded extensions (comma-separated):",
                getFieldEditorParent()));

        addField(new ComboFieldEditor(
                PreferenceConstants.SCOPE,
                "Review scope on save:",
                new String[][] {
                    { "Current file", "file" },
                    { "All open files", "project" }
                },
                getFieldEditorParent()));
    }

    @Override
    public void init(IWorkbench workbench) {
    }
}
