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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.Validate;
import org.mule.api.ConnectionException;
import org.mule.api.ConnectionExceptionCode;
import org.mule.api.DefaultMuleException;
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
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.transformer.TransformerException;
import org.mule.endpoint.URIBuilder;
import org.mule.module.json.transformers.ObjectToJson;
import org.mule.modules.neo4j.model.CypherQuery;
import org.mule.modules.neo4j.model.CypherQueryResult;
import org.mule.modules.neo4j.model.Node;
import org.mule.modules.neo4j.model.ServiceRoot;
import org.mule.transformer.types.MimeTypes;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
import org.mule.util.StringUtils;

/**
 * <p>
 * Neo4j Connector.
 * </p>
 * {@sample.config ../../../doc/mule-module-neo4j.xml.sample neo4j:config-no-auth}
 * <p>
 * {@sample.config ../../../doc/mule-module-neo4j.xml.sample neo4j:config-auth}
 * 
 * @author MuleSoft Inc.
 */
@Connector(name = "neo4j", schemaVersion = "3.4", friendlyName = "Neo4j", minMuleVersion = "3.4.0", description = "Neo4j Module")
public class Neo4jConnector implements MuleContextAware
{
    private static final Set<Integer> SC_OK = Collections.singleton(HttpConstants.SC_OK);
    private static final Set<Integer> SC_CREATED = Collections.singleton(HttpConstants.SC_CREATED);

    private static final Set<Integer> SC_OK_OR_NOT_FOUND = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_OK, HttpConstants.SC_NOT_FOUND)));

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
    private ObjectToJson objectToJsonTransformer;

    /**
     * Connect to a Neo4j server.
     * 
     * @param baseUri the base URI of the Neo4j server API.
     * @throws ConnectionException in case connection fails.
     */
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
            serviceRoot = getEntity(baseUri + "/", ServiceRoot.class, SC_OK);
        }
        catch (final MuleException me)
        {
            throw new ConnectionException(ConnectionExceptionCode.CANNOT_REACH, null,
                "Failed to retrieve service root from: " + baseUri, me);
        }

        objectToJsonTransformer = new ObjectToJson();
        objectToJsonTransformer.setMuleContext(muleContext);

        try
        {
            objectToJsonTransformer.initialise();
        }
        catch (final InitialisationException ie)
        {
            throw new ConnectionException(ConnectionExceptionCode.UNKNOWN, null,
                "Failed to initialize JSON transformer: " + baseUri, ie);
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
        objectToJsonTransformer = null;
    }

    private <T> T getEntity(final String uri,
                            final Class<T> responseClass,
                            final Set<Integer> expectedStatusCodes,
                            final Object... queryParameters) throws MuleException
    {
        return sendHttpRequest(uri, null, getRequestProperties(HttpConstants.METHOD_GET), responseClass,
            expectedStatusCodes, queryParameters);
    }

    private <T> T postEntity(final String uri,
                             final Object entity,
                             final Class<T> responseClass,
                             final Set<Integer> expectedStatusCodes,
                             final Object... queryParameters) throws MuleException
    {
        final Map<String, Object> requestProperties = getRequestProperties(HttpConstants.METHOD_POST);

        requestProperties.put(HttpConstants.HEADER_CONTENT_TYPE, MimeTypes.JSON);

        final String json = serializeEntityToJson(entity);

        return sendHttpRequest(uri, json, requestProperties, responseClass, expectedStatusCodes,
            queryParameters);
    }

    private String serializeEntityToJson(final Object entity) throws TransformerException
    {
        if (entity == null)
        {
            return null;
        }

        return (String) objectToJsonTransformer.transform(entity);
    }

    private <T> T sendHttpRequest(final String uri,
                                  final Object entity,
                                  final Map<String, Object> requestProperties,
                                  final Class<T> responseClass,
                                  final Set<Integer> expectedStatusCodes,
                                  final Object... queryParameters) throws MuleException
    {
        final MuleMessage response = muleContext.getClient().send(buildUri(uri, queryParameters), entity,
            requestProperties);

        final Integer responseStatusCode = Integer.valueOf((String) response.getInboundProperty(HttpConnector.HTTP_STATUS_PROPERTY));

        if (!expectedStatusCodes.contains(responseStatusCode))
        {
            throw new DefaultMuleException("Received status code: " + responseStatusCode
                                           + " but was expecting: " + expectedStatusCodes);
        }

        return responseStatusCode != HttpConstants.SC_NOT_FOUND ? response.getPayload(responseClass) : null;
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
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public CypherQueryResult runCypherQuery(@Optional @Default("false") final boolean includeStatistics,
                                            @Optional @Default("false") final boolean profile,
                                            final CypherQuery cypherQuery) throws MuleException
    {
        return postEntity(serviceRoot.getCypher(), cypherQuery, CypherQueryResult.class, SC_OK,
            "includeStats", includeStatistics, "profile", profile);
    }

    /**
     * Get a node.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNode}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNode-failIfNotFound}
     * 
     * @param failIfNotFound if true, an exception will be thrown if the node is not found,
     *            otherwise null will be returned.
     * @param nodeId id of the node to get.
     * @return a {@link Node} instance or null.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Node getNode(@Optional @Default("false") final boolean failIfNotFound, final long nodeId)
        throws MuleException
    {
        return getEntity(serviceRoot.getNode() + "/" + nodeId, Node.class, failIfNotFound
                                                                                         ? SC_OK
                                                                                         : SC_OK_OR_NOT_FOUND);
    }

    /**
     * Create a node.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createNode}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createNode-withProperties}
     * 
     * @param properties node properties or null.
     * @return the created {@link Node} instance.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Node createNode(@Optional final Map<String, Object> properties) throws MuleException
    {
        return postEntity(serviceRoot.getNode(), properties, Node.class, SC_CREATED);
    }

    // TODO deleteNode

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
