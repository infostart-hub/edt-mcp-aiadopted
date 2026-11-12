// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateInfobaseFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;

/** Core synchronization callbacks for an explicitly unattended host. */
public final class HeadlessInfobaseUpdate
{
    public static final String EXTERNAL_CHANGES = "edt.mcp.externalInfobaseChanges";

    private HeadlessInfobaseUpdate() { }

    public static String runBounded(java.util.function.Supplier<String> work)
    {
        return runBounded(work, 120_000L);
    }

    static String runBounded(java.util.function.Supplier<String> work, long timeoutMs)
    {
        return runBounded(work, timeoutMs, McpJobs::schedule);
    }

    static String runBounded(java.util.function.Supplier<String> work, long timeoutMs,
        BoundedJob.IJobScheduler scheduler)
    {
        var answer = new java.util.concurrent.atomic.AtomicReference<String>();
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        var ended = new java.util.concurrent.atomic.AtomicBoolean();
        var outcome = BoundedJob.run("Unattended database update", timeoutMs,
            monitor -> { entered.set(true); answer.set(work.get()); }, () -> ended.set(true), scheduler);
        if (outcome.getOutcome() == BoundedJob.Outcome.COMPLETED && outcome.getFailure() == null)
            return answer.get();
        String detail = outcome.getFailure() == null ? outcome.getOutcome().name()
            : PlatformFailures.describe(outcome.getFailure());
        if (outcome.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START
            || outcome.getOutcome() == BoundedJob.Outcome.NOT_RUN && ended.get() && !entered.get())
            return com.ditrix.edt.mcp.server.protocol.ToolResult.error(
                "The unattended database update did not start. No update ran; retry update_database.")
                .put("outcome", "notStarted").toJson();
        if (outcome.getOutcome() == BoundedJob.Outcome.COMPLETED)
            return com.ditrix.edt.mcp.server.protocol.ToolResult.errorWithUnknownMutationOutcome("Unattended database update failed: " + detail
                + ". Inspect the database before retrying update_database.").put("outcome", "failed").toJson();
        return com.ditrix.edt.mcp.server.protocol.ToolResult.errorWithUnknownMutationOutcome("Unattended database update did not complete: "
            + detail + ". Close active infobase sessions and inspect the database. "
            + "The operation may still run; do not submit another update or assume rollback.")
            .put("outcome", "unknown").toJson();
    }

    public static ApplicationUpdateState update(IApplication application, ApplicationUpdateType type,
        ExecutionContext context, IProgressMonitor monitor)
    {
        ExternalInfobaseChangesPolicy policy = context.<ExternalInfobaseChangesPolicy>getProperty(EXTERNAL_CHANGES)
            .orElse(ExternalInfobaseChangesPolicy.DEFAULT);
        return update(ServiceAccess.get(IInfobaseSynchronizationManager.class), application, type, policy, monitor);
    }

    static ApplicationUpdateState update(IInfobaseSynchronizationManager manager, IApplication target,
        ApplicationUpdateType type, ExternalInfobaseChangesPolicy policy, IProgressMonitor monitor)
    {
        InfobaseReference reference = InfobaseAccessSupport.resolveInfobaseReference(target);
        if (reference == null)
            throw new ApplicationException("No infobase reference resolves for application '" + target.getName()
                + "'. Use get_applications to select a bound infobase or server application.");
        IInfobaseUpdateCallback callback = callback(policy);
        // The 2025 SDK returns boolean; 2026 returns IStatus. Resolve the host signature.
        Object result;
        try
        {
            var method = IInfobaseSynchronizationManager.class.getMethod(
                type == ApplicationUpdateType.FULL ? "reloadInfobase" : "updateInfobase",
                IProject.class, InfobaseReference.class, IInfobaseUpdateCallback.class,
                boolean.class, IProgressMonitor.class);
            result = invoke(method, manager, new Object[] { target.getProject(), reference, callback, false, monitor });
        }
        catch (Throwable failure)
        {
            if (failure instanceof Error error) throw error;
            throw new ApplicationException("Unattended update failed: " + PlatformFailures.describe(failure));
        }
        IStatus status = updateStatus(result);
        if (status.matches(IStatus.ERROR | IStatus.CANCEL))
        {
            throw new ApplicationException("Unattended update failed: "
                + PlatformFailures.describeStatus(status)
                + ". Close active infobase sessions and retry update_database; inspect the project and database "
                + "before retrying an interrupted update.");
        }
        return ApplicationUpdateState.UPDATED;
    }

    static IStatus updateStatus(Object result)
    {
        if (result instanceof IStatus status) return status;
        if (Boolean.TRUE.equals(result)) return Status.OK_STATUS;
        return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "EDT did not complete the database update");
    }

    static IInfobaseUpdateCallback callback(ExternalInfobaseChangesPolicy policy)
    {
        // A proxy implements the exact callback interface exported by the running EDT.
        // No missing 2026 class is linked when loading this class in EDT 2025.2.
        return (IInfobaseUpdateCallback)java.lang.reflect.Proxy.newProxyInstance(
            IInfobaseUpdateCallback.class.getClassLoader(), new Class<?>[] { IInfobaseUpdateCallback.class },
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class)
                {
                    return switch (method.getName()) {
                        case "toString" -> "UnattendedInfobaseUpdateCallback";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                }
                if (method.getName().equals("onConfirm")) return true;
                if (!method.getName().equals("resolveInfobaseChanges"))
                    throw new UnsupportedOperationException(method.toString());
                if (policy == ExternalInfobaseChangesPolicy.CANCEL)
                    throw new InfobaseSynchronizationException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "External infobase changes were detected; externalInfobaseChanges=cancel stopped the update. "
                            + "Choose import to retain them or override to discard them, then retry update_database."));
                // 2026 inserted removed-object names before change; the trailing parameters are stable.
                int resolverIndex = args.length - 4;
                Object resolver = args[resolverIndex];
                Object flow = args[args.length - 2];
                if (policy != ExternalInfobaseChangesPolicy.IMPORT && !(flow instanceof IUpdateInfobaseFlow))
                    throw new InfobaseSynchronizationException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "This synchronization flow cannot override external changes. Use import or windowed EDT."));
                Object[] forwarded = new Object[args.length - 1];
                System.arraycopy(args, 0, forwarded, 0, resolverIndex);
                System.arraycopy(args, resolverIndex + 1, forwarded, resolverIndex, args.length - resolverIndex - 1);
                String name = policy == ExternalInfobaseChangesPolicy.IMPORT ? "resolveConflict" : "overrideConflict";
                for (var candidate : IInfobaseUpdateConflictResolver.class.getMethods())
                {
                    if (!candidate.getName().equals(name) || candidate.getParameterCount() != forwarded.length) continue;
                    Object resolution = invoke(candidate, resolver, forwarded);
                    if (resolution == null || method.getReturnType().isInstance(resolution)) return resolution;
                    // In 2026 override returns an enum, while the callback returns a result wrapper.
                    return method.getReturnType().getConstructor(candidate.getReturnType()).newInstance(resolution);
                }
                throw new UnsupportedOperationException("Unsupported EDT conflict resolver signature: " + method);
            });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable
    {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }
}
