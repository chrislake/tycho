/*******************************************************************************
 * Copyright (c) 2012 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.test.product;

import org.apache.maven.it.Verifier;
import org.eclipse.tycho.test.AbstractTychoIntegrationTest;
import org.eclipse.tycho.test.util.ResourceUtil.P2Repositories;
import org.junit.Test;

public class ProductDuplicateIUsTest extends AbstractTychoIntegrationTest {

    @Test
    public void testMultipleProductsNoDuplicateIUs() throws Exception {
        Verifier verifier = getVerifier("product.duplicateIUs", false);
        verifier.getSystemProperties().setProperty("test-data-repo", P2Repositories.ECLIPSE_OXYGEN.toString());
        verifier.executeGoal("integration-test");
        verifier.verifyErrorFreeLog();
    }
}
