/*
 * Copyright (c) MuleSoft, Inc. All rights reserved. http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.md file.
 */

package org.mule.modules.neo4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.Validate;
import org.mule.api.ConnectionException;
import org.mule.api.ConnectionExceptionCode;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Connect;
import org.mule.api.annotations.ConnectionIdentifier;
import org.mule.api.annotations.Connector;
import org.mule.api.annotations.Disconnect;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.ValidateConnection;
import org.mule.api.annotations.param.ConnectionKey;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.Optional;
import org.mule.api.context.MuleContextAware;
import org.mule.api.transformer.Transformer;
import org.mule.endpoint.URIBuilder;
import org.mule.modules.neo4j.model.CypherQuery;
import org.mule.modules.neo4j.model.CypherQueryResult;
import org.mule.modules.neo4j.model.ServiceRoot;
import org.mule.transformer.types.DataTypeFactory;
import org.mule.transformer.types.MimeTypes;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
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
@Connector(name = "neo4j", schemaVersion = "3.4", friendlyName = "Neo4j", minMuleVersion = "3.4.0", description = "Neo4j Module")
public class Neo4jConnector implements MuleContextAware
{
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

    /**
     * Should streaming be used when communicating with the Neo4j server.
     */
    @Configurable
    @Optional
    @Default("true")
    private boolean streaming;

    /**
     * The connector to use to reach Neo4j: configure only if there is more than one HTTP/HTTPS
     * connector active in your Mule application.
     */
    @Configurable
    @Optional
    private org.mule.api.transport.Connector connector;

    private MuleContext muleContext;
    private String authorization;
    private String baseUri;
    private ServiceRoot serviceRoot;

    @Connect
    public void connect(@ConnectionKey @Default("http://localhost:7474/db/data") final String baseUri)
        throws ConnectionException
    {
        try
        {
            new URI(baseUri);
        }
        catch (final URISyntaxException urie)
        {
            throw new ConnectionException(ConnectionExceptionCode.UNKNOWN_HOST, null, "Invalid baseUri: "
                                                                                      + baseUri, urie);
        }

        this.baseUri = baseUri;

        try
        {
            serviceRoot = getEntityFromApi(baseUri + "/", ServiceRoot.class);
        }
        catch (final MuleException me)
        {
            throw new ConnectionException(ConnectionExceptionCode.CANNOT_REACH, null,
                "Failed to retrieve service root from: " + baseUri, me);
        }
    }

    @ValidateConnection
    public boolean isConnected()
    {
        return serviceRoot != null;
    }

    @Disconnect
    public void disconnect() throws IOException
    {
        serviceRoot = null;
    }

    private <T> T getEntityFromApi(final String uri,
                                   final Class<T> responseClass,
                                   final Object... queryParameters) throws MuleException
    {
        final MuleMessage response = muleContext.getClient().send(buildUri(uri, queryParameters), null,
            getRequestProperties(HttpConstants.METHOD_GET));

        return response.getPayload(responseClass);
    }

    private <T> T postEntityToApi(final String uri,
                                  final Object entity,
                                  final Class<T> responseClass,
                                  final Object... queryParameters) throws MuleException
    {
        final Map<String, Object> requestProperties = getRequestProperties(HttpConstants.METHOD_POST);

        requestProperties.put(HttpConstants.HEADER_CONTENT_TYPE, MimeTypes.JSON);

        final Transformer transformer = muleContext.getRegistry().lookupTransformer(
            DataTypeFactory.create(entity.getClass()), DataTypeFactory.JSON_STRING);

        final Object json = transformer.transform(entity);

        final MuleMessage response = muleContext.getClient().send(buildUri(uri, queryParameters), json,
            requestProperties);

        return response.getPayload(responseClass);
    }

    private String buildUri(final String uri, final Object... queryParameters)
    {
        Validate.isTrue(queryParameters.length % 2 == 0, "queryParameters must be an even array");

        final URIBuilder uriBuilder = new URIBuilder(uri, muleContext);

        final Map<Object, Object> queryMap = new HashMap<Object, Object>();

        if (connector != null)
        {
            queryMap.put("connector", connector.getName());
        }

        for (int i = 0; i < queryParameters.length; i += 2)
        {
            final Object value = queryParameters[i + 1];

            if (value != null)
            {
                queryMap.put(queryParameters[i], value.toString());
            }
        }

        uriBuilder.setQueryMap(queryMap);

        return uriBuilder.toString();
    }

    private Map<String, Object> getRequestProperties(final String method)
    {
        final Map<String, Object> properties = new HashMap<String, Object>();

        properties.put(HttpConstants.HEADER_ACCEPT, MimeTypes.JSON);
        properties.put(HttpConnector.HTTP_METHOD_PROPERTY, method);
        properties.put("X-Stream", streaming);

        if (StringUtils.isNotBlank(authorization))
        {
            properties.put(HttpConstants.HEADER_AUTHORIZATION, authorization);
        }

        return properties;
    }

    /**
     * Get service root.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getServiceRoot}
     * 
     * @return the service root data.
     * @throws IOException if anything goes wrong with the operation.
     */
    @Processor
    public ServiceRoot getServiceRoot()
    {
        return serviceRoot;
    }

    /**
     * Run a cypher query.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:runCypherQuery}
     * 
     * @param includeStatistics defines if meta data about the query must be returned
     * @param profile defines if a profile of the executed query must be returned
     * @param cypherQuery the query to execute
     * @return a {@link CypherQueryResult}.
     * @throws MuleException
     * @throws IOException if anything goes wrong with the operation.
     */
    @Processor
    public CypherQueryResult runCypherQuery(@Optional @Default("false") final boolean includeStatistics,
                                            @Optional @Default("false") final boolean profile,
                                            final CypherQuery cypherQuery) throws MuleException
    {
        return postEntityToApi(serviceRoot.getCypher(), cypherQuery, CypherQueryResult.class, "includeStats",
            includeStatistics, "profile", profile);
    }

    private void refreshAuthorization()
    {
        final byte[] credentialBytes = (StringUtils.trimToEmpty(user) + ":" + StringUtils.trimToEmpty(password)).getBytes();
        authorization = "Basic " + new String(Base64.encodeBase64(credentialBytes));
    }

    public void setMuleContext(final MuleContext muleContext)
    {
        this.muleContext = muleContext;
    }

    public String getAuthorization()
    {
        return authorization;
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

    @ConnectionIdentifier
    public String getBaseUri()
    {
        return baseUri;
    }

    // non-JavaBean accessor required by DevKit: http://www.mulesoft.org/jira/browse/DEVKIT-365
    public boolean getStreaming()
    {
        return streaming;
    }

    public boolean isStreaming()
    {
        return streaming;
    }

    public void setStreaming(final boolean streaming)
    {
        this.streaming = streaming;
    }

    public org.mule.api.transport.Connector getConnector()
    {
        return connector;
    }

    public void setConnector(final org.mule.api.transport.Connector connector)
    {
        this.connector = connector;
    }
}
