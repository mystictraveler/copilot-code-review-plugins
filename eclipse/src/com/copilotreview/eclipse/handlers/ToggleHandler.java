package com.copilotreview.eclipse.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.handlers.HandlerUtil;

import com.copilotreview.eclipse.Activator;
import com.copilotreview.eclipse.preferences.PreferenceConstants;

public class ToggleHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean current = store.getBoolean(PreferenceConstants.ENABLED);
        store.setValue(PreferenceConstants.ENABLED, !current);

        MessageDialog.openInformation(
                HandlerUtil.getActiveShell(event),
                "Copilot Code Review",
                "Auto-review on save: " + (!current ? "Enabled" : "Disabled"));

        return null;
    }
}
