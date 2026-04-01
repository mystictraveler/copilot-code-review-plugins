package com.copilotreview.eclipse.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.ResourcesPlugin;

import com.copilotreview.eclipse.CopilotReviewService;

public class ClearMarkersHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        CopilotReviewService.getInstance().clearMarkers(
                ResourcesPlugin.getWorkspace().getRoot());
        return null;
    }
}
