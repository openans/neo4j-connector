/*
 * Copyright (c) MuleSoft, Inc. All rights reserved. http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.md file.
 */

package org.mule.modules.neo4j;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import org.junit.Test;
import org.mule.tck.junit4.FunctionalTestCase;

public class Neo4jConnectorTestCase extends FunctionalTestCase
{
    @Override
    protected String getConfigResources()
    {
        return "neo4j-connector-config.xml";
    }

    @Test
    public void ensureConfigurationLoads()
    {
        assertThat(muleContext.getRegistry().lookupFlowConstruct("test"), is(notNullValue()));
    }
}
