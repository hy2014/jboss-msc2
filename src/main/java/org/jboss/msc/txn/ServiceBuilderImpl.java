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
import org.jboss.msc.service.Dependency;
import org.jboss.msc.service.DependencyFlag;
import org.jboss.msc.service.DuplicateServiceException;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceMode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * A service builder.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ServiceBuilderImpl<T> implements ServiceBuilder<T> {

    private static final Registration[] NO_ALIASES = new Registration[0];
    private static final DependencyImpl<?>[] NO_DEPENDENCIES = new DependencyImpl<?>[0];

    static final DependencyFlag[] noFlags = new DependencyFlag[0];

    // the service registry
    private final ServiceRegistryImpl registry;
    // service name
    private final ServiceName name;
    // service aliases
    private final Set<ServiceName> aliases = new HashSet<>(0);
    // service itself
    private Service<T> service;
    // dependencies
    private final Set<DependencyImpl<?>> dependencies = new HashSet<>();
    // active transaction
    private final Transaction transaction;
    // service mode
    private ServiceMode mode;
    // is service builder installed?
    private boolean installed;

    /**
     * Creates service builder.
     * @param registry     the service registry
     * @param name         service name
     * @param transaction  active transaction
     */
    ServiceBuilderImpl(final Transaction transaction, final ServiceRegistryImpl registry, final ServiceName name) {
        this.transaction = transaction;
        this.registry = registry;
        this.name = name;
        this.mode = ServiceMode.ACTIVE;
    }

    void addDependency(DependencyImpl<?> dependency) {
        dependencies.add(dependency);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServiceBuilder<T> setMode(final ServiceMode mode) {
        checkAlreadyInstalled();
        if (mode == null) {
            throw MSCLogger.SERVICE.methodParameterIsNull("mode");
        }
        this.mode = mode;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServiceBuilder<T> setService(final Service<T> service) {
        assert ! calledFromConstructorOf(service) : "setService() must not be called from the service constructor";
        checkAlreadyInstalled();
        if (service == null) {
            throw MSCLogger.SERVICE.methodParameterIsNull("service");
        }
        this.service = service;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServiceBuilderImpl<T> addAliases(final ServiceName... aliases) {
        checkAlreadyInstalled();
        if (aliases != null) for (final ServiceName alias : aliases) {
            if (alias != null && !alias.equals(name)) {
                this.aliases.add(alias);
            }
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <D> Dependency<D> addDependency(final ServiceName name) {
        return addDependencyInternal(registry, name, (DependencyFlag[])null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <D> Dependency<D> addDependency(final ServiceName name, final DependencyFlag... flags) {
        return addDependencyInternal(registry, name, flags);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <D> Dependency<D> addDependency(final ServiceRegistry registry, final ServiceName name) {
        return addDependencyInternal((ServiceRegistryImpl)registry, name, (DependencyFlag[])null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <D> Dependency<D> addDependency(final ServiceRegistry registry, final ServiceName name, final DependencyFlag... flags) {
        return addDependencyInternal((ServiceRegistryImpl)registry, name, flags);
    }

    private <D> Dependency<D> addDependencyInternal(final ServiceRegistryImpl registry, final ServiceName name, final DependencyFlag... flags) {
        checkAlreadyInstalled();
        if (registry == null) {
            throw MSCLogger.SERVICE.methodParameterIsNull("registry");
        }
        if (name == null) {
            throw MSCLogger.SERVICE.methodParameterIsNull("name");
        }
        if (this.registry.getTransactionController() != registry.getTransactionController()) {
            throw MSCLogger.SERVICE.cannotCreateDependencyOnRegistryCreatedByOtherTransactionController();
        }
        final Registration dependencyRegistration = registry.getOrCreateRegistration(name);
        final DependencyImpl<D> dependency = new DependencyImpl<>(dependencyRegistration, flags != null ? flags : noFlags);
        dependencies.add(dependency);
        return dependency;
    }

    private static boolean calledFromConstructorOf(Object obj) {
        if (obj == null) return false;
        final String c = obj.getClass().getName();
        final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getMethodName().equals("<init>") && element.getClassName().equals(c)) {
                return true;
            }
        }
        return false;
    }

    private void checkAlreadyInstalled() {
        if (installed) {
            throw new IllegalStateException("ServiceBuilder installation already requested.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServiceController install() throws IllegalStateException, DuplicateServiceException, CircularDependencyException {
        assert ! calledFromConstructorOf(service) : "install() must not be called from a service constructor";
        // idempotent
        if (installed) {
            throw MSCLogger.SERVICE.cannotCallInstallTwice();
        }
        installed = true;

        // create primary registration
        final Registration registration = registry.getOrCreateRegistration(name);

        // create alias registrations
        final Registration[] aliasRegistrations = aliases.size() > 0 ? new Registration[aliases.size()] : NO_ALIASES;
        if (aliasRegistrations.length > 0) {
            int i = 0;
            for (final ServiceName alias: aliases) {
                aliasRegistrations[i++] = registry.getOrCreateRegistration(alias);
            }
        }

        // create dependencies
        final DependencyImpl<?>[] dependenciesArray = dependencies.size() > 0 ? new DependencyImpl<?>[dependencies.size()] : NO_DEPENDENCIES;
        if (dependenciesArray.length > 0) {
            dependencies.toArray(dependenciesArray);
        }

        // create and install service controller
        final ServiceControllerImpl<T> serviceController =  new ServiceControllerImpl<>(registration, aliasRegistrations, service, mode, dependenciesArray);
        serviceController.beginInstallation();
        try {
            serviceController.completeInstallation(transaction);
        } catch (Throwable t) {
            serviceController.clear(transaction);
            throw t;
        }
        return serviceController;
    }
}
