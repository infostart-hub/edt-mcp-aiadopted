/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;

/**
 * Tests for {@link FormPageSwitch}: the render sequence of a switch and of its restore, which renders
 * run (and which do not) when a step fails, and that no other editor can render between the switch
 * and the frame clear. The fake drives the same reflective surface of {@code FormWysiwygRepresentation}
 * the production code uses and records each render as EDT would run it; it says nothing about what
 * the native visualizer paints.
 */
public class FormPageSwitchTest
{
    private static final int SHORT_TIMEOUT_MS = 400;
    private static final int PAGE_ID = 7;
    private static final int PAGES_ID = 2;
    private static final int DEFAULT_PAGE_ID = 3;

    /** Stand-in for {@code CommandInterfaceMapping}. */
    public static final class FakeMapping
    {
    }

    /** Stand-in for {@code NativeRenderEvent} with the two factories the helper calls. */
    public static final class FakeEvent
    {
        int[] selectedIds;

        public static FakeEvent buildUpdateEvent()
        {
            return new FakeEvent();
        }

        public static FakeEvent buildSelectByIdEvent(long windowHandle, int[] ids)
        {
            FakeEvent event = new FakeEvent();
            event.selectedIds = ids;
            return event;
        }
    }

    /** Controller stand-in: not ready for the first {@code notReadyRoots} calls, then for {@code readyRoots}. */
    private static final class FakeController
    {
        int notReadyRoots;
        int readyRoots = Integer.MAX_VALUE;

        @SuppressWarnings("unused") // invoked reflectively (MappingController.getMappingRoot(Class))
        Object getMappingRoot(Class<?> mappingClass)
        {
            if (notReadyRoots > 0)
            {
                notReadyRoots--;
                return null;
            }
            if (readyRoots <= 0)
            {
                return null;
            }
            readyRoots--;
            return new FakeMapping();
        }
    }

    /** Stand-in for EDT's singleton {@code NativeRenderService}: the editor window it last rendered into. */
    private static final class FakeRenderService
    {
        Object window;
    }

    /** The reflectively driven surface of {@code FormWysiwygRepresentation}, recording every render. */
    private static final class FakeEditor
    {
        @SuppressWarnings("unused") // read reflectively
        final Form form = formWithPages();
        final FakeController controller = new FakeController();
        Object hippoSession = new Object();
        @SuppressWarnings("unused") // read reflectively (FormPageSwitch.selectedIds)
        IStructuredSelection lastDomainSelection;
        final List<String> renders = new ArrayList<>();
        int throwOnRender = -1;
        FakeRenderService renderService = new FakeRenderService();
        // The native renderer retains page state on NONE/update, including a full render.
        final Map<Integer, Integer> currentPages = new LinkedHashMap<>();
        int[] selectionFrame = new int[0];

        FakeEditor()
        {
            currentPages.put(PAGES_ID, DEFAULT_PAGE_ID);
        }

        private void applySelection(int[] ids)
        {
            if (ids == null)
            {
                return;
            }
            selectionFrame = ids.clone();
            for (int id : ids)
            {
                for (EObject item : PersistedContents.descendants(form))
                {
                    if (!(item instanceof FormItem formItem) || formItem.getId() != id)
                    {
                        continue;
                    }
                    EObject page = item;
                    for (EObject parent = page.eContainer(); parent != null; parent = page.eContainer())
                    {
                        if (parent instanceof FormGroup group && group.getType() == ManagedFormGroupType.PAGES)
                        {
                            currentPages.put(group.getId(), ((FormItem)page).getId());
                        }
                        page = parent;
                    }
                    break;
                }
            }
        }

        @SuppressWarnings("unused") // invoked reflectively (the synchronous render body)
        private void rebuildInternal(Object renderedForm, FakeMapping mapping, FakeEvent event, boolean updateOnly)
        {
            // EDT's rebuildInternal: setWindows() reports a window change when the shared render service
            // last rendered another editor, and an update-only render then runs as a full one.
            boolean windowChanged = renderService.window != this;
            renderService.window = this;
            int index = renders.size();
            renders.add((event.selectedIds == null ? "update" : "select" + Arrays.toString(event.selectedIds)) //$NON-NLS-1$ //$NON-NLS-2$
                + (updateOnly && !windowChanged ? "/updateOnly" : "/full")); //$NON-NLS-1$ //$NON-NLS-2$
            if (index == throwOnRender)
            {
                throw new IllegalStateException("render failed"); //$NON-NLS-1$
            }
            applySelection(event.selectedIds);
            hippoSession = new Object();
        }

        void renderOnItsOwn()
        {
            rebuildInternal(form, new FakeMapping(), FakeEvent.buildUpdateEvent(), false);
        }
    }

    private static FormGroup page(int id, String name)
    {
        FormGroup page = FormFactory.eINSTANCE.createFormGroup();
        page.setId(id);
        page.setName(name);
        page.setType(ManagedFormGroupType.PAGE);
        return page;
    }

    private static Form formWithPages()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup pages = FormFactory.eINSTANCE.createFormGroup();
        pages.setId(PAGES_ID);
        pages.setName("Pages"); //$NON-NLS-1$
        pages.setType(ManagedFormGroupType.PAGES);
        pages.getItems().add(page(DEFAULT_PAGE_ID, "PageMain")); //$NON-NLS-1$
        pages.getItems().add(page(PAGE_ID, "PageExtra")); //$NON-NLS-1$
        form.getItems().add(pages);
        return form;
    }

    @Test
    public void testRestoreReallyLeavesTheRequestedPageWithoutADomainSelection()
    {
        FakeEditor editor = new FakeEditor();
        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        assertNull(pageSwitch.show());
        assertEquals(Integer.valueOf(PAGE_ID), editor.currentPages.get(PAGES_ID));
        assertNull(pageSwitch.restore());
        assertEquals("a full NONE update retains the native page; restore must select its page", //$NON-NLS-1$
            Integer.valueOf(DEFAULT_PAGE_ID), editor.currentPages.get(PAGES_ID));
        assertEquals("an empty original selection must leave no frame", 0, editor.selectionFrame.length); //$NON-NLS-1$
    }

    @Test
    public void testRestoreDoesNotResetAnUnrelatedPagesGroup()
    {
        FakeEditor editor = new FakeEditor();
        FormGroup other = FormFactory.eINSTANCE.createFormGroup();
        other.setId(20);
        other.setType(ManagedFormGroupType.PAGES);
        other.getItems().add(page(21, "OtherDefault")); //$NON-NLS-1$
        other.getItems().add(page(22, "OtherSelected")); //$NON-NLS-1$
        editor.form.getItems().add(other);
        editor.currentPages.put(20, 22);
        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        assertNull(pageSwitch.show());
        assertNull(pageSwitch.restore());
        assertEquals(Integer.valueOf(DEFAULT_PAGE_ID), editor.currentPages.get(PAGES_ID));
        assertEquals("an unrelated native tab must not be reset", Integer.valueOf(22), //$NON-NLS-1$
            editor.currentPages.get(20));
    }

    @Test
    public void testRestoreReappliesASelectionInsideTheOriginalPage()
    {
        FakeEditor editor = new FakeEditor();
        FormGroup original = page(12, "OriginalNondefault"); //$NON-NLS-1$
        ((FormGroup)editor.form.getItems().get(0)).getItems().add(original);
        editor.currentPages.put(PAGES_ID, 12);
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(4);
        original.getItems().add(field);
        editor.lastDomainSelection = new StructuredSelection(field);
        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        assertNull(pageSwitch.show());
        assertNull(pageSwitch.restore());
        assertEquals("the original nondefault page must win over the reset", Integer.valueOf(12), //$NON-NLS-1$
            editor.currentPages.get(PAGES_ID));
        assertEquals("the original selection frame must be reapplied", "[4]", //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.toString(editor.selectionFrame));
    }

    @Test
    public void testNestedPagesRestoreInsideOutWithoutReopeningTheOuterRequestedPage()
    {
        FakeEditor editor = new FakeEditor();
        FormGroup outerExtra = (FormGroup)((FormGroup)editor.form.getItems().get(0)).getItems().get(1);
        FormGroup inner = FormFactory.eINSTANCE.createFormGroup();
        inner.setId(30);
        inner.setType(ManagedFormGroupType.PAGES);
        inner.getItems().add(page(31, "InnerDefault")); //$NON-NLS-1$
        inner.getItems().add(page(37, "InnerExtra")); //$NON-NLS-1$
        outerExtra.getItems().add(inner);
        editor.currentPages.put(30, 31);
        FormPageSwitch pageSwitch = FormPageSwitch.prepare(editor,
            EditorScreenshotHelper.ShowElementTarget.resolved("InnerExtra", 37, true), //$NON-NLS-1$
            SHORT_TIMEOUT_MS, NO_PUMP);
        assertNull(pageSwitch.show());
        assertEquals(Integer.valueOf(37), editor.currentPages.get(30));
        assertEquals(Integer.valueOf(PAGE_ID), editor.currentPages.get(PAGES_ID));

        assertNull(pageSwitch.restore());

        assertEquals("inner page must be restored before hiding its outer page", Integer.valueOf(31), //$NON-NLS-1$
            editor.currentPages.get(30));
        assertEquals("selecting the inner default last would reopen PageExtra", //$NON-NLS-1$
            Integer.valueOf(DEFAULT_PAGE_ID), editor.currentPages.get(PAGES_ID));
    }

    private static EditorScreenshotHelper.ShowElementTarget target()
    {
        return EditorScreenshotHelper.ShowElementTarget.resolved("Delivery", PAGE_ID, true); //$NON-NLS-1$
    }

    private static FormPageSwitch prepare(Object editor, Runnable pump)
    {
        return FormPageSwitch.prepare(editor, target(), SHORT_TIMEOUT_MS, pump);
    }

    private static final Runnable NO_PUMP = () -> {
        // nothing else runs on the event loop
    };

    @Test
    public void testSwitchSelectsClearsThenRestoresTheDefaultPages()
    {
        FakeEditor editor = new FakeEditor();
        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);

        assertNull(pageSwitch.show());
        assertTrue(pageSwitch.isTouched());
        assertEquals(List.of("select[7]/full", "select[]/updateOnly"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(pageSwitch.restore());
        // Nothing was selected before: explicitly select the default page, then clear its frame.
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[3]/full", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "select[]/updateOnly"), editor.renders); //$NON-NLS-1$
    }

    @Test
    public void testRestoreReappliesTheEditorsOwnSelection()
    {
        // The user clicked the 'Totals' tab: EDT stores the page as its last domain selection.
        FormGroup totals = FormFactory.eINSTANCE.createFormGroup();
        totals.setName("Totals"); //$NON-NLS-1$
        totals.setId(12);
        totals.setType(ManagedFormGroupType.PAGE);
        FakeEditor editor = new FakeEditor();
        ((FormGroup)editor.form.getItems().get(0)).getItems().add(totals);
        editor.currentPages.put(PAGES_ID, 12);
        editor.lastDomainSelection = new StructuredSelection(new Object[] { editor.form, totals, "not an entity" }); //$NON-NLS-1$

        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        pageSwitch.show();
        assertNull(pageSwitch.restore());

        // The way EDT re-applies it after its own renders: 0 for the form, the item id for the page.
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[3]/full", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "select[0, 12]/full"), editor.renders); //$NON-NLS-1$
        assertEquals(Integer.valueOf(12), editor.currentPages.get(PAGES_ID));
    }

    @Test
    public void testSelectedIdsSkipNonEntities()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(5);
        FakeEditor editor = new FakeEditor();
        editor.lastDomainSelection = new StructuredSelection(new Object[] { field, new Object() });
        assertEquals("[5]", Arrays.toString(FormPageSwitch.selectedIds(editor))); //$NON-NLS-1$

        editor.lastDomainSelection = null;
        assertEquals(0, FormPageSwitch.selectedIds(editor).length);
    }

    @Test
    public void testAnotherEditorCannotRenderBetweenTheSelectAndTheFrameClear()
    {
        // Editor B renders whenever the event loop is pumped. Had it rendered after A's select, the
        // shared render service would be bound to B's window and EDT would run A's update-only frame
        // clear as a full render, bringing A's default pages back.
        FakeRenderService shared = new FakeRenderService();
        FakeEditor a = new FakeEditor();
        FakeEditor b = new FakeEditor();
        a.renderService = shared;
        b.renderService = shared;
        a.controller.notReadyRoots = 1; // A's model is still loading once, so the loop waits (and pumps)
        int[] pumps = { 0 };
        Runnable pump = () -> {
            pumps[0]++;
            b.renderOnItsOwn();
        };

        assertNull(prepare(a, pump).show());

        assertEquals("the event loop runs only while A waits for its model", 1, pumps[0]); //$NON-NLS-1$
        assertEquals(List.of("update/full"), b.renders); //$NON-NLS-1$
        assertEquals("A's frame clear stays update-only", //$NON-NLS-1$
            List.of("select[7]/full", "select[]/updateOnly"), a.renders); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testElementOutsideEveryPagesGroupRendersNothing()
    {
        // The editor may show a non-default tab; a header outside every Pages group is visible on it, so
        // nothing is rendered and that tab stays.
        FakeEditor editor = new FakeEditor();
        FormPageSwitch pageSwitch = FormPageSwitch.prepare(editor,
            EditorScreenshotHelper.ShowElementTarget.resolved("Header", 9, false), SHORT_TIMEOUT_MS, NO_PUMP); //$NON-NLS-1$

        assertNull(pageSwitch.show());
        assertFalse(pageSwitch.isTouched());
        assertNull(pageSwitch.restore());
        assertTrue(editor.renders.isEmpty());
    }

    @Test
    public void testFailedFirstRenderSwitchesNothingAndRestoresNothing()
    {
        FakeEditor editor = new FakeEditor();
        editor.controller.readyRoots = 0; // the mapping root never becomes ready

        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        String error = pageSwitch.show();

        assertNotNull(error);
        assertTrue(error, error.contains("still loading")); //$NON-NLS-1$
        assertFalse(error, error.contains("-D")); //$NON-NLS-1$
        assertFalse(pageSwitch.isTouched());
        assertNull("nothing was switched, so there is nothing to restore", pageSwitch.restore()); //$NON-NLS-1$
        assertTrue("no render may run for a switch that never started", editor.renders.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testUnreachableHooksNameTheRealCause()
    {
        // No synchronous render hooks at all (another EDT build): no flag advice, nothing to restore.
        FormPageSwitch pageSwitch = prepare(new FakeNoHooksEditor(), NO_PUMP);
        String error = pageSwitch.show();

        assertTrue(error, error.contains("could not be driven")); //$NON-NLS-1$
        assertTrue(error, error.contains("EDT log")); //$NON-NLS-1$
        assertFalse(error, error.contains("nativeFormBufferedLayoutRender")); //$NON-NLS-1$
        assertFalse(pageSwitch.isTouched());
    }

    @Test
    public void testRenderThatThrowsIsStillRestored()
    {
        FakeEditor editor = new FakeEditor();
        editor.throwOnRender = 0;

        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        String error = pageSwitch.show();

        assertTrue(error, error.contains("failed")); //$NON-NLS-1$
        assertTrue("a throw inside the render may have switched the page", pageSwitch.isTouched()); //$NON-NLS-1$
        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[7]/full", "select[3]/full", "select[]/updateOnly"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            editor.renders);
    }

    @Test
    public void testFrameClearFailureIsReportedAndRestored()
    {
        FakeEditor editor = new FakeEditor();
        editor.throwOnRender = 1;

        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        String error = pageSwitch.show();

        assertTrue(error, error.contains("selection frame could not be cleared")); //$NON-NLS-1$
        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[3]/full", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "select[]/updateOnly"), editor.renders); //$NON-NLS-1$
    }

    @Test
    public void testAThrowWhileWaitingForTheModelIsRestored()
    {
        // The event loop pumped while the model loads throws: whether a render ran is unknown, so the
        // switch counts as made.
        FakeEditor editor = new FakeEditor();
        editor.controller.notReadyRoots = 1;
        boolean[] thrown = { false };
        Runnable pump = () -> {
            if (!thrown[0])
            {
                thrown[0] = true;
                throw new IllegalStateException("async runnable failed"); //$NON-NLS-1$
            }
        };
        FormPageSwitch pageSwitch = prepare(editor, pump);

        String error = pageSwitch.show();
        assertNotNull(error);
        assertTrue(error, error.contains("threw")); //$NON-NLS-1$
        assertTrue(pageSwitch.isTouched());
        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[3]/full", "select[]/updateOnly"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRestoreFailureIsReported()
    {
        FakeEditor editor = new FakeEditor();
        editor.controller.readyRoots = 2; // the switch renders, then the mapping root is gone

        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        assertNull(pageSwitch.show());

        String restoreError = pageSwitch.restore();
        assertNotNull(restoreError);
        assertTrue(restoreError, restoreError.contains("could not be returned to the page it showed before")); //$NON-NLS-1$
        assertTrue(restoreError, restoreError.contains("'Delivery'")); //$NON-NLS-1$
        assertTrue(restoreError, restoreError.contains("showElement set to the desired page")); //$NON-NLS-1$
        assertTrue(restoreError, restoreError.contains("refresh=true alone does not reset")); //$NON-NLS-1$
        assertNull("the restore runs once", pageSwitch.restore()); //$NON-NLS-1$
    }

    @Test
    public void testAThrowDuringTheRestoreIsReportedNotThrown()
    {
        FakeEditor editor = new FakeEditor();
        editor.controller.readyRoots = 2; // the restore waits for the model, pumping
        Runnable pump = () -> {
            throw new IllegalStateException("async runnable failed"); //$NON-NLS-1$
        };
        FormPageSwitch pageSwitch = prepare(editor, pump);
        assertNull(pageSwitch.show());

        String restoreError = pageSwitch.restore();
        assertNotNull(restoreError);
        assertTrue(restoreError, restoreError.contains("could not be returned to the page it showed before")); //$NON-NLS-1$
        assertTrue(restoreError, restoreError.contains("threw")); //$NON-NLS-1$
    }

    @Test
    public void testRestoreRunsOnce()
    {
        FakeEditor editor = new FakeEditor();
        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        pageSwitch.show();
        assertNull(pageSwitch.restore());
        assertNull(pageSwitch.restore());
        assertEquals(4, editor.renders.size());
    }

    @Test
    public void testRenderThatReturnsWithoutRenderingIsNotReady()
    {
        // rebuildInternal returns early (no actual form object yet) and keeps the old session: nothing
        // was rendered, so this is not a switch and not something to restore.
        FakeEarlyReturnEditor early = new FakeEarlyReturnEditor();

        FormPageSwitch pageSwitch = prepare(early, NO_PUMP);
        String error = pageSwitch.show();

        assertTrue(error, error.contains("still loading")); //$NON-NLS-1$
        assertFalse(pageSwitch.isTouched());
        assertTrue("the render was retried until the deadline", early.calls > 1); //$NON-NLS-1$
    }

    @Test
    public void testRestoreFrameClearFailureIsReported()
    {
        FakeEditor editor = new FakeEditor();
        editor.throwOnRender = 3;
        FormPageSwitch pageSwitch = prepare(editor, NO_PUMP);
        assertNull(pageSwitch.show());
        String error = pageSwitch.restore();
        assertNotNull(error);
        assertTrue(error, error.contains("could not be returned")); //$NON-NLS-1$
        assertEquals("the restored page is real even when clearing its frame fails", //$NON-NLS-1$
            Integer.valueOf(DEFAULT_PAGE_ID), editor.currentPages.get(PAGES_ID));
        assertNull("a failed restore is not retried implicitly", pageSwitch.restore()); //$NON-NLS-1$
    }

    /** A real form model with no reflective render hooks. */
    private static final class FakeNoHooksEditor
    {
        @SuppressWarnings("unused") // read reflectively
        final Form form = formWithPages();
    }

    /** An editor whose render returns early and never replaces the session. */
    private static final class FakeEarlyReturnEditor
    {
        @SuppressWarnings("unused") // read reflectively
        final Form form = formWithPages();
        @SuppressWarnings("unused") // read reflectively
        final FakeController controller = new FakeController();
        @SuppressWarnings("unused") // read reflectively
        final Object hippoSession = new Object();
        int calls;

        @SuppressWarnings("unused") // invoked reflectively
        private void rebuildInternal(Object renderedForm, FakeMapping mapping, FakeEvent event, boolean updateOnly)
        {
            calls++;
        }
    }
}
