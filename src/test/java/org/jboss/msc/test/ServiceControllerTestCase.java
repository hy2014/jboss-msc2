/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
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
package org.jboss.msc.test;

import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceMode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.txn.AbstractTransactionTest;
import org.jboss.msc.txn.TestService;
import org.jboss.msc.txn.UpdateTransaction;
import org.jboss.msc.util.CompletionListener;
import org.jboss.msc.util.Listener;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class ServiceControllerTestCase extends AbstractTransactionTest {

    @Test
    public void replaceStartedServiceWithNewService() throws Exception {
        final CompletionListener<UpdateTransaction> createListener = new CompletionListener<>();
        txnController.createUpdateTransaction(defaultExecutor, createListener);
        UpdateTransaction updateTxn = createListener.awaitCompletion();
        assertNotNull(updateTxn);
        final ServiceContainer container = txnController.createServiceContainer();
        final ServiceRegistry registry = container.newRegistry();
        final ServiceName serviceName = ServiceName.of("test");
        final ServiceBuilder<Void> sb = txnController.getServiceContext(updateTxn).addService(registry, serviceName);
        final TestService service1 = new TestService(serviceName, sb, false);
        final ServiceController serviceController = sb.setService(service1).setMode(ServiceMode.ACTIVE).install();
        // assert first service is up and running
        prepare(updateTxn);
        commit(updateTxn);
        service1.waitStart();
        assertTrue(service1.isUp());
        assertSame(service1, serviceController.getService());
        // next transation
        updateTxn = newUpdateTransaction();
        // replace service
        final TestService service2 = new TestService(serviceName, sb, false);
        final ReplaceListener listener = new ReplaceListener();
        serviceController.replace(updateTxn, service2, listener);
        prepare(updateTxn);
        commit(updateTxn);
        service1.waitStop();
        service2.waitStart();
        // assert first service down and removed
        assertFalse(service1.isUp());
        assertNotSame(service1, serviceController.getService());
        // assert second service up and running
        assertTrue(service2.isUp());
        assertSame(service2, serviceController.getService());
        assertTrue(listener.wasExecuted());
    }

    @Test
    public void replaceStartedServiceWithNullService() throws Exception {
        final CompletionListener<UpdateTransaction> createListener = new CompletionListener<>();
        txnController.createUpdateTransaction(defaultExecutor, createListener);
        UpdateTransaction updateTxn = createListener.awaitCompletion();
        assertNotNull(updateTxn);
        final ServiceContainer container = txnController.createServiceContainer();
        final ServiceRegistry registry = container.newRegistry();
        final ServiceName serviceName = ServiceName.of("test");
        final ServiceBuilder<Void> sb = txnController.getServiceContext(updateTxn).addService(registry, serviceName);
        final TestService service1 = new TestService(serviceName, sb, false);
        final ServiceController serviceController = sb.setService(service1).setMode(ServiceMode.ACTIVE).install();
        // assert first service is up and running
        prepare(updateTxn);
        commit(updateTxn);
        service1.waitStart();
        assertTrue(service1.isUp());
        assertSame(service1, serviceController.getService());
        // next transation
        updateTxn = newUpdateTransaction();
        // replace service
        final ReplaceListener listener = new ReplaceListener();
        serviceController.replace(updateTxn, null, listener);
        prepare(updateTxn);
        commit(updateTxn);
        // assert first service down and removed
        assertFalse(service1.isUp());
        assertNotSame(service1, serviceController.getService());
        // assert second service up and null
        assertSame(null, serviceController.getService());
        assertTrue(listener.wasExecuted());
    }

    @Test
    public void replaceDownServiceWithNewService() throws Exception {
        final CompletionListener<UpdateTransaction> createListener = new CompletionListener<>();
        txnController.createUpdateTransaction(defaultExecutor, createListener);
        UpdateTransaction updateTxn = createListener.awaitCompletion();
        assertNotNull(updateTxn);
        final ServiceContainer container = txnController.createServiceContainer();
        final ServiceRegistry registry = container.newRegistry();
        final ServiceName serviceName = ServiceName.of("test");
        final ServiceBuilder<Void> sb = txnController.getServiceContext(updateTxn).addService(registry, serviceName);
        final TestService service1 = new TestService(serviceName, sb, false);
        final ServiceController serviceController = sb.setService(service1).setMode(ServiceMode.LAZY).install();
        // assert first service is up and running
        prepare(updateTxn);
        commit(updateTxn);
        assertFalse(service1.isUp());
        assertSame(service1, serviceController.getService());
        // next transation
        updateTxn = newUpdateTransaction();
        // replace service
        final TestService service2 = new TestService(serviceName, sb, false);
        final ReplaceListener listener = new ReplaceListener();
        serviceController.replace(updateTxn, service2, listener);
        prepare(updateTxn);
        commit(updateTxn);
        // assert first service down and removed
        assertFalse(service1.isUp());
        assertNotSame(service1, serviceController.getService());
        // assert second service up and running
        assertFalse(service2.isUp());
        assertSame(service2, serviceController.getService());
        assertTrue(listener.wasExecuted());
    }

    @Test
    public void replaceDownServiceWithNullService() throws Exception {
        final CompletionListener<UpdateTransaction> createListener = new CompletionListener<>();
        txnController.createUpdateTransaction(defaultExecutor, createListener);
        UpdateTransaction updateTxn = createListener.awaitCompletion();
        assertNotNull(updateTxn);
        final ServiceContainer container = txnController.createServiceContainer();
        final ServiceRegistry registry = container.newRegistry();
        final ServiceName serviceName = ServiceName.of("test");
        final ServiceBuilder<Void> sb = txnController.getServiceContext(updateTxn).addService(registry, serviceName);
        final TestService service1 = new TestService(serviceName, sb, false);
        final ServiceController serviceController = sb.setService(service1).setMode(ServiceMode.LAZY).install();
        // assert first service is up and running
        prepare(updateTxn);
        commit(updateTxn);
        assertFalse(service1.isUp());
        assertSame(service1, serviceController.getService());
        // next transation
        updateTxn = newUpdateTransaction();
        // replace service
        final ReplaceListener listener = new ReplaceListener();
        serviceController.replace(updateTxn, null, listener);
        prepare(updateTxn);
        commit(updateTxn);
        // assert first service down and removed
        assertFalse(service1.isUp());
        assertNotSame(service1, serviceController.getService());
        // assert second service up and null
        assertSame(null, serviceController.getService());
        assertTrue(listener.wasExecuted());
    }

    private static final class ReplaceListener implements Listener<ServiceController> {

        private boolean executed;

        @Override
        public void handleEvent(final ServiceController result) {
            executed = true;
        }

        private boolean wasExecuted() {
            return executed;
        }
    }

}
