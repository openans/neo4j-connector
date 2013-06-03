/*
 * Copyright (c) MuleSoft, Inc. All rights reserved. http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.md file.
 */

package org.mule.modules.neo4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.Validate;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
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
import org.mule.api.annotations.param.RefOnly;
import org.mule.api.context.MuleContextAware;
import org.mule.endpoint.URIBuilder;
import org.mule.modules.neo4j.model.BaseDataExtensible;
import org.mule.modules.neo4j.model.CypherQuery;
import org.mule.modules.neo4j.model.CypherQueryResult;
import org.mule.modules.neo4j.model.Data;
import org.mule.modules.neo4j.model.NewRelationship;
import org.mule.modules.neo4j.model.Node;
import org.mule.modules.neo4j.model.Relationship;
import org.mule.modules.neo4j.model.ServiceRoot;
import org.mule.transformer.types.MimeTypes;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
import org.mule.util.CollectionUtils;
import org.mule.util.MapUtils;
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
    public static enum RelationshipDirection
    {
        ALL
        {
            @Override
            public String getRelationshipsUrl(final Node node)
            {
                return node.getAllRelationships();
            }

            @Override
            public String getTypeRelationshipsUrlPattern(final Node node)
            {
                return node.getAllTypedRelationships();
            }
        },
        INCOMING
        {
            @Override
            public String getRelationshipsUrl(final Node node)
            {
                return node.getIncomingRelationships();
            }

            @Override
            public String getTypeRelationshipsUrlPattern(final Node node)
            {
                return node.getIncomingTypedRelationships();
            }
        },
        OUTGOING
        {
            @Override
            public String getRelationshipsUrl(final Node node)
            {
                return node.getOutgoingRelationships();
            }

            @Override
            public String getTypeRelationshipsUrlPattern(final Node node)
            {
                return node.getOutgoingTypedRelationships();
            }
        };

        public abstract String getRelationshipsUrl(Node node);

        public abstract String getTypeRelationshipsUrlPattern(Node node);
    }

    private static final TypeReference<ServiceRoot> SERVICE_ROOT_TYPE_REFERENCE = new TypeReference<ServiceRoot>()
    {
        // NOOP
    };
    private static final TypeReference<CypherQueryResult> CYPHER_QUERY_RESULT_TYPE_REFERENCE = new TypeReference<CypherQueryResult>()
    {
        // NOOP
    };
    private static final TypeReference<Node> NODE_TYPE_REFERENCE = new TypeReference<Node>()
    {
        // NOOP
    };
    private static final TypeReference<Relationship> RELATIONSHIP_TYPE_REFERENCE = new TypeReference<Relationship>()
    {
        // NOOP
    };
    private static final TypeReference<Collection<Relationship>> RELATIONSHIPS_TYPE_REFERENCE = new TypeReference<Collection<Relationship>>()
    {
        // NOOP
    };

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<Integer> SC_OK = Collections.singleton(HttpConstants.SC_OK);
    private static final Set<Integer> SC_CREATED = Collections.singleton(HttpConstants.SC_CREATED);
    private static final Set<Integer> SC_NO_CONTENT = Collections.singleton(HttpConstants.SC_NO_CONTENT);
    private static final Set<Integer> SC_NO_CONTENT_OR_NOT_FOUND = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_NO_CONTENT, HttpConstants.SC_NOT_FOUND)));
    private static final Set<Integer> SC_OK_OR_NOT_FOUND = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_OK, HttpConstants.SC_NOT_FOUND)));
    private static final Set<Integer> NO_RESPONSE_STATUSES = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_NO_CONTENT, HttpConstants.SC_NOT_FOUND)));
    private static final Set<String> ENTITY_CARRYING_HTTP_METHODS = Collections.unmodifiableSet(new HashSet<String>(
        Arrays.asList(HttpConstants.METHOD_POST, HttpConstants.METHOD_PUT, HttpConstants.METHOD_PATCH)));

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
            serviceRoot = getEntity(baseUri + "/", SERVICE_ROOT_TYPE_REFERENCE, SC_OK);
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

    private <T> T getEntity(final String uri,
                            final TypeReference<T> responseType,
                            final Set<Integer> expectedStatusCodes,
                            final Object... queryParameters) throws MuleException
    {
        return sendHttpRequest(uri, null, getRequestProperties(HttpConstants.METHOD_GET), responseType,
            expectedStatusCodes, queryParameters);
    }

    private void deleteEntity(final String uri, final Set<Integer> expectedStatusCodes) throws MuleException
    {
        sendHttpRequest(uri, null, getRequestProperties(HttpConstants.METHOD_DELETE), null,
            expectedStatusCodes);
    }

    private <T> T postEntity(final String uri,
                             final Object entity,
                             final TypeReference<T> responseType,
                             final Set<Integer> expectedStatusCodes,
                             final Object... queryParameters) throws MuleException
    {
        return sendRequestWithEntity(HttpConstants.METHOD_POST, uri, entity, responseType,
            expectedStatusCodes, queryParameters);
    }

    private <T> T putEntity(final String uri,
                            final Object entity,
                            final Set<Integer> expectedStatusCodes,
                            final Object... queryParameters) throws MuleException
    {
        return sendRequestWithEntity(HttpConstants.METHOD_PUT, uri, entity, null, expectedStatusCodes,
            queryParameters);
    }

    private <T> T sendRequestWithEntity(final String httpMethod,
                                        final String uri,
                                        final Object entity,
                                        final TypeReference<T> responseType,
                                        final Set<Integer> expectedStatusCodes,
                                        final Object... queryParameters) throws MuleException
    {
        Validate.isTrue(ENTITY_CARRYING_HTTP_METHODS.contains(httpMethod),
            "Only entity carrying HTTP methods are supported: " + ENTITY_CARRYING_HTTP_METHODS);

        final Map<String, Object> requestProperties = getRequestProperties(httpMethod);

        requestProperties.put(HttpConstants.HEADER_CONTENT_TYPE, MimeTypes.JSON);

        final String json = serializeEntityToJson(entity);

        return sendHttpRequest(uri, json, requestProperties, responseType, expectedStatusCodes,
            queryParameters);
    }

    private String serializeEntityToJson(final Object entity) throws MuleException
    {
        if (entity == null)
        {
            return null;
        }

        try
        {
            return OBJECT_MAPPER.writeValueAsString(entity);
        }
        catch (final IOException ioe)
        {
            throw new DefaultMuleException("Failed to serialize to JSON: " + entity, ioe);
        }
    }

    private <T> T sendHttpRequest(final String uri,
                                  final Object entity,
                                  final Map<String, Object> requestProperties,
                                  final TypeReference<T> responseType,
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

        if (NO_RESPONSE_STATUSES.contains(responseStatusCode))
        {
            return null;
        }

        return deserializeJsonToEntity(responseType, response);
    }

    private <T> T deserializeJsonToEntity(final TypeReference<T> responseType, final MuleMessage response)
        throws DefaultMuleException
    {
        try
        {
            final T entity = OBJECT_MAPPER.readValue((InputStream) response.getPayload(), responseType);

            if (entity instanceof BaseDataExtensible)
            {
                final BaseDataExtensible bde = (BaseDataExtensible) entity;
                bde.setId(StringUtils.substringAfterLast(bde.getSelf(), "/"));
            }

            return entity;
        }
        catch (final IOException ioe)
        {
            throw new DefaultMuleException("Failed to deserialize from JSON: " + response, ioe);
        }
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

    private String getNodeUrl(final long nodeId)
    {
        return serviceRoot.getNode() + "/" + nodeId;
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
     * @param cypherQuery the query to execute
     * @param includeStatistics defines if meta data about the query must be returned
     * @param profile defines if a profile of the executed query must be returned
     * @return a {@link CypherQueryResult}.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public CypherQueryResult runCypherQuery(final CypherQuery cypherQuery,
                                            @Optional @Default("false") final boolean includeStatistics,
                                            @Optional @Default("false") final boolean profile)
        throws MuleException
    {
        return postEntity(serviceRoot.getCypher(), cypherQuery, CYPHER_QUERY_RESULT_TYPE_REFERENCE, SC_OK,
            "includeStats", includeStatistics, "profile", profile);
    }

    /**
     * Get a node.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodeById}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodeById-failIfNotFound}
     * 
     * @param nodeId id of the node to get.
     * @param failIfNotFound if true, an exception will be thrown if the node is not found,
     *            otherwise null will be returned.
     * @return a {@link Node} instance or null.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Node getNodeById(final long nodeId, @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        return getEntity(getNodeUrl(nodeId), NODE_TYPE_REFERENCE, failIfNotFound ? SC_OK : SC_OK_OR_NOT_FOUND);
    }

    /**
     * Create a node.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createNode}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createNode-withProperties}
     * 
     * @param properties the properties of the node.
     * @return the created {@link Node} instance.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Node createNode(@Optional final Map<String, Object> properties) throws MuleException
    {
        return postEntity(serviceRoot.getNode(), properties, NODE_TYPE_REFERENCE, SC_CREATED);
    }

    /**
     * Delete a node.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteNodeById}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteNodeById-failIfNotFound}
     * 
     * @param nodeId id of the node to delete.
     * @param failIfNotFound if true, an exception will be thrown if the node is not found and
     *            couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void deleteNodeById(final long nodeId, @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        deleteNode(getNodeUrl(nodeId), failIfNotFound);
    }

    /**
     * Delete a node.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteNode}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteNode-failIfNotFound}
     * 
     * @param node the {@link Node} to delete.
     * @param failIfNotFound if true, an exception will be thrown if the node is not found and
     *            couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    @Inject
    public void deleteNode(@RefOnly final Node node, @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        deleteNode(node.getSelf(), failIfNotFound);
    }

    private void deleteNode(final String nodeUrl, final boolean failIfNotFound) throws MuleException
    {
        deleteEntity(nodeUrl, failIfNotFound ? SC_NO_CONTENT : SC_NO_CONTENT_OR_NOT_FOUND);
    }

    /**
     * Get a relationship.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getRelationshipById}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:getRelationshipById-failIfNotFound}
     * 
     * @param relationshipId the ID of the relationship to retrieve.
     * @param failIfNotFound if true, an exception will be thrown if the node is not found,
     *            otherwise null will be returned.
     * @return a {@link Relationship} or null.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Relationship getRelationshipById(final long relationshipId,
                                            @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        return getEntity(getRelationshipUrl(relationshipId), RELATIONSHIP_TYPE_REFERENCE,
            failIfNotFound ? SC_OK : SC_OK_OR_NOT_FOUND);
    }

    private String getRelationshipUrl(final long relationshipId)
    {
        // why this horrible hack? see: https://github.com/neo4j/neo4j/issues/848
        return StringUtils.substringBeforeLast(serviceRoot.getNode(), "/node") + "/relationship/"
               + relationshipId;
    }

    /**
     * Create a relationship.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createRelationshipByIds}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:createRelationshipByIds-withProperties}
     * 
     * @param fromNodeId the ID of the node where the relationship starts.
     * @param toNodeId the ID of the node where the relationship ends.
     * @param type the type of relationship.
     * @param properties the properties of the relationship.
     * @return the created {@link Relationship} instance.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Relationship createRelationshipByIds(final long fromNodeId,
                                                final long toNodeId,
                                                final String type,
                                                @Optional final Map<String, Object> properties)
        throws MuleException
    {
        final Data data = convertMapToData(properties);

        final NewRelationship newRelationship = new NewRelationship().withType(type)
            .withTo(getNodeUrl(toNodeId))
            .withData(data);

        return postEntity(getNodeUrl(fromNodeId) + "/relationships", newRelationship,
            RELATIONSHIP_TYPE_REFERENCE, SC_CREATED);
    }

    /**
     * Create a relationship.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createRelationship}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:createRelationship-withProperties}
     * 
     * @param fromNode the node where the relationship starts.
     * @param toNode the node where the relationship ends.
     * @param type the type of relationship.
     * @param properties the properties of the relationship.
     * @return the created {@link Relationship} instance.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Relationship createRelationship(@RefOnly final Node fromNode,
                                           @RefOnly final Node toNode,
                                           final String type,
                                           @Optional final Map<String, Object> properties)
        throws MuleException
    {
        final Data data = convertMapToData(properties);

        final NewRelationship newRelationship = new NewRelationship().withType(type)
            .withTo(toNode.getSelf())
            .withData(data);

        return postEntity(fromNode.getCreateRelationship(), newRelationship, RELATIONSHIP_TYPE_REFERENCE,
            SC_CREATED);
    }

    /**
     * Delete a relationship.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteRelationshipById}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:deleteRelationshipById-failIfNotFound}
     * 
     * @param relationshipId the ID of the relationship to delete.
     * @param failIfNotFound if true, an exception will be thrown if the relationship is not found
     *            and couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void deleteRelationshipById(final long relationshipId,
                                       @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        deleteRelationship(getRelationshipUrl(relationshipId), failIfNotFound);
    }

    /**
     * Delete a relationship.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteRelationship}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:deleteRelationship-failIfNotFound}
     * 
     * @param relationship the {@link Relationship} to delete.
     * @param failIfNotFound if true, an exception will be thrown if the relationship is not found
     *            and couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void deleteRelationship(@RefOnly final Relationship relationship,
                                   @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        deleteRelationship(relationship.getSelf(), failIfNotFound);
    }

    private void deleteRelationship(final String relationshipUrl, final boolean failIfNotFound)
        throws MuleException
    {
        deleteEntity(relationshipUrl, failIfNotFound ? SC_NO_CONTENT : SC_NO_CONTENT_OR_NOT_FOUND);
    }

    /**
     * Set the properties of a relationship.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:setRelationshipProperties}
     * 
     * @param relationship the {@link Relationship} to set properties on.
     * @param properties the properties to set.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void setRelationshipProperties(@RefOnly final Relationship relationship,
                                          final Map<String, Object> properties) throws MuleException
    {
        putEntity(relationship.getProperties(), properties, SC_NO_CONTENT);
    }

    /**
     * Set one property of a relationship.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:setRelationshipProperty}
     * 
     * @param relationship the {@link Relationship} to set a property on.
     * @param key the property key.
     * @param value the property value.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void setRelationshipProperty(@RefOnly final Relationship relationship,
                                        final String key,
                                        final Object value) throws MuleException
    {
        putEntity(StringUtils.replace(relationship.getProperty(), "{key}", key), value, SC_NO_CONTENT);
    }

    /**
     * Get the relationships for a particular {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodeRelationships}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodeRelationships-singleType}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:getNodeRelationships-multipleTypes}
     * 
     * @param node the {@link Node} for which relationships are considered.
     * @param direction the {@link RelationshipDirection} to use.
     * @param types the relationship types to look for.
     * @return a {@link Collection} of {@link Relationship}, which can be empty but never null.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Collection<Relationship> getNodeRelationships(@RefOnly final Node node,
                                                         final RelationshipDirection direction,
                                                         @Optional final List<String> types)
        throws MuleException
    {

        String relationshipsUrl;

        if (CollectionUtils.isEmpty(types))
        {
            relationshipsUrl = direction.getRelationshipsUrl(node);
        }
        else
        {
            final String relationshipsUrlPattern = direction.getTypeRelationshipsUrlPattern(node);
            relationshipsUrl = StringUtils.replace(relationshipsUrlPattern, "{-list|&|types}",
                StringUtils.join(types, '&'));
        }

        return getEntity(relationshipsUrl, RELATIONSHIPS_TYPE_REFERENCE, SC_OK);
    }

    private Data convertMapToData(final Map<String, Object> properties)
    {
        final Data data = new Data();

        if (MapUtils.isNotEmpty(properties))
        {
            data.getAdditionalProperties().putAll(properties);
        }

        return data;
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
