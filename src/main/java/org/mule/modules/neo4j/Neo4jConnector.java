/*
 * Copyright (c) MuleSoft, Inc. All rights reserved. http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.md file.
 */

package org.mule.modules.neo4j;

import java.io.IOException;

import org.apache.commons.codec.binary.Base64;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Module;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.Optional;
import org.mule.api.annotations.rest.HttpMethod;
import org.mule.api.annotations.rest.RestCall;
import org.mule.api.annotations.rest.RestExceptionOn;
import org.mule.api.annotations.rest.RestHeaderParam;
import org.mule.modules.neo4j.model.ServiceRoot;
import org.mule.util.StringUtils;

/**
 * <p>
 * Neo4j Connector.
 * </p>
 * {@sample.config ../../../doc/mule-module-neo4j.xml.sample neo4j:config-no-auth} <br/>
 * {@sample.config ../../../doc/mule-module-neo4j.xml.sample neo4j:config-auth}
 * 
 * @author MuleSoft Inc.
 */
@Module(name = "neo4j", schemaVersion = "3.4", friendlyName = "Neo4j", minMuleVersion = "3.4.0", description = "Neo4j Module")
public abstract class Neo4jConnector
{
    /**
     * The base URI of the Neo4j server. It shouldn't end with a trailing slash.
     */
    @Configurable
    @Optional
    @Default("http://localhost:7474/db/data")
    private String baseUri;

    /**
     * The user used to authenticate to Neo4j.
     */
    @Configurable
    @Optional
    private String user;

    /**
     * The password used to authenticate to Neo4j.
     */
    @Configurable
    @Optional
    private String password;

    @RestHeaderParam("Authorization")
    private String authorization;

    /**
     * Get service root.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getServiceRoot}
     * 
     * @return the service root data.
     * @throws IOException if anything goes wrong with the operation.
     */
    @Processor
    // FIXME de-hardcode this value
    @RestCall(uri = "http://localhost:7474/db/data", method = HttpMethod.GET, contentType = "application/json", exceptions = {@RestExceptionOn(expression = "#[message.inboundProperties['http.status'] != 200]")})
    public abstract ServiceRoot getServiceRoot() throws IOException;

    private void refreshAuthorization()
    {
        final byte[] credentialBytes = (StringUtils.trimToEmpty(user) + ":" + StringUtils.trimToEmpty(password)).getBytes();
        authorization = "Basic " + new String(Base64.encodeBase64(credentialBytes));
    }

    public String getUser()
    {
        return user;
    }

    public void setUser(final String user)
    {
        this.user = user;
        refreshAuthorization();
    }

    public String getPassword()
    {
        return password;
    }

    public void setPassword(final String password)
    {
        this.password = password;
        refreshAuthorization();
    }

    public String getBaseUri()
    {
        return baseUri;
    }

    public void setBaseUri(final String baseUri)
    {
        this.baseUri = baseUri;
    }

    public String getAuthorization()
    {
        return authorization;
    }
}
