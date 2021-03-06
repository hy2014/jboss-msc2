/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.msc.txn;

import org.jboss.msc._private.MSCLogger;
import org.jboss.msc.service.CircularDependencyException;
import org.jboss.msc.service.DuplicateServiceException;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceMode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.util.Listener;

import static java.lang.Thread.holdsLock;
import static org.jboss.msc._private.MSCLogger.SERVICE;
import static org.jboss.msc.txn.Helper.setModified;
import static org.jboss.msc.txn.Helper.validateTransaction;

/**
 * A service controller implementation.
 *
 * @param <T> the service type
 * 
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ServiceControllerImpl<T> implements ServiceController<T> {

    // controller modes
    static final byte MODE_ACTIVE      = (byte)ServiceMode.ACTIVE.ordinal();
    static final byte MODE_LAZY        = (byte)ServiceMode.LAZY.ordinal();
    static final byte MODE_ON_DEMAND   = (byte)ServiceMode.ON_DEMAND.ordinal();
    static final byte MODE_MASK        = (byte)0b00000011;
    // controller states
    static final byte STATE_DOWN       = (byte)0b00000100;
    static final byte STATE_STARTING   = (byte)0b00001000;
    static final byte STATE_UP         = (byte)0b00001100;
    static final byte STATE_FAILED     = (byte)0b00010000;
    static final byte STATE_STOPPING   = (byte)0b00010100;
    static final byte STATE_REMOVING   = (byte)0b00011000;
    static final byte STATE_REMOVED    = (byte)0b00011100;
    static final byte STATE_MASK       = (byte)0b00011100;
    // controller flags
    static final byte SERVICE_ENABLED  = (byte)0b00100000;
    static final byte SERVICE_REMOVED  = (byte)0b01000000;
    static final byte REGISTRY_ENABLED = (byte)0b10000000;

    static final Service<Void> VOID_SERVICE = new Service<Void>() {
        @Override public void start(StartContext<Void> startContext) {}
        @Override public void stop(StopContext stopContext) {}
    };

    /**
     * The service itself.
     */
    private Service<T> service;
    /**
     * The service that will replace current service.
     */
    private Service<T> replaceService;
    /**
     * The primary registration of this service.
     */
    private final Registration primaryRegistration;
    /**
     * The alias registrations of this service.
     */
    private final Registration[] aliasRegistrations;
    /**
     * The dependencies of this service.
     */
    final DependencyImpl<?>[] dependencies;
    /**
     * The service value, resulting of service start.
     */
    private volatile T value;
    /**
     * The controller state.
     */
    private volatile byte state = (byte)(STATE_DOWN | MODE_ACTIVE | SERVICE_ENABLED | REGISTRY_ENABLED);
    /**
     * The number of dependencies that are not satisfied.
     */
    private int unsatisfiedDependencies;
    /**
     * Indicates if this service is demanded to start.
     */
    private int demandedByCount;
    /**
     * The system nanotime of the moment in which the last lifecycle change was
     * initiated.
     */
    @SuppressWarnings("VolatileLongOrDoubleField")
    volatile long lifecycleTime;

    private NotificationEntry<T> disableObservers;
    private NotificationEntry<T> enableObservers;
    private NotificationEntry<T> removeObservers;
    private NotificationEntry<T> replaceObservers;

    /**
     * Creates the service controller, thus beginning installation.
     * 
     * @param primaryRegistration the primary registration
     * @param aliasRegistrations  the alias registrations
     * @param service             the service itself
     * @param mode                the service mode
     * @param dependencies        the service dependencies
     */
    ServiceControllerImpl(final Registration primaryRegistration, final Registration[] aliasRegistrations,
            final Service<T> service, final org.jboss.msc.service.ServiceMode mode, final DependencyImpl<?>[] dependencies) {
        this.service = service != null ? service : (Service<T>)VOID_SERVICE;
        setMode(mode);
        this.primaryRegistration = primaryRegistration;
        this.aliasRegistrations = aliasRegistrations;
        this.dependencies = dependencies;
        this.unsatisfiedDependencies = dependencies.length;
    }

    private void setMode(final ServiceMode mode) {
        if (mode != null) {
            setMode((byte)mode.ordinal());
        } else {
            // default mode (if not provided) is ACTIVE
        }
    }

    /**
     * Begins services installation, by bounding service with its registrations (primary and aliases) and dependencies.
     * 
     * @throws DuplicateServiceException   if there is already a service installed at any of the registrations
     * @throws CircularDependencyException if installation of this services creates a dependency cycle
     */
    void beginInstallation() throws DuplicateServiceException, CircularDependencyException {
        // associate controller holder with primary registration
        if (!primaryRegistration.holderRef.compareAndSet(null, this)) {
            throw SERVICE.duplicateService(primaryRegistration.getServiceName());
        }
        boolean ok = false;
        int lastIndex = -1;
        try {
            // associate controller holder with alias registrations
            for (int i = 0; i < aliasRegistrations.length; lastIndex = i++) {
                if (!aliasRegistrations[i].holderRef.compareAndSet(null, this)) {
                    throw SERVICE.duplicateService(aliasRegistrations[i].getServiceName());
                }
            }
            CycleDetector.execute(this);
            ok = true;
        } finally {
            if (!ok) {
                // exception was thrown, cleanup
                for (int i = 0; i < lastIndex; i++) {
                    aliasRegistrations[i].holderRef.set(null);
                }
                primaryRegistration.holderRef.set(null);
            }
        }
    }

    /**
     * Completes service installation, enabling the service and installing it into registrations.
     *
     * @param transaction the active transaction
     */
    void completeInstallation(final Transaction transaction) {
        for (final DependencyImpl<?> dependency: dependencies) {
            dependency.setDependent(this, transaction);
        }
        primaryRegistration.serviceInstalled();
        boolean demandDependencies;
        synchronized (this) {
            if (!primaryRegistration.registry.isEnabled()) state &= ~REGISTRY_ENABLED;
            demandDependencies = isMode(MODE_ACTIVE);
        }
        if (demandDependencies) {
            demandDependencies(transaction);
        }
        synchronized (this) {
            transition(transaction);
        }
    }

    void clear(Transaction transaction) {
        primaryRegistration.clearController(transaction);
        for (Registration registration: aliasRegistrations) {
            registration.clearController(transaction);
        }
        final boolean undemand = isMode(MODE_ACTIVE);
        for (DependencyImpl<?> dependency: dependencies) {
            if (undemand) {
                dependency.undemand(transaction);
            }
            dependency.clearDependent(transaction);
        }
        primaryRegistration.serviceRemoved();
    }

    /**
     * Gets the primary registration.
     */
    Registration getPrimaryRegistration() {
        return primaryRegistration;
    }

    /**
     * Gets the dependencies.
     */
    DependencyImpl<?>[] getDependencies() {
        return dependencies;
    }

    /**
     * Gets the service.
     */
    public synchronized Service<T> getService() {
        return service == VOID_SERVICE ? null : service;
    }

    T getValue() {
        return value;
    }

    void setValue(T value) {
        this.value = value;
    }

    @Override
    public void disable(final UpdateTransaction transaction) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        disable(transaction, null);
    }

    @Override
    public void disable(final UpdateTransaction transaction, final Listener<ServiceController<T>> completionListener) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        validateTransaction(transaction, primaryRegistration.getTransactionController());
        setModified(transaction);
        synchronized (this) {
            while (true) {
                if (isServiceRemoved() || getState() == STATE_REMOVING || getState() == STATE_REMOVED) {
                    throw MSCLogger.SERVICE.cannotDisableRemovedService();
                }
                if (!isServiceEnabled()) break;
                state &= ~SERVICE_ENABLED;
                if (!isRegistryEnabled()) break;
                transition(transaction);
                break;
            }
            if (completionListener == null) return;
            if (getState() != STATE_DOWN) {
                this.disableObservers = new NotificationEntry<>(this.disableObservers, completionListener);
                return; // don't call completion listener
            }
        }
        safeCallListener(completionListener);
    }

    void safeCallListener(final Listener<ServiceController<T>> listener) {
        try {
            listener.handleEvent(this);
        } catch (final Throwable t) {
            MSCLogger.SERVICE.serviceControllerCompletionListenerFailed(t);
        }
    }

    @Override
    public void enable(final UpdateTransaction transaction) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        enable(transaction, null);
    }

    @Override
    public void enable(final UpdateTransaction transaction, final Listener<ServiceController<T>> completionListener) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        validateTransaction(transaction, primaryRegistration.getTransactionController());
        setModified(transaction);
        synchronized (this) {
            while (true) {
                if (isServiceRemoved() || getState() == STATE_REMOVING || getState() == STATE_REMOVED) {
                    throw MSCLogger.SERVICE.cannotEnableRemovedService();
                }
                if (isServiceEnabled()) break;
                state |= SERVICE_ENABLED;
                if (!isRegistryEnabled()) break;
                transition(transaction);
                break;
            }
            if (completionListener == null) return;
            if (getState() != STATE_UP && getState() != STATE_FAILED) {
                this.enableObservers = new NotificationEntry<>(this.enableObservers, completionListener);
                return; // don't call completion listener
            }
        }
        safeCallListener(completionListener);
    }

    private boolean isServiceEnabled() {
        assert holdsLock(this);
        return Bits.allAreSet(state, SERVICE_ENABLED);
    }

    private boolean isServiceRemoved() {
        assert holdsLock(this);
        return Bits.allAreSet(state, SERVICE_REMOVED);
    }

    void disableRegistry(final Transaction transaction) {
        synchronized (this) {
            if (isServiceRemoved()) return;
            if (!isRegistryEnabled()) return;
            state &= ~REGISTRY_ENABLED;
            if (!isServiceEnabled()) return;
            transition(transaction);
        }
    }

    void enableRegistry(final Transaction transaction) {
        synchronized (this) {
            if (isServiceRemoved()) return;
            if (isRegistryEnabled()) return;
            state |= REGISTRY_ENABLED;
            if (!isServiceEnabled()) return;
            transition(transaction);
        }
    }

    private boolean isRegistryEnabled() {
        assert holdsLock(this);
        return Bits.allAreSet(state, REGISTRY_ENABLED);
    }

    @Override
    public void retry(final UpdateTransaction transaction) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        retry(transaction, null);
    }

    @Override
    public void retry(final UpdateTransaction transaction, final Listener<ServiceController<T>> completionListener) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        validateTransaction(transaction, primaryRegistration.getTransactionController());
        setModified(transaction);
        synchronized (this) {
            if (isServiceRemoved() || getState() == STATE_REMOVING || getState() == STATE_REMOVED) {
                throw MSCLogger.SERVICE.cannotRetryRemovedService();
            }
            if (getState() != STATE_FAILED) {
                throw MSCLogger.SERVICE.serviceControllerNotInFailedState();
            }
            state &= ~SERVICE_ENABLED;
            if (completionListener != null) {
                this.enableObservers = new NotificationEntry<>(this.enableObservers, completionListener);
            }
            transition(transaction);
        }
    }

    /**
     * Removes this service.<p>
     * All dependent services will be automatically stopped as the result of this operation.
     *
     * @param  transaction the active transaction
     */
    @Override
    public void remove(final UpdateTransaction transaction) throws IllegalArgumentException, InvalidTransactionStateException {
        remove(transaction, null);
    }

    @Override
    public void remove(final UpdateTransaction transaction, final Listener<ServiceController<T>> completionListener) throws IllegalArgumentException, InvalidTransactionStateException {
        validateTransaction(transaction, primaryRegistration.getTransactionController());
        setModified(transaction);
        _remove(transaction, completionListener);
    }

    void _remove(final Transaction transaction, final Listener<ServiceController<T>> completionListener) throws IllegalArgumentException, InvalidTransactionStateException {
        synchronized (this) {
            while (true) {
                if (isServiceRemoved()) break;
                state |= SERVICE_REMOVED;
                transition(transaction);
                break;
            }
            if (completionListener == null) return;
            if (getState() != STATE_REMOVED) {
                this.removeObservers = new NotificationEntry<>(this.removeObservers, completionListener);
                return; // don't call completion listener
            }
        }
        safeCallListener(completionListener);
    }

    @Override
    public void replace(final UpdateTransaction transaction, final Service<T> newService) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        replace(transaction, newService, null);
    }

    @Override
    public void replace(final UpdateTransaction transaction, final Service<T> newService, final Listener<ServiceController<T>> completionListener)
            throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        validateTransaction(transaction, primaryRegistration.getTransactionController());
        setModified(transaction);
        synchronized (this) {
            if (isServiceRemoved() || getState() == STATE_REMOVING || getState() == STATE_REMOVED) {
                throw MSCLogger.SERVICE.cannotReplaceRemovedService();
            }
            if (getState() == STATE_DOWN) {
                service = newService != null ? newService : (Service<T>)VOID_SERVICE;
            } else {
                replaceService = newService != null ? newService : (Service<T>)VOID_SERVICE;
                if (completionListener != null) {
                    this.replaceObservers = new NotificationEntry<>(this.replaceObservers, completionListener);
                }
                transition(transaction);
                return;
            }
        }
        if (completionListener != null) safeCallListener(completionListener);
    }

    @Override
    public void restart(final UpdateTransaction transaction) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        restart(transaction, null);
    }

    @Override
    public void restart(final UpdateTransaction transaction, final Listener<ServiceController<T>> completionListener) throws IllegalArgumentException, IllegalStateException, InvalidTransactionStateException {
        validateTransaction(transaction, primaryRegistration.getTransactionController());
        setModified(transaction);
        synchronized (this) {
            if (isServiceRemoved() || getState() == STATE_REMOVING || getState() == STATE_REMOVED) {
                throw MSCLogger.SERVICE.cannotRestartRemovedService();
            }
            if (getState() != STATE_UP) {
                throw MSCLogger.SERVICE.serviceControllerNotInUpState();
            }
            state &= ~SERVICE_ENABLED;
            if (completionListener != null) {
                this.enableObservers = new NotificationEntry<>(this.enableObservers, completionListener);
            }
            transition(transaction);
        }
    }

    /**
     * Notifies this service that it is demanded by one of its incoming dependencies.
     * 
     * @param transaction the active transaction
     */
    void demand(final Transaction transaction) {
        final boolean propagate;
        synchronized (this) {
            if (demandedByCount++ > 0) {
                return;
            }
            propagate = !isMode(MODE_ACTIVE);
        }
        if (propagate) {
            demandDependencies(transaction);
        }
        synchronized (this) {
            transition(transaction);
        }
    }

    /**
     * Demands this service's dependencies to start.
     * 
     * @param transaction the active transaction
     */
    private void demandDependencies(Transaction transaction) {
        for (DependencyImpl<?> dependency: dependencies) {
            dependency.demand(transaction);
        }
    }

    /**
     * Notifies this service that it is no longer demanded by one of its incoming dependencies (invoked when incoming
     * dependency is being disabled or removed).
     * 
     * @param transaction the active transaction
     */
    void undemand(final Transaction transaction) {
        final boolean propagate;
        synchronized (this) {
            if (--demandedByCount > 0) {
                return;
            }
            propagate = !isMode(MODE_ACTIVE);
        }
        if (propagate) {
            undemandDependencies(transaction);
        }
        synchronized (this) {
            transition(transaction);
        }
    }

    /**
     * Undemands this service's dependencies to start.
     * 
     * @param transaction the active transaction
     */
    private void undemandDependencies(Transaction transaction) {
        for (DependencyImpl<?> dependency: dependencies) {
            dependency.undemand(transaction);
        }
    }

    public ServiceName getServiceName() {
        return primaryRegistration.getServiceName();
    }

    void dependencySatisfied(final Transaction transaction) {
        synchronized (this) {
            if (--unsatisfiedDependencies > 0) {
                return;
            }
            transition(transaction);
        }
    }

    public void dependencyUnsatisfied(final Transaction transaction) {
        synchronized (this) {
            if (++unsatisfiedDependencies > 1) {
               return;
            }
            transition(transaction);
        }
    }

    /* Transition related methods */

    void setServiceUp(T result, final Transaction transaction) {
        setValue(result);
        NotificationEntry<T> enableObservers;
        synchronized (this) {
            setState(STATE_UP);
            transition(transaction);
            enableObservers = this.enableObservers;
            this.enableObservers = null;
        }
        while (enableObservers != null) {
            safeCallListener(enableObservers.completionListener);
            enableObservers = enableObservers.next;
        }
    }

    void setServiceFailed(final Transaction transaction) {
        MSCLogger.FAIL.startFailed(getServiceName());
        NotificationEntry<T> enableObservers;
        synchronized (this) {
            setState(STATE_FAILED);
            transition(transaction);
            enableObservers = this.enableObservers;
            this.enableObservers = null;
        }
        while (enableObservers != null) {
            safeCallListener(enableObservers.completionListener);
            enableObservers = enableObservers.next;
        }
    }

    void setServiceDown(final Transaction transaction) {
        setValue(null);
        NotificationEntry<T> disableObservers, replaceObservers = null;
        synchronized (this) {
            setState(STATE_DOWN);
            transition(transaction);
            if (replaceService != null) {
                service = replaceService;
                replaceService = null;
                replaceObservers = this.replaceObservers;
                this.replaceObservers = null;
            }
            disableObservers = this.disableObservers;
            this.disableObservers = null;
        }
        while (replaceObservers != null) {
            safeCallListener(replaceObservers.completionListener);
            replaceObservers = replaceObservers.next;
        }
        while (disableObservers != null) {
            safeCallListener(disableObservers.completionListener);
            disableObservers = disableObservers.next;
        }
    }

    void setServiceRemoved(final Transaction transaction) {
        NotificationEntry<T> disableObservers, enableObservers, removeObservers;
        synchronized (this) {
            setState(STATE_REMOVED);
            disableObservers = this.disableObservers;
            this.disableObservers = null;
            enableObservers = this.enableObservers;
            this.enableObservers = null;
            removeObservers = this.removeObservers;
            this.removeObservers = null;
        }
        clear(transaction);
        while (disableObservers != null) {
            safeCallListener(disableObservers.completionListener);
            disableObservers = disableObservers.next;
        }
        while (enableObservers != null) {
            safeCallListener(enableObservers.completionListener);
            enableObservers = enableObservers.next;
        }
        while (removeObservers != null) {
            safeCallListener(removeObservers.completionListener);
            removeObservers = removeObservers.next;
        }
    }

    void notifyServiceUp(final Transaction transaction) {
        primaryRegistration.serviceUp(transaction);
        for (Registration registration: aliasRegistrations) {
            registration.serviceUp(transaction);
        }
    }

    void notifyServiceDown(Transaction transaction) {
        primaryRegistration.serviceDown(transaction);
        for (Registration registration: aliasRegistrations) {
            registration.serviceDown(transaction);
        }
    }

    private void transition(final Transaction transaction) {
        assert holdsLock(this);
        final boolean removed = isServiceRemoved();
        switch (getState()) {
            case STATE_DOWN:
                if (unsatisfiedDependencies == 0 && shouldStart()) {
                    setState(STATE_STARTING);
                    StartServiceTask.create(this, transaction);
                } else if (removed) {
                    setState(STATE_REMOVING);
                    RemoveServiceTask.create(this, transaction);
                }
                break;
            case STATE_UP:
                if (unsatisfiedDependencies > 0 || shouldStop()) {
                    lifecycleTime = System.nanoTime();
                    setState(STATE_STOPPING);
                    StopServiceTask.create(this, transaction);
                }
                break;
            case STATE_FAILED:
                if (unsatisfiedDependencies > 0 || shouldStop()) {
                    lifecycleTime = System.nanoTime();
                    setState(STATE_STOPPING);
                    StopFailedServiceTask.create(this, transaction);
                }
                break;
        }
    }

    private boolean shouldStart() {
        return (isMode(MODE_ACTIVE) || demandedByCount > 0) && Bits.allAreSet(state, SERVICE_ENABLED | REGISTRY_ENABLED) && Bits.allAreClear(state, SERVICE_REMOVED);
    }

    private boolean shouldStop() {
        return (isMode(MODE_ON_DEMAND) && demandedByCount == 0) || !Bits.allAreSet(state, SERVICE_ENABLED | REGISTRY_ENABLED) || Bits.allAreSet(state, SERVICE_REMOVED) || replaceService != null;
    }

    private synchronized void setMode(final byte mid) {
        state = (byte) (mid & MODE_MASK | state & ~MODE_MASK);
    }

    private synchronized boolean isMode(final byte mode) {
        return (state & MODE_MASK) == mode;
    }

    private void setState(final byte newState) {
        assert holdsLock(this);
        state = (byte) (newState & STATE_MASK | state & ~STATE_MASK);
    }

    byte getState() {
        return (byte)(state & STATE_MASK);
    }

    private static final class NotificationEntry<T> {

        private final NotificationEntry next;
        private final Listener<ServiceController<T>> completionListener;

        private NotificationEntry(final NotificationEntry next, final Listener<ServiceController<T>> listener) {
            this.next = next;
            this.completionListener = listener;
        }

    }
}
