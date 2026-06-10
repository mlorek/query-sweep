package io.github.mlorek.querysweep;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

public class OpenQuerySweepHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window != null) {
            IWorkbenchPage page = window.getActivePage();
            if (page != null) {
                try {
                    page.showView(QuerySweepView.VIEW_ID);
                } catch (PartInitException e) {
                    throw new ExecutionException("Failed to open QuerySweep view", e);
                }
            }
        }
        return null;
    }
}
