package com.copilotreview.eclipse.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import com.copilotreview.eclipse.Activator;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setDefault(PreferenceConstants.ENABLED, true);
        store.setDefault(PreferenceConstants.DEBOUNCE_MS, 2000);
        store.setDefault(PreferenceConstants.EXCLUDED_EXTENSIONS, "txt");
        store.setDefault(PreferenceConstants.SCOPE, "file");
    }
}
