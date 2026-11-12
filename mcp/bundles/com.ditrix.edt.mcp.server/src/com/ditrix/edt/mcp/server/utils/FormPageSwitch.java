/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IStructuredSelection;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.RenderOutcome;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.ShowElementTarget;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.SyncRender;

/**
 * A capture-scoped page switch in an open form editor (native render): brings the page holding an
 * element into view for one capture, then puts the editor back as far as EDT allows. Create it with
 * {@link #prepare} before anything is rendered, call {@link #show()} inside a {@code try}, capture with
 * no event pumping after it, and call {@link #restore()} in the {@code finally}. UI thread only.
 *
 * <p>Best effort by construction: EDT keeps no readable page state per Pages group (a render takes
 * only select-by-id ids). Restore explicitly selects the default pages of the target's enclosing
 * Pages groups, from inner to outer, then re-selects the editor's last domain selection, as
 * {@code FormWysiwygRepresentation.refreshSelection} does. Unrelated Pages groups are not reset.</p>
 */
public final class FormPageSwitch
{
    private static final String LAST_DOMAIN_SELECTION_FIELD = "lastDomainSelection"; //$NON-NLS-1$
    private static final int SWITCH_TIMEOUT_MS = 10000;

    private final Object representation;
    private final ShowElementTarget target;
    private final int[] previousSelection;
    private final int[] defaultPageIds;
    private final long deadline;
    private final Runnable pump;
    /** Whether a render of ours may have changed the editor (a throw counts). */
    private boolean touched;
    private boolean restoreDone;

    private FormPageSwitch(Object representation, ShowElementTarget target, int[] previousSelection,
        int[] defaultPageIds, long deadline, Runnable pump)
    {
        this.representation = representation;
        this.target = target;
        this.previousSelection = previousSelection;
        this.defaultPageIds = defaultPageIds;
        this.deadline = deadline;
        this.pump = pump;
    }

    /**
     * Records the editor's last domain selection, which the restore re-selects; renders nothing.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param target the element resolved by {@link EditorScreenshotHelper#resolveShowElement}
     * @return the switch, not yet shown
     */
    public static FormPageSwitch prepare(Object representation, ShowElementTarget target)
    {
        return prepare(representation, target, SWITCH_TIMEOUT_MS, EditorScreenshotHelper::processCurrentEvents);
    }

    /**
     * @param timeoutMs budget shared by the switch and the restore
     * @param pump drains the event loop while a render waits for the form model
     */
    static FormPageSwitch prepare(Object representation, ShowElementTarget target, int timeoutMs, Runnable pump)
    {
        return new FormPageSwitch(representation, target, selectedIds(representation),
            defaultPageIds(representation, target), System.currentTimeMillis() + timeoutMs, pump);
    }

    /**
     * Shows the page holding the element: every Pages group enclosing it switches to the page holding
     * it, and the selection frame is then dropped so it is not painted into the image. An element
     * outside every Pages group is visible on the current pages, so nothing is rendered for it.
     *
     * @return {@code null} when the page holding the element is in the render buffer, else why not
     */
    public String show()
    {
        if (!target.isOnPage())
        {
            return null;
        }
        boolean completed = false;
        try
        {
            String error = switchPages();
            completed = true;
            return error;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not show form element '" + target.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return "Element '" + target.getName() + "' could not be shown: the form editor threw while it " //$NON-NLS-1$ //$NON-NLS-2$
                + "was re-rendered; the EDT log has the cause."; //$NON-NLS-1$
        }
        finally
        {
            if (!completed)
            {
                touched = true;
            }
        }
    }

    /** @return whether a render of this switch may have changed the editor, so a restore is due */
    public boolean isTouched()
    {
        return touched;
    }

    private String switchPages()
    {
        // A full render selecting the element switches its Pages groups (update-only would reuse
        // HippoLayoutService's singleton layout cache, which may hold another editor's form). The
        // frame clear and the caller's capture follow with no event pumping: another editor rendering
        // in between rebinds the shared NativeRenderService window, which makes EDT turn the
        // update-only clear into a full render and bring the default pages back.
        SyncRender select = render(new int[] { target.getItemId() }, false);
        if (select.outcome != RenderOutcome.RENDERED)
        {
            if (select.outcome == RenderOutcome.NOT_READY)
            {
                return "The form model is still loading, so element '" + target.getName() //$NON-NLS-1$
                    + "' could not be shown. Try again."; //$NON-NLS-1$
            }
            return "Element '" + target.getName() + "' could not be shown: " + cause(select.outcome) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        // Update-only with an empty selection keeps the switched pages and drops the frame.
        SyncRender clear = EditorScreenshotHelper.renderRequestedFormSynchronously(representation, new int[0], true);
        if (clear.outcome != RenderOutcome.RENDERED)
        {
            return "Element '" + target.getName() + "' was shown, but the selection frame could not be " //$NON-NLS-1$ //$NON-NLS-2$
                + "cleared from the image (" + cause(clear.outcome) + "). Try again."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /** One synchronous render, retried (pumping) while the form model loads, in the shared budget. */
    private SyncRender render(int[] ids, boolean updateOnly)
    {
        SyncRender result = EditorScreenshotHelper.renderSyncUntilRendered(representation, ids, updateOnly,
            deadline, pump);
        if (result.outcome == RenderOutcome.RENDERED || result.outcome == RenderOutcome.FAILED)
        {
            touched = true;
        }
        return result;
    }

    /**
     * Puts the editor back, once and only if this switch may have changed it. A full NONE/update
     * render does not reset native page state: explicitly select each affected group's default page
     * from inner to outer, then reapply the original domain selection. With no original selection,
     * clear the frame update-only after the page selections. Unrelated groups retain their tabs.
     * Best effort: a user's page that is absent from the last domain selection is not observable.
     *
     * @return {@code null} when nothing needed restoring or the render ran, else the error
     */
    public String restore()
    {
        if (!touched || restoreDone)
        {
            return null;
        }
        restoreDone = true;
        String cause;
        try
        {
            SyncRender restore = restorePagesAndSelection();
            if (restore.outcome == RenderOutcome.RENDERED)
            {
                return null;
            }
            Activator.logWarning("Could not restore the form editor after a showElement capture: " //$NON-NLS-1$
                + restore.outcome);
            cause = cause(restore.outcome);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not restore the form editor after showing '" + target.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            cause = "the form editor threw while it was re-rendered; the EDT log has the cause"; //$NON-NLS-1$
        }
        return "The form editor could not be returned to the page it showed before the capture (" + cause //$NON-NLS-1$
            + "), so it may still show the page holding element '" + target.getName() //$NON-NLS-1$
            + "', and a later capture without showElement may return that page. Call again with " //$NON-NLS-1$
            + "showElement set to the desired page; refresh=true alone does not reset native page state."; //$NON-NLS-1$
    }

    private SyncRender restorePagesAndSelection()
    {
        for (int pageId : defaultPageIds)
        {
            SyncRender page = render(new int[] { pageId }, false);
            if (page.outcome != RenderOutcome.RENDERED)
            {
                return page;
            }
        }
        // Applying the old selection LAST lets it reopen its nondefault page, overriding the
        // explicit default selections. An empty selection only clears the frame; it cannot itself
        // switch a native page. Do not pump between the last page render and that frame clear.
        return previousSelection.length == 0
            ? EditorScreenshotHelper.renderRequestedFormSynchronously(representation, new int[0], true)
            : render(previousSelection, false);
    }

    /** Default page ids for ONLY the groups this target can switch, ordered inner to outer. */
    private static int[] defaultPageIds(Object representation, ShowElementTarget target)
    {
        if (!target.isOnPage())
        {
            return new int[0];
        }
        if (EditorScreenshotHelper.getRepresentationForm(representation) instanceof Form form)
        {
            for (EObject object : PersistedContents.descendants(form))
            {
                if (!(object instanceof FormItem item) || item.getId() != target.getItemId())
                {
                    continue;
                }
                List<Integer> pages = new ArrayList<>();
                for (EObject parent = item.eContainer(); parent != null; parent = parent.eContainer())
                {
                    if (parent instanceof FormGroup group && group.getType() == ManagedFormGroupType.PAGES
                        && !group.getItems().isEmpty())
                    {
                        pages.add(Integer.valueOf(group.getItems().get(0).getId()));
                    }
                }
                return pages.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        throw new IllegalStateException("The page hierarchy of element '" + target.getName() //$NON-NLS-1$
            + "' cannot be read, so the editor cannot safely restore its pages after the capture."); //$NON-NLS-1$
    }

    private static String cause(RenderOutcome outcome)
    {
        switch (outcome)
        {
        case NOT_READY:
            return "the form model is still loading"; //$NON-NLS-1$
        case FAILED:
            return "the platform's synchronous form render failed; the EDT log has the cause"; //$NON-NLS-1$
        default:
            return "the platform's synchronous form render could not be driven; the EDT log has the cause"; //$NON-NLS-1$
        }
    }

    /**
     * The ids of the editor's last domain selection, built the way
     * {@code FormWysiwygRepresentation.manageSelectionFromFormElement} builds them: {@code 0} for the
     * form itself, the item id for a form item.
     */
    static int[] selectedIds(Object representation)
    {
        Object selection;
        try
        {
            selection = ReflectionUtils.getFieldValue(representation, LAST_DOMAIN_SELECTION_FIELD);
        }
        catch (Exception e)
        {
            return new int[0];
        }
        if (!(selection instanceof IStructuredSelection structured))
        {
            return new int[0];
        }
        List<Integer> ids = new ArrayList<>();
        for (Object element : structured.toArray())
        {
            if (element instanceof Form)
            {
                ids.add(Integer.valueOf(0));
            }
            else if (element instanceof FormItem item)
            {
                ids.add(Integer.valueOf(item.getId()));
            }
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < result.length; i++)
        {
            result[i] = ids.get(i).intValue();
        }
        return result;
    }
}
