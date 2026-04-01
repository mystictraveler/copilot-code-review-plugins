package com.copilotreview.eclipse.handlers;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;

import com.copilotreview.eclipse.CopilotReviewService;
import com.copilotreview.eclipse.CopilotReviewView;
import com.copilotreview.eclipse.ReviewIssue;

public class ReviewNowHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(ReviewNowHandler.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorInput input = HandlerUtil.getActiveEditorInput(event);
        if (!(input instanceof IFileEditorInput fileInput)) return null;

        IFile file = fileInput.getFile();
        new Thread(() -> {
            try {
                List<ReviewIssue> issues = CopilotReviewService.getInstance().reviewFile(file);
                LOG.info("[CopilotReview] Reviewed " + file.getName() + ": " + issues.size() + " issue(s)");
                org.eclipse.swt.widgets.Display.getDefault().asyncExec(() ->
                        CopilotReviewView.updateResults(file.getName(), file.getFullPath().toString(), issues));
            } catch (Exception e) {
                LOG.warn("[CopilotReview] Review failed: " + e.getMessage());
            }
        }).start();

        return null;
    }
}
