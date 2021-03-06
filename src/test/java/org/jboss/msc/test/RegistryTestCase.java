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
package org.jboss.msc.test;

import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceMode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceNotFoundException;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.txn.AbstractServiceTest;
import org.jboss.msc.txn.DependencyInfo;
import org.jboss.msc.txn.TestControllerListener;
import org.jboss.msc.txn.TestService;
import org.jboss.msc.txn.UpdateTransaction;
import org.junit.Before;
import org.junit.Test;

import static org.jboss.msc.service.DependencyFlag.UNREQUIRED;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ManagementContext test case.
 * 
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 * 
 */
public class RegistryTestCase extends AbstractServiceTest {
    private static final ServiceName serviceAName = ServiceName.of("a", "different", "service", "name");
    private static final ServiceName serviceBName = ServiceName.of("u", "n", "e", "x", "p", "e", "c", "ted");
    private static final ServiceName serviceCName = ServiceName.of("just", "C");
    private static final ServiceName serviceDName = ServiceName.of("D");
    private static final ServiceName serviceEName = ServiceName.of("e", "service");
    private static final ServiceName serviceFName = ServiceName.of("f");
    private static final ServiceName serviceGName = ServiceName.of("g");
    private static final ServiceName serviceHName = ServiceName.of("H");
    private static final ServiceName serviceIName = ServiceName.of("iresource", "service");
    private ServiceRegistry registry1;
    private TestService serviceA;
    private TestService serviceB;
    private TestService serviceC;
    private TestService serviceD;
    private ServiceRegistry registry2;
    private TestService serviceE;
    private TestService serviceF;
    private TestService serviceG;
    private TestService serviceH;
    private ServiceRegistry registry3;

    @Before
    public void setup() {
        // registry1: contains A, B, and C
        registry1 = serviceContainer.newRegistry();
        serviceA = addService(registry1, serviceAName);
        serviceB = addService(registry1, serviceBName);
        serviceC = addService(registry1, serviceCName);
        // registry 2, contains D, E->D, F->(D and B), G->C, and H -> G services
        registry2 = serviceContainer.newRegistry();
        serviceD = addService(registry2, serviceDName);
        serviceE = addService(registry2, serviceEName, new DependencyInfo<TestService>(serviceDName, UNREQUIRED));
        serviceF = addService(registry2, serviceFName, false, ServiceMode.ACTIVE, new DependencyInfo<TestService>(serviceDName,
                UNREQUIRED), new DependencyInfo<TestService>(serviceBName, registry1, UNREQUIRED));
        serviceG = addService(registry2, serviceGName, false, ServiceMode.ACTIVE, new DependencyInfo<TestService>(serviceCName,
                registry1, UNREQUIRED));
        serviceH = addService(registry2, serviceHName, false, ServiceMode.ACTIVE, new DependencyInfo<TestService>(serviceGName,
                UNREQUIRED));
        // registry 3, empty
        registry3 = serviceContainer.newRegistry();
        // all services are up
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void disableServiceA() {
        final TestControllerListener listener = new TestControllerListener();
        final UpdateTransaction transaction = newUpdateTransaction();
        try {
            final ServiceController controller = registry1.getRequiredService(serviceAName);
            controller.disable(transaction, listener);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertFalse(serviceA.isUp());
        assertTrue(listener.wasCalled());
        // all other services remain UP:
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void disableServiceB() {
        UpdateTransaction transaction = newUpdateTransaction();
        final ServiceController controller;
        try {
            controller = registry1.getRequiredService(serviceBName);
            controller.disable(transaction);
            controller.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertFalse(serviceB.isUp());
        // service F depends on B
        assertFalse(serviceF.isUp());
        assertTrue(serviceA.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());

        // idempotent
        transaction = newUpdateTransaction();
        try {
            controller.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertFalse(serviceB.isUp());
        // service F depends on B
        assertFalse(serviceF.isUp());
        assertTrue(serviceA.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void enableServiceC() throws Exception {
        final TestControllerListener listener1 = new TestControllerListener();
        final TestControllerListener listener2 = new TestControllerListener();
        final TestControllerListener listener3 = new TestControllerListener();
        final UpdateTransaction transaction = newUpdateTransaction();
        try {
            final ServiceController controller = registry1.getRequiredService(serviceCName);
            controller.enable(transaction, listener1);
            controller.disable(transaction, listener2);
            controller.enable(transaction, listener3);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
        assertTrue(listener1.wasCalled());
        assertTrue(listener2.wasCalled());
        assertTrue(listener3.wasCalled());
    }

    @Test
    public void enableServiceCInTwoSteps() throws Exception {
        final TestControllerListener listener1 = new TestControllerListener();
        final ServiceController controller = registry1.getRequiredService(serviceCName);
        // operation that will be ignored, services are already enabled
        UpdateTransaction transaction = newUpdateTransaction();
        try {

            controller.enable(transaction, listener1);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
        assertTrue(listener1.wasCalled());

        // disable service C
        final TestControllerListener listener2 = new TestControllerListener();
        transaction = newUpdateTransaction();
        try {
            controller.disable(transaction, listener2);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertFalse(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertFalse(serviceG.isUp());
        assertFalse(serviceH.isUp());
        assertTrue(listener2.wasCalled());

        // enable service C
        final TestControllerListener listener3 = new TestControllerListener();
        transaction = newUpdateTransaction();
        try {
            controller.enable(transaction, listener3);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
        listener3.wasCalled();
    }

    @Test
    public void enableServiceD() {
        final UpdateTransaction transaction = newUpdateTransaction();
        try {
            final ServiceController controller = registry2.getRequiredService(serviceDName);
            controller.disable(transaction);
            controller.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void enableServiceDInTwoSteps() {
        final ServiceController controller = registry2.getRequiredService(serviceDName);

        UpdateTransaction transaction = newUpdateTransaction();
        try {
            controller.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertFalse(serviceD.isUp());
        assertFalse(serviceE.isUp());
        assertFalse(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());

        transaction = newUpdateTransaction();
        try {
            controller.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void enableServiceA() {
        disableServiceA();
        final UpdateTransaction transaction = newUpdateTransaction();
        try {
            final ServiceController controller = registry1.getRequiredService(serviceAName);
            controller.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void disableRegistry1() {
        final UpdateTransaction transaction = newUpdateTransaction();
        try {
            registry1.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertFalse(serviceA.isUp());
        assertFalse(serviceB.isUp());
        assertFalse(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertFalse(serviceF.isUp());
        assertFalse(serviceG.isUp());
        assertFalse(serviceH.isUp());
    }

    @Test
    public void disableRegistry2() {
        UpdateTransaction transaction = newUpdateTransaction();
        try {
            registry2.disable(transaction);
            // idempotent
            registry2.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertFalse(serviceD.isUp());
        assertFalse(serviceE.isUp());
        assertFalse(serviceF.isUp());
        assertFalse(serviceG.isUp());
        assertFalse(serviceH.isUp());

        // idempotent
        transaction = newUpdateTransaction();
        try {
            registry2.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertFalse(serviceD.isUp());
        assertFalse(serviceE.isUp());
        assertFalse(serviceF.isUp());
        assertFalse(serviceG.isUp());
        assertFalse(serviceH.isUp());
    }

    @Test
    public void enableRegistry3() {
        UpdateTransaction transaction = newUpdateTransaction();
        try {
            registry3.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        // nothing happens, registry 3 is empty
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());

        transaction = newUpdateTransaction();
        try {
            registry3.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        // nothing happens, registry 3 is empty
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());

        // oops, no longer empty
        TestService serviceI = addService(registry3, serviceIName);
        assertFalse(serviceI.isUp()); // as registry 3 is disabled, serviceI won't start

        transaction = newUpdateTransaction();
        try {
            registry3.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        // service I finally starts
        assertTrue(serviceI.isUp());
        // remainder services keep the same
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void enableRegistry1() {
        final UpdateTransaction transaction = newUpdateTransaction();
        try {
            registry1.enable(transaction);
            registry1.disable(transaction);
            registry1.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void enableRegistry1InTwoSteps() {
        // enable registry1: nothing happens, it is already enabled
        UpdateTransaction transaction = newUpdateTransaction();
        try {
            registry1.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());

        // disable registry 1
        transaction = newUpdateTransaction();
        try {
            registry1.disable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertFalse(serviceA.isUp());
        assertFalse(serviceB.isUp());
        assertFalse(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertFalse(serviceF.isUp());
        assertFalse(serviceG.isUp());
        assertFalse(serviceH.isUp());

        // reenable registry 1
        transaction = newUpdateTransaction();
        try {
            registry1.enable(transaction);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
        assertTrue(serviceA.isUp());
        assertTrue(serviceB.isUp());
        assertTrue(serviceC.isUp());
        assertTrue(serviceD.isUp());
        assertTrue(serviceE.isUp());
        assertTrue(serviceF.isUp());
        assertTrue(serviceG.isUp());
        assertTrue(serviceH.isUp());
    }

    @Test
    public void outsiderService() {
        final UpdateTransaction transaction = newUpdateTransaction();
        try {
            ServiceNotFoundException expected = null;
            try {
                registry3.getRequiredService(serviceAName);
            } catch (ServiceNotFoundException e) {
                expected = e;
            }
            assertNotNull(expected);

            expected = null;
            try {
                registry3.getRequiredService(serviceAName);
            } catch (ServiceNotFoundException e) {
                expected = e;
            }
            assertNotNull(expected);
        } finally {
            prepare(transaction);
            commit(transaction);
        }
    }
}
