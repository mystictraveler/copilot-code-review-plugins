package com.copilotreview.eclipse;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.copilotreview.eclipse.preferences.PreferenceConstants;

public class SaveListener implements IResourceChangeListener {

    private static final ILog LOG = Platform.getLog(SaveListener.class);
    private final Map<String, TimerTask> debounceTimers = new HashMap<>();
    private final Timer timer = new Timer("CopilotReviewDebounce", true);

    public static void register() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
                new SaveListener(), IResourceChangeEvent.POST_CHANGE);
        LOG.info("[CopilotReview] Save listener registered");
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        if (!store.getBoolean(PreferenceConstants.ENABLED)) return;

        IResourceDelta delta = event.getDelta();
        if (delta == null) return;

        try {
            delta.accept(new IResourceDeltaVisitor() {
                @Override
                public boolean visit(IResourceDelta d) throws CoreException {
                    if ((d.getFlags() & IResourceDelta.CONTENT) == 0) return true;
                    if (d.getResource() instanceof IFile file) {
                        handleFileSave(file, store);
                    }
                    return true;
                }
            });
        } catch (CoreException e) {
            LOG.warn("Error processing resource change: " + e.getMessage());
        }
    }

    private void handleFileSave(IFile file, IPreferenceStore store) {
        String ext = file.getFileExtension();
        if (ext == null) return;

        List<String> excluded = Arrays.asList(
                store.getString(PreferenceConstants.EXCLUDED_EXTENSIONS).split(","));
        if (excluded.stream().map(String::trim).anyMatch(e -> e.equalsIgnoreCase(ext))) return;

        String scope = store.getString(PreferenceConstants.SCOPE);
        long debounceMs = store.getInt(PreferenceConstants.DEBOUNCE_MS);

        if ("project".equals(scope)) {
            String key = "__project__";
            cancelDebounce(key);
            debounceTimers.put(key, scheduleTask(key, debounceMs, () -> reviewAllOpenFiles(excluded)));
        } else {
            String key = file.getFullPath().toString();
            cancelDebounce(key);
            debounceTimers.put(key, scheduleTask(key, debounceMs, () -> reviewSingleFile(file)));
        }
    }

    private void cancelDebounce(String key) {
        TimerTask existing = debounceTimers.remove(key);
        if (existing != null) existing.cancel();
    }

    private TimerTask scheduleTask(String key, long delayMs, Runnable action) {
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                debounceTimers.remove(key);
                action.run();
            }
        };
        timer.schedule(task, delayMs);
        return task;
    }

    private void reviewSingleFile(IFile file) {
        LOG.info("[CopilotReview] Reviewing: " + file.getName());
        try {
            CopilotReviewService service = CopilotReviewService.getInstance();
            if (!service.isGitProject(file)) return;

            List<ReviewIssue> issues = service.reviewFile(file);
            LOG.info("[CopilotReview] Found " + issues.size() + " issue(s) in " + file.getName());

            Display.getDefault().asyncExec(() ->
                    CopilotReviewView.updateResults(file.getName(), file.getFullPath().toString(), issues));
        } catch (Exception e) {
            LOG.warn("[CopilotReview] Review failed for " + file.getName() + ": " + e.getMessage());
        }
    }

    private void reviewAllOpenFiles(List<String> excluded) {
        Display.getDefault().syncExec(() -> {
            try {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null) return;
                IWorkbenchPage page = window.getActivePage();
                if (page == null) return;

                for (IEditorReference ref : page.getEditorReferences()) {
                    IEditorInput input = ref.getEditorInput();
                    if (input instanceof IFileEditorInput fileInput) {
                        IFile file = fileInput.getFile();
                        String ext = file.getFileExtension();
                        if (ext != null && excluded.stream().map(String::trim).noneMatch(e -> e.equalsIgnoreCase(ext))) {
                            new Thread(() -> reviewSingleFile(file)).start();
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[CopilotReview] Error reviewing open files: " + e.getMessage());
            }
        });
    }
}
