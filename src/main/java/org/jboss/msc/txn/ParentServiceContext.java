/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
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

import org.jboss.msc.service.DependencyFlag;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.util.AttachmentKey;
import org.jboss.msc.util.Factory;

import java.util.concurrent.ConcurrentHashMap;

import static org.jboss.msc.txn.Helper.validateTransaction;

/**
 * Parent service context: behaves just like service context super class except that newly created services are
 * automatically children services of parent.
 * 
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 */
class ParentServiceContext extends ServiceContextImpl {

    private final Registration parentRegistration;

    public ParentServiceContext(Registration parentRegistration, UpdateTransaction txn) {
        super (txn);
        this.parentRegistration = parentRegistration;
    }

    @Override
    public <S> ServiceBuilder<S> addService(final ServiceRegistry registry, final ServiceName name) {
        validateParentUp();
        final ServiceBuilderImpl<S> serviceBuilder = (ServiceBuilderImpl<S>) super.addService(registry, name);
        final ServiceName parentName = parentRegistration.getServiceName();
        serviceBuilder.addDependency(getParentDependency(parentName, parentRegistration));
        return serviceBuilder;
    }

    private void validateParentUp() {
        if (parentRegistration.getController() == null) {
            throw new IllegalStateException("Service context error: " + parentRegistration.getServiceName() + " is not installed");
        }
        if (!Bits.allAreSet(parentRegistration.getController().getState(), ServiceControllerImpl.STATE_STARTING)) {
            throw new IllegalStateException("Service context error: " + parentRegistration.getServiceName() + " is not starting");
        }
    }

    private static final AttachmentKey<ConcurrentHashMap<ServiceName, DependencyImpl<?>>> PARENT_DEPENDENCIES= AttachmentKey.create(new Factory<ConcurrentHashMap<ServiceName, DependencyImpl<?>>>() {

        @Override
        public ConcurrentHashMap<ServiceName, DependencyImpl<?>> create() {
            return new ConcurrentHashMap<>();
        }

    });

    private <T> DependencyImpl<T> getParentDependency(ServiceName parentName, Registration parentRegistration) {
        final ConcurrentHashMap<ServiceName, DependencyImpl<?>> parentDependencies = getTransaction().getAttachment(PARENT_DEPENDENCIES);
        @SuppressWarnings("unchecked")
        DependencyImpl<T> parentDependency = (DependencyImpl<T>) parentDependencies.get(parentName);
        if (parentDependency == null ) {
            parentDependency = new ParentDependency<>(parentRegistration);
            parentDependencies.put(parentName, parentDependency);
        }
        return parentDependency;
    }

    /**
     * Parent dependency. The dependent is created whenever dependency is satisfied, and is removed whenever
     * dependency is no longer satisfied.
     */
    private static final class ParentDependency<T> extends DependencyImpl<T> {

        ParentDependency(final Registration dependencyRegistration) {
            super(dependencyRegistration, DependencyFlag.UNREQUIRED);
        }

        @Override
        public void dependencyDown(Transaction transaction) {
            dependent._remove(transaction, null);
        }
    }
}
