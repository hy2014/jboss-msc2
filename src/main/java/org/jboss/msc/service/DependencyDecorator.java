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
package org.jboss.msc.service;

import org.jboss.msc.txn.ServiceContext;
import org.jboss.msc.txn.TaskController;
import org.jboss.msc.txn.Transaction;

/**
 * Dependency decorator. Adds extra functionality to {@link SimpleDependency}.
 * 
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 *
 */
abstract class DependencyDecorator<T> implements Dependency<T> {
    protected final Dependency<T> dependency;

    public DependencyDecorator(Dependency<T> dependency) {
        this.dependency = dependency;
    }

    @Override
    public void setDependent(Transaction transaction, ServiceContext context, ServiceController<?> dependentController) {
        dependency.setDependent(transaction, context, dependentController);
    }

    @Override
    public final Registration getDependencyRegistration() {
        return dependency.getDependencyRegistration();
    }

    @Override
    public void performInjections() {
        dependency.performInjections();
    }

    @Override
    public void demand(Transaction transaction, ServiceContext context) {
        dependency.demand(transaction, context);
    }

    @Override
    public void undemand(Transaction transaction, ServiceContext context) {
        dependency.undemand(transaction, context);
    }

    @Override
    public TaskController<?> newDependencyState(Transaction transaction, ServiceContext context, boolean dependencyUp) {
        return dependency.newDependencyState(transaction, context, dependencyUp);
    }

    @Override
    public TaskController<?> dependencyFailed(Transaction transaction, ServiceContext context) {
        return dependency.dependencyFailed(transaction, context);
    }

    @Override
    public TaskController<?> dependencyFailureCleared(Transaction transaction, ServiceContext context) {
        return dependency.dependencyFailureCleared(transaction, context);
    }


    @Override
    public void dependencyReplacementStarted(Transaction transaction) {
        dependency.dependencyReplacementStarted(transaction);
    }

    @Override
    public void dependencyReplacementConcluded(Transaction transaction) {
        dependency.dependencyReplacementConcluded(transaction);
    }
}