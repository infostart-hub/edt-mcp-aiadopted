/**
 * Pinned installation update-policy tests.
 * Licensed under AGPL-3.0-or-later.
 */
package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.preferences.PreferenceInitializer;

/** Verifies that a pinned installation never schedules an unsolicited channel check. */
public class UpdateCheckerPolicyTest
{
    @Test
    public void freshInstallationDoesNotScheduleAnUpdateCheck() throws Exception
    {
        checkSchedule(null, false);
    }

    @Test
    public void explicitOptInStillSchedulesACheck() throws Exception
    {
        checkSchedule(PreferenceConstants.UPDATE_CHECK_HOURLY, true);
    }

    @Test
    public void disablingChecksStopsAnAlreadyScheduledCheck() throws Exception
    {
        UpdateChecker checker = UpdateChecker.getInstance();
        PreferenceStore store = new PreferenceStore();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        plugin.set(null, activator);
        try
        {
            store.setValue(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL,
                PreferenceConstants.UPDATE_CHECK_HOURLY);
            checker.scheduleCheck();
            assertNotNull(scheduler(checker));
            store.setValue(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL,
                PreferenceConstants.UPDATE_CHECK_NEVER);
            checker.scheduleCheck();
            assertNull("never must cancel a previously scheduled foreign-channel check", scheduler(checker));
        }
        finally
        {
            checker.stopScheduler();
            plugin.set(null, previous);
        }
    }

    private void checkSchedule(String preference, boolean expected) throws Exception
    {
        UpdateChecker checker = UpdateChecker.getInstance();
        checker.stopScheduler();
        PreferenceStore store = new PreferenceStore();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        plugin.set(null, activator);
        try
        {
            new PreferenceInitializer().initializeDefaultPreferences();
            if (preference != null)
            {
                store.setValue(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL, preference);
            }
            checker.scheduleCheck();
            if (expected)
            {
                assertNotNull("an explicit opt-in must remain available", scheduler(checker));
            }
            else
            {
                assertNull("a fresh pinned installation must not schedule an upstream check", scheduler(checker));
            }
        }
        finally
        {
            checker.stopScheduler();
            plugin.set(null, previous);
        }
    }

    private Object scheduler(UpdateChecker checker) throws Exception
    {
        Field field = UpdateChecker.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        return field.get(checker);
    }
}
