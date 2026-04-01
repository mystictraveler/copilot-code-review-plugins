package com.copilotreview.eclipse;

import org.eclipse.ui.IStartup;

public class StartupHook implements IStartup {

    @Override
    public void earlyStartup() {
        SaveListener.register();
    }
}
