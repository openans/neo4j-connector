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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.inject.Inject;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.mule.DefaultMuleEvent;
import org.mule.DefaultMuleMessage;
import org.mule.api.ConnectionException;
import org.mule.api.ConnectionExceptionCode;
import org.mule.api.DefaultMuleException;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.MuleRuntimeException;
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
import org.mule.api.callback.SourceCallback;
import org.mule.api.context.MuleContextAware;
import org.mule.modules.neo4j.model.BaseEntity;
import org.mule.modules.neo4j.model.CypherQuery;
import org.mule.modules.neo4j.model.CypherQueryResult;
import org.mule.modules.neo4j.model.Data;
import org.mule.modules.neo4j.model.Fullpath;
import org.mule.modules.neo4j.model.IndexedNode;
import org.mule.modules.neo4j.model.NewNodeIndex;
import org.mule.modules.neo4j.model.NewRelationship;
import org.mule.modules.neo4j.model.NewSchemaIndex;
import org.mule.modules.neo4j.model.Node;
import org.mule.modules.neo4j.model.NodeIndex;
import org.mule.modules.neo4j.model.NodeIndexConfiguration;
import org.mule.modules.neo4j.model.NodeIndexingRequest;
import org.mule.modules.neo4j.model.Path;
import org.mule.modules.neo4j.model.Relationship;
import org.mule.modules.neo4j.model.RelationshipQuery;
import org.mule.modules.neo4j.model.SchemaIndex;
import org.mule.modules.neo4j.model.ServiceRoot;
import org.mule.modules.neo4j.model.TraversalQuery;
import org.mule.modules.neo4j.model.TraversalScript;
import org.mule.transformer.types.MimeTypes;
import org.mule.transport.http.HttpConnector;
import org.mule.transport.http.HttpConstants;
import org.mule.util.CaseInsensitiveHashMap;
import org.mule.util.CollectionUtils;
import org.mule.util.MapUtils;
import org.mule.util.StringUtils;

/**
 * <p>
 * Neo4j Connector, for versions 1.9 or above.
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
            public String getRelationshipsUri(final Node node)
            {
                return node.getAllRelationships();
            }

            @Override
            public String getTypeRelationshipsUriPattern(final Node node)
            {
                return node.getAllTypedRelationships();
            }
        },
        IN
        {
            @Override
            public String getRelationshipsUri(final Node node)
            {
                return node.getIncomingRelationships();
            }

            @Override
            public String getTypeRelationshipsUriPattern(final Node node)
            {
                return node.getIncomingTypedRelationships();
            }
        },
        OUT
        {
            @Override
            public String getRelationshipsUri(final Node node)
            {
                return node.getOutgoingRelationships();
            }

            @Override
            public String getTypeRelationshipsUriPattern(final Node node)
            {
                return node.getOutgoingTypedRelationships();
            }
        };

        public abstract String getRelationshipsUri(Node node);

        public abstract String getTypeRelationshipsUriPattern(Node node);
    }

    public static enum QueryResultOrder
    {
        INDEX, RELEVANCE, SCORE
    };

    private static enum TraversalResult
    {
        NODE, RELATIONSHIP, PATH, FULLPATH;
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
    private static final TypeReference<Collection<Node>> NODES_TYPE_REFERENCE = new TypeReference<Collection<Node>>()
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
    private static final TypeReference<Collection<String>> STRINGS_TYPE_REFERENCE = new TypeReference<Collection<String>>()
    {
        // NOOP
    };
    private static final TypeReference<SchemaIndex> SCHEMA_INDEX_TYPE_REFERENCE = new TypeReference<SchemaIndex>()
    {
        // NOOP
    };
    private static final TypeReference<Collection<SchemaIndex>> SCHEMA_INDEXES_TYPE_REFERENCE = new TypeReference<Collection<SchemaIndex>>()
    {
        // NOOP
    };
    private static final TypeReference<NodeIndex> NODE_INDEX_TYPE_REFERENCE = new TypeReference<NodeIndex>()
    {
        // NOOP
    };
    private static final TypeReference<Map<String, Map<String, String>>> NODE_INDEXES_TYPE_REFERENCE = new TypeReference<Map<String, Map<String, String>>>()
    {
        // NOOP
    };
    private static final TypeReference<IndexedNode> INDEXED_NODE_TYPE_REFERENCE = new TypeReference<IndexedNode>()
    {
        // NOOP
    };
    private static final TypeReference<Collection<IndexedNode>> INDEXED_NODES_TYPE_REFERENCE = new TypeReference<Collection<IndexedNode>>()
    {
        // NOOP
    };
    private static final TypeReference<Collection<Path>> PATHS_TYPE_REFERENCE = new TypeReference<Collection<Path>>()
    {
        // NOOP
    };
    private static final TypeReference<Collection<Fullpath>> FULLPATHS_TYPE_REFERENCE = new TypeReference<Collection<Fullpath>>()
    {
        // NOOP
    };

    private static class HttpResponse<T>
    {
        private final T entity;
        private final Map<String, String> headers;

        public HttpResponse(final T entity, final Map<String, String> headers)
        {
            this.entity = entity;
            this.headers = headers;
        }

        public T getEntity()
        {
            return entity;
        }

        public Map<String, String> getHeaders()
        {
            return headers;
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Log LOGGER = LogFactory.getLog(Neo4jConnector.class);

    private static final Set<Integer> SC_OK = Collections.singleton(HttpConstants.SC_OK);
    private static final Set<Integer> SC_CREATED = Collections.singleton(HttpConstants.SC_CREATED);
    private static final Set<Integer> SC_NO_CONTENT = Collections.singleton(HttpConstants.SC_NO_CONTENT);
    private static final Set<Integer> SC_NO_CONTENT_OR_NOT_FOUND = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_NO_CONTENT, HttpConstants.SC_NOT_FOUND)));
    private static final Set<Integer> SC_OK_OR_NOT_FOUND = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_OK, HttpConstants.SC_NOT_FOUND)));
    private static final Set<Integer> SC_OK_OR_NO_CONTENT = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_OK, HttpConstants.SC_NO_CONTENT)));
    private static final Set<Integer> NO_RESPONSE_STATUSES = Collections.unmodifiableSet(new HashSet<Integer>(
        Arrays.asList(HttpConstants.SC_NO_CONTENT, HttpConstants.SC_NOT_FOUND)));
    private static final Set<String> ENTITY_CARRYING_HTTP_METHODS = Collections.unmodifiableSet(new HashSet<String>(
        Arrays.asList(HttpConstants.METHOD_POST, HttpConstants.METHOD_PUT, HttpConstants.METHOD_PATCH)));

    private static final String HEADER_STREAMING = "X-Stream";

    private static final String PROPERTY_KEY_TEMPLATE = "{key}";
    private static final String LABEL_TEMPLATE = "{label}";
    private static final String TYPE_LIST_TEMPLATE = "{-list|&|types}";
    private static final String RETURN_TYPE_TEMPLATE = "{returnType}";
    private static final String PAGINATION_PARAMS_TEMPLATE = "{?pageSize,leaseTime}";

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

            // this hack courtesy of: https://github.com/neo4j/neo4j/issues/848
            final String serviceRootSelf = StringUtils.substringBeforeLast(serviceRoot.getNode(), "/node");
            serviceRoot.setRelationship(serviceRootSelf + "/relationship");

            if (!isBeforeVersion2())
            {
                // and these ones of: https://github.com/neo4j/neo4j/issues/850
                serviceRoot.setNodeLabels(serviceRootSelf + "/labels");
                serviceRoot.setLabelNodes(serviceRootSelf + "/label/" + LABEL_TEMPLATE + "/nodes");

                // and this one of: https://github.com/neo4j/neo4j/issues/857
                serviceRoot.setSchemaIndex(serviceRootSelf + "/schema/index/" + LABEL_TEMPLATE);
            }
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

    private String getNodeUri(final long nodeId)
    {
        return serviceRoot.getNode() + "/" + nodeId;
    }

    private String getRelationshipUri(final long relationshipId)
    {
        return serviceRoot.getRelationship() + "/" + relationshipId;
    }

    private String getSchemaIndexUri(final String label)
    {
        return StringUtils.replace(serviceRoot.getSchemaIndex(), LABEL_TEMPLATE, label);
    }

    private String getNodeIndexUri(final String indexName)
    {
        return serviceRoot.getNodeIndex() + "/" + indexName;
    }

    private <T> T getEntity(final String uri,
                            final TypeReference<T> responseType,
                            final Set<Integer> expectedStatusCodes,
                            final Object... queryParameters) throws MuleException
    {
        return sendHttpRequest(uri, null, getRequestProperties(HttpConstants.METHOD_GET), responseType,
            expectedStatusCodes, queryParameters).getEntity();
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
            expectedStatusCodes, queryParameters).getEntity();
    }

    private void putEntity(final String uri,
                           final Object entity,
                           final Set<Integer> expectedStatusCodes,
                           final Object... queryParameters) throws MuleException
    {
        sendRequestWithEntity(HttpConstants.METHOD_PUT, uri, entity, null, expectedStatusCodes,
            queryParameters);
    }

    private <T> HttpResponse<T> sendRequestWithEntity(final String httpMethod,
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

    private <T> HttpResponse<T> sendHttpRequest(final String uri,
                                                final String jsonEntityOrNull,
                                                final Map<String, Object> requestProperties,
                                                final TypeReference<T> responseType,
                                                final Set<Integer> expectedStatusCodes,
                                                final Object... queryParameters) throws MuleException
    {
        final String fullUri = buildUri(uri, queryParameters);

        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug(String.format(
                "Sending HTTP request:%n  URI: %s%n  JSON Entity: %s%n  Request Properties: %s%n"
                                + "  Response Type: %s%n  Expected Status Codes: %s", fullUri,
                jsonEntityOrNull, requestProperties, responseType, expectedStatusCodes));
        }

        final MuleMessage response = muleContext.getClient().send(fullUri, jsonEntityOrNull,
            requestProperties);

        @SuppressWarnings("unchecked")
        final Map<String, String> responseHeaders = new CaseInsensitiveHashMap();
        for (final String headerName : response.getInboundPropertyNames())
        {
            if (HttpConstants.RESPONSE_HEADER_NAMES.containsKey(headerName))
            {
                responseHeaders.put(headerName, response.<String> getInboundProperty(headerName));
            }
        }

        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug("Received HTTP response: " + response);
        }

        final Integer responseStatusCode = Integer.valueOf((String) response.getInboundProperty(HttpConnector.HTTP_STATUS_PROPERTY));

        if (!expectedStatusCodes.contains(responseStatusCode))
        {
            if (LOGGER.isDebugEnabled())
            {
                try
                {
                    LOGGER.debug("Received payload with unexpected status: " + response.getPayloadAsString());
                }
                catch (final Exception e)
                {
                    // not much to do
                }
            }

            throw new DefaultMuleException("Received status code: " + responseStatusCode
                                           + " but was expecting: " + expectedStatusCodes);
        }

        if (NO_RESPONSE_STATUSES.contains(responseStatusCode))
        {
            return new HttpResponse<T>(null, responseHeaders);
        }
        else
        {
            final T entity = deserializeJsonToEntity(responseType, response);
            return new HttpResponse<T>(entity, responseHeaders);
        }
    }

    private <T> T deserializeJsonToEntity(final TypeReference<T> responseType, final MuleMessage response)
        throws DefaultMuleException
    {
        try
        {
            final T entity = OBJECT_MAPPER.readValue((InputStream) response.getPayload(), responseType);

            if (entity instanceof BaseEntity)
            {
                final BaseEntity baseEntity = (BaseEntity) entity;
                baseEntity.setId(StringUtils.substringAfterLast(baseEntity.getSelf(), "/"));
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

        final Map<String, String> queryParams = new HashMap<String, String>();

        if (connector != null)
        {
            queryParams.put("connector", connector.getName());
        }

        for (int i = 0; i < queryParameters.length; i += 2)
        {
            final String name = (String) queryParameters[i];
            final Object value = queryParameters[i + 1];

            if ((StringUtils.isNotBlank(name)) && (value != null))
            {
                queryParams.put(name, value.toString());
            }
        }

        if (queryParams.isEmpty())
        {
            return uri;
        }

        final StringBuilder queryBuilder = new StringBuilder();
        for (final Entry<String, String> queryParam : queryParams.entrySet())
        {
            queryBuilder.append(queryBuilder.length() != 0 ? "&" : "?")
                .append(urlEncode(queryParam.getKey()))
                .append("=")
                .append(urlEncode(queryParam.getValue()));
        }

        return uri + queryBuilder.toString();
    }

    private static String urlEncode(final String s)
    {
        try
        {
            return URLEncoder.encode(s, "UTF-8");
        }
        catch (final UnsupportedEncodingException uee)
        {
            throw new MuleRuntimeException(uee);
        }
    }

    private Map<String, Object> getRequestProperties(final String method)
    {
        final Map<String, Object> properties = new HashMap<String, Object>();

        // TODO uncomment when https://github.com/neo4j/neo4j/issues/862 is fixed
        // properties.put(HttpConstants.HEADER_ACCEPT, MimeTypes.JSON);

        properties.put(HttpConnector.HTTP_METHOD_PROPERTY, method);
        properties.put(HEADER_STREAMING, streaming);

        if (StringUtils.isNotBlank(authorization))
        {
            properties.put(HttpConstants.HEADER_AUTHORIZATION, authorization);
        }

        return properties;
    }

    private void deleteEntity(final BaseEntity entity, final boolean failIfNotFound) throws MuleException
    {
        deleteEntityByUri(entity.getSelf(), failIfNotFound);
    }

    private void deleteEntityByUri(final String entityUri, final boolean failIfNotFound) throws MuleException
    {
        deleteEntity(entityUri, failIfNotFound ? SC_NO_CONTENT : SC_NO_CONTENT_OR_NOT_FOUND);
    }

    private void setPropertiesOnEntity(final Map<String, Object> properties, final BaseEntity entity)
        throws MuleException
    {
        putEntity(entity.getProperties(), properties, SC_NO_CONTENT);
    }

    private void setPropertyOnEntity(final String key, final Object value, final BaseEntity entity)
        throws MuleException
    {
        putEntity(StringUtils.replace(entity.getProperty(), PROPERTY_KEY_TEMPLATE, key), value, SC_NO_CONTENT);
    }

    private void deletePropertiesFromEntity(final BaseEntity entity) throws MuleException
    {
        deleteEntity(entity.getProperties(), SC_NO_CONTENT);
    }

    private void deletePropertyFromEntity(final String key,
                                          final BaseEntity entity,
                                          final boolean failIfNotFound) throws MuleException
    {
        deleteEntity(StringUtils.replace(entity.getProperty(), PROPERTY_KEY_TEMPLATE, key),
            failIfNotFound ? SC_NO_CONTENT : SC_NO_CONTENT_OR_NOT_FOUND);
    }

    private static Data convertMapToData(final Map<String, Object> properties)
    {
        final Data data = new Data();

        if (MapUtils.isNotEmpty(properties))
        {
            data.getAdditionalProperties().putAll(properties);
        }

        return data;
    }

    private boolean isBeforeVersion2()
    {
        return serviceRoot.getNeo4jVersion().compareTo("2") < 0;
    }

    private void ensureVersion2OrAbove() throws DefaultMuleException
    {
        if (isBeforeVersion2())
        {
            throw new DefaultMuleException("This feature is only available with Neo4j version 2.0 or above");
        }
    }

    private void logDeprecatedIn2OrAbove(final String method)
    {
        if (!isBeforeVersion2())
        {
            LOGGER.info(method + " is deprecated in Neo4j version 2.0 or above");
        }
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
     * Get a {@link Node}.
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
        return getEntity(getNodeUri(nodeId), NODE_TYPE_REFERENCE, failIfNotFound ? SC_OK : SC_OK_OR_NOT_FOUND);
    }

    /**
     * Create a {@link Node}.
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
     * Set the properties of a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:setNodeProperties}
     * 
     * @param node the {@link Node} to set properties on.
     * @param properties the properties of the node.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void setNodeProperties(@RefOnly final Node node, final Map<String, Object> properties)
        throws MuleException
    {
        setPropertiesOnEntity(properties, node);
    }

    /**
     * Set a property of a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:setNodeProperty}
     * 
     * @param node the {@link Node} to set the property on.
     * @param key the key of the property.
     * @param value the value of the property.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void setNodeProperty(@RefOnly final Node node, final String key, final Object value)
        throws MuleException
    {
        setPropertyOnEntity(key, value, node);
    }

    /**
     * Delete a property from a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteNodeProperty}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:deleteNodeProperty-failIfNotFound}
     * 
     * @param node the {@link Node} to delete the property from.
     * @param key the key of the property.
     * @param failIfNotFound if true, an exception will be thrown if the property is not found and
     *            couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void deleteNodeProperty(@RefOnly final Node node,
                                   final String key,
                                   @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        deletePropertyFromEntity(key, node, failIfNotFound);
    }

    /**
     * Delete all properties from a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteNodeProperties}
     * 
     * @param node the {@link Node} to delete properties from.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void deleteNodeProperties(@RefOnly final Node node) throws MuleException
    {
        deletePropertiesFromEntity(node);
    }

    /**
     * Delete a {@link Node}.
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
        deleteEntityByUri(getNodeUri(nodeId), failIfNotFound);
    }

    /**
     * Delete a {@link Node}.
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
        deleteEntity(node, failIfNotFound);
    }

    /**
     * Get a {@link Relationship}.
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
        return getEntity(getRelationshipUri(relationshipId), RELATIONSHIP_TYPE_REFERENCE,
            failIfNotFound ? SC_OK : SC_OK_OR_NOT_FOUND);
    }

    /**
     * Create a {@link Relationship}.
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
            .withTo(getNodeUri(toNodeId))
            .withData(data);

        return postEntity(getNodeUri(fromNodeId) + "/relationships", newRelationship,
            RELATIONSHIP_TYPE_REFERENCE, SC_CREATED);
    }

    /**
     * Create a {@link Relationship}.
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
     * Delete a {@link Relationship}.
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
        deleteEntityByUri(getRelationshipUri(relationshipId), failIfNotFound);
    }

    /**
     * Delete a {@link Relationship}.
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
        deleteEntity(relationship, failIfNotFound);
    }

    /**
     * Set the properties of a {@link Relationship}.
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
        setPropertiesOnEntity(properties, relationship);
    }

    /**
     * Set one property of a {@link Relationship}.
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
        setPropertyOnEntity(key, value, relationship);
    }

    /**
     * Delete one property of a {@link Relationship}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteRelationshipProperty}
     * 
     * @param relationship the {@link Relationship} to delete from.
     * @param key the key of the property.
     * @param failIfNotFound if true, an exception will be thrown if the property is not found and
     *            couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void deleteRelationshipProperty(@RefOnly final Relationship relationship,
                                           final String key,
                                           @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        deletePropertyFromEntity(key, relationship, failIfNotFound);
    }

    /**
     * Delete all properties of a {@link Relationship}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteRelationshipProperties}
     * 
     * @param relationship the {@link Relationship} to delete from.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public void deleteRelationshipProperties(@RefOnly final Relationship relationship) throws MuleException
    {
        deletePropertiesFromEntity(relationship);
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

        String relationshipsUri;

        if (CollectionUtils.isEmpty(types))
        {
            relationshipsUri = direction.getRelationshipsUri(node);
        }
        else
        {
            final String relationshipsUriPattern = direction.getTypeRelationshipsUriPattern(node);
            relationshipsUri = StringUtils.replace(relationshipsUriPattern, TYPE_LIST_TEMPLATE,
                StringUtils.join(types, '&'));
        }

        return getEntity(relationshipsUri, RELATIONSHIPS_TYPE_REFERENCE, SC_OK);
    }

    /**
     * Get all the relationship types.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getRelationshipTypes}
     * 
     * @return a {@link Collection} of {@link String}, which can be empty but never null.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Collection<String> getRelationshipTypes() throws MuleException
    {
        return getEntity(getServiceRoot().getRelationshipTypes(), STRINGS_TYPE_REFERENCE, SC_OK);
    }

    /**
     * Add a label to a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:addNodeLabel}
     * 
     * @param node the {@link Node} to add a label to.
     * @param label the label to add.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public void addNodeLabel(@RefOnly final Node node, final String label) throws MuleException
    {
        ensureVersion2OrAbove();

        postEntity(node.getLabels(), label, null, SC_NO_CONTENT);
    }

    /**
     * Add labels to a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:addNodeLabels}
     * 
     * @param node the {@link Node} to add labels to.
     * @param labels a {@link List} of labels to add.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public void addNodeLabels(@RefOnly final Node node, final List<String> labels) throws MuleException
    {
        ensureVersion2OrAbove();

        postEntity(node.getLabels(), labels, null, SC_NO_CONTENT);
    }

    /**
     * Set labels of a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:setNodeLabels}
     * 
     * @param node the {@link Node} to set labels of.
     * @param labels a {@link List} of labels to set.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public void setNodeLabels(@RefOnly final Node node, final List<String> labels) throws MuleException
    {
        ensureVersion2OrAbove();

        putEntity(node.getLabels(), labels, SC_NO_CONTENT);
    }

    /**
     * Delete a label from a node, never failing even if the label doesn't exist.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteNodeLabel}
     * 
     * @param node the {@link Node} to delete the label from.
     * @param label the label to delete.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public void deleteNodeLabel(@RefOnly final Node node, final String label) throws MuleException
    {
        ensureVersion2OrAbove();

        deleteEntityByUri(node.getLabels() + "/" + label, true);
    }

    /**
     * Get all the labels of a {@link Node}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodeLabels}
     * 
     * @param node the {@link Node} from which to get the labels.
     * @return a {@link Collection} of {@link String} representing the labels, never null but
     *         possible empty.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public Collection<String> getNodeLabels(@RefOnly final Node node) throws MuleException
    {
        ensureVersion2OrAbove();

        return getEntity(node.getLabels(), STRINGS_TYPE_REFERENCE, SC_OK);
    }

    /**
     * Get all the {@link Node}s that have a particular label and, optional, a particular property.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodesByLabel}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodesByLabel-property}
     * 
     * @param label the label to use when searching for nodes.
     * @param propertyName the property name to use when searching for nodes.
     * @param propertyValue the property value to use when searching for nodes.
     * @return a {@link Collection} of {@link Node}, never null but possibly empty.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public Collection<Node> getNodesByLabel(final String label,
                                            @Optional final String propertyName,
                                            @Optional final Object propertyValue) throws MuleException
    {
        ensureVersion2OrAbove();

        final String uri = StringUtils.replace(serviceRoot.getLabelNodes(), LABEL_TEMPLATE, label);

        return getEntity(uri, NODES_TYPE_REFERENCE, SC_OK_OR_NOT_FOUND, propertyName,
            serializeEntityToJson(propertyValue));
    }

    /**
     * Get all the labels.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getLabels}
     * 
     * @return a {@link Collection} of {@link String} labels, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public Collection<String> getLabels() throws MuleException
    {
        ensureVersion2OrAbove();

        return getEntity(getServiceRoot().getNodeLabels(), STRINGS_TYPE_REFERENCE, SC_OK);
    }

    /**
     * Create a {@link SchemaIndex}.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createSchemaIndex}
     * 
     * @param label the label to create the index for.
     * @param propertyKeys the property key or keys to index.
     * @return the created {@link SchemaIndex}.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public SchemaIndex createSchemaIndex(final String label, final List<String> propertyKeys)
        throws MuleException
    {
        ensureVersion2OrAbove();

        Validate.notEmpty(propertyKeys, "propertyKeys can not be empty");

        return postEntity(getSchemaIndexUri(label), new NewSchemaIndex().withPropertyKeys(propertyKeys),
            SCHEMA_INDEX_TYPE_REFERENCE, SC_OK);
    }

    /**
     * Get the {@link SchemaIndex}es for a particular label.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getSchemaIndexes}
     * 
     * @param label the label to consider.
     * @return a {@link Collection} of {@link SchemaIndex} instances, never null but possibly empty
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public Collection<SchemaIndex> getSchemaIndexes(final String label) throws MuleException
    {
        ensureVersion2OrAbove();

        return getEntity(getSchemaIndexUri(label), SCHEMA_INDEXES_TYPE_REFERENCE, SC_OK);
    }

    /**
     * Delete a schema index.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteSchemaIndex}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:deleteSchemaIndex-failIfNotFound}
     * 
     * @param label the label to delete the schema index from.
     * @param propertyKey the property key to delete the schema index for.
     * @param failIfNotFound if true, an exception will be thrown if the schema index is not found
     *            and couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     * @since Neo4j 2.0.0
     */
    @Processor
    public void deleteSchemaIndex(final String label,
                                  final String propertyKey,
                                  @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        ensureVersion2OrAbove();

        deleteEntityByUri(getSchemaIndexUri(label) + "/" + propertyKey, failIfNotFound);
    }

    /**
     * Create a node index.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:createNodeIndex}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:createNodeIndex-withConfiguration}
     * 
     * @param indexName the name of the new node index to create.
     * @param type the type of the new node index.
     * @param provider the provider for the new node index.
     * @return the created {@link NodeIndex}.
     * @throws MuleException if anything goes wrong with the operation.
     * @Deprecated since Neo4j 2.0.0
     */
    @Processor
    public NodeIndex createNodeIndex(final String indexName,
                                     @Optional final String type,
                                     @Optional final String provider) throws MuleException
    {
        logDeprecatedIn2OrAbove("createNodeIndex");

        final NewNodeIndex newNodeIndex = new NewNodeIndex().withName(indexName);

        if ((StringUtils.isNotBlank(type)) || (StringUtils.isNotBlank(provider)))
        {
            newNodeIndex.setConfig(new NodeIndexConfiguration().withType(type).withProvider(provider));
        }

        final NodeIndex nodeIndex = postEntity(serviceRoot.getNodeIndex(), newNodeIndex,
            NODE_INDEX_TYPE_REFERENCE, SC_CREATED);
        nodeIndex.setName(indexName);
        return nodeIndex;
    }

    /**
     * Delete a node index.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:deleteSchemaIndex}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:deleteSchemaIndex-failIfNotFound}
     * 
     * @param indexName the name of the node index to delete.
     * @param failIfNotFound if true, an exception will be thrown if the node index is not found and
     *            couldn't be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     * @Deprecated since Neo4j 2.0.0
     */
    @Processor
    public void deleteNodeIndex(final String indexName,
                                @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        logDeprecatedIn2OrAbove("deleteNodeIndex");

        deleteEntityByUri(getNodeIndexUri(indexName), failIfNotFound);
    }

    /**
     * Get all the node indexes.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:getNodeIndexes}
     * 
     * @return a {@link Collection} of {@link NodeIndex}es, never null but can be empty.
     * @throws MuleException if anything goes wrong with the operation.
     * @Deprecated since Neo4j 2.0.0
     */
    @Processor
    public Collection<NodeIndex> getNodeIndexes() throws MuleException
    {
        logDeprecatedIn2OrAbove("getNodeIndexes");

        // the Neo4j returns an object instead of an array for the list of indexes
        // so we need to manually convert it into a proper collection :(
        final Map<String, Map<String, String>> rawNodeIndexes = getEntity(serviceRoot.getNodeIndex(),
            NODE_INDEXES_TYPE_REFERENCE, SC_OK_OR_NO_CONTENT);

        final List<NodeIndex> nodeIndexes = new ArrayList<NodeIndex>();

        if (MapUtils.isNotEmpty(rawNodeIndexes))
        {
            for (final Entry<String, Map<String, String>> rawNodeIndex : rawNodeIndexes.entrySet())
            {
                final NodeIndex nodeIndex = new NodeIndex().withName(rawNodeIndex.getKey());
                nodeIndex.setTemplate(rawNodeIndex.getValue().get("template"));
                nodeIndex.setProvider(rawNodeIndex.getValue().get("provider"));
                nodeIndex.setType(rawNodeIndex.getValue().get("type"));
                nodeIndexes.add(nodeIndex);
            }
        }

        return nodeIndexes;
    }

    /**
     * Add a {@link Node} to an index.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:addNodeToIndex}
     * 
     * @param indexName the name of the index to add the node to.
     * @param node the node to add.
     * @param key the key to use with the index entry.
     * @param value the value to use with the index entry.
     * @return an {@link IndexedNode} instance.
     * @throws MuleException if anything goes wrong with the operation.
     * @Deprecated since Neo4j 2.0.0
     */
    @Processor
    public IndexedNode addNodeToIndex(final String indexName,
                                      @RefOnly final Node node,
                                      final String key,
                                      final String value) throws MuleException
    {
        logDeprecatedIn2OrAbove("addNodeToIndex");

        final NodeIndexingRequest nodeIndexingRequest = new NodeIndexingRequest().withKey(key)
            .withValue(value)
            .withUri(node.getSelf());

        return postEntity(getNodeIndexUri(indexName), nodeIndexingRequest, INDEXED_NODE_TYPE_REFERENCE,
            SC_CREATED);
    }

    /**
     * Remove node index entries.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:removeNodeIndexEntries}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:removeNodeIndexEntries-failIfNotFound}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:removeNodeIndexEntries-key}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:removeNodeIndexEntries-keyAndValue}
     * 
     * @param indexName the name of the index to remove entries from.
     * @param node the node for which entries will be removed.
     * @param key the key for which entries will be removed.
     * @param value the value for which entries will be removed.
     * @param failIfNotFound if true, an exception will be thrown if no index entry can be deleted.
     * @throws MuleException if anything goes wrong with the operation.
     * @Deprecated since Neo4j 2.0.0
     */
    @Processor
    public void removeNodeIndexEntries(final String indexName,
                                       @RefOnly final Node node,
                                       @Optional final String key,
                                       @Optional final String value,
                                       @Optional @Default("false") final boolean failIfNotFound)
        throws MuleException
    {
        logDeprecatedIn2OrAbove("removeNodeIndexEntries");

        final StringBuilder uriBuilder = new StringBuilder(getNodeIndexUri(indexName));

        if (StringUtils.isNotBlank(key))
        {
            uriBuilder.append("/").append(key);

            if (StringUtils.isNotBlank(value))
            {
                uriBuilder.append("/").append(value);
            }
        }

        uriBuilder.append("/").append(node.getId());

        deleteEntityByUri(uriBuilder.toString(), failIfNotFound);
    }

    /**
     * Find nodes by exact index match.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:findNodesByIndex}
     * 
     * @param indexName the name of the index to use for the search.
     * @param key the key to use.
     * @param value the value to use.
     * @return a {@link Collection} of {@link IndexedNode}s, never null but possibly empty.
     * @throws MuleException if anything goes wrong with the operation.
     * @Deprecated since Neo4j 2.0.0
     */
    @Processor
    public Collection<IndexedNode> findNodesByIndex(final String indexName,
                                                    final String key,
                                                    final String value) throws MuleException
    {
        logDeprecatedIn2OrAbove("findNodesByIndex");

        return getEntity(getNodeIndexUri(indexName) + "/" + key + "/" + value, INDEXED_NODES_TYPE_REFERENCE,
            SC_OK);
    }

    /**
     * Find nodes by index queyr.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:findNodesByQuery}
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:findNodesByQuery-order}
     * 
     * @param indexName the name of the index to use for the search.
     * @param query the query to run.
     * @param order the desired {@link QueryResultOrder}.
     * @return a {@link Collection} of {@link IndexedNode}s, never null but possibly empty.
     * @throws MuleException if anything goes wrong with the operation.
     * @Deprecated since Neo4j 2.0.0
     */
    @Processor
    public Collection<IndexedNode> findNodesByQuery(final String indexName,
                                                    final String query,
                                                    @Optional final QueryResultOrder order)
        throws MuleException
    {
        logDeprecatedIn2OrAbove("findNodesByIndex");

        return getEntity(getNodeIndexUri(indexName), INDEXED_NODES_TYPE_REFERENCE, SC_OK, "query", query,
            "order", order == null ? null : order.toString().toLowerCase());
    }

    private <T> Collection<T> traverse(final Node node,
                                       final TraversalQuery.Order order,
                                       final TraversalQuery.Uniqueness uniqueness,
                                       final Integer maxDepth,
                                       final List<RelationshipQuery> relationships,
                                       final TraversalScript returnFilter,
                                       final TraversalScript pruneEvaluator,
                                       final TraversalResult traversalResult,
                                       final TypeReference<Collection<T>> responseType) throws MuleException
    {
        final String traverseUri = StringUtils.replace(node.getTraverse(), RETURN_TYPE_TEMPLATE,
            traversalResult.toString().toLowerCase());

        final TraversalQuery traversalQuery = new TraversalQuery().withOrder(order)
            .withUniqueness(uniqueness)
            .withMaxDepth(maxDepth)
            .withRelationships(relationships)
            .withPruneEvaluator(pruneEvaluator)
            .withReturnFilter(returnFilter);

        return postEntity(traverseUri, traversalQuery, responseType, SC_OK);
    }

    /**
     * Perform a node traversal, returning {@link Node} instances.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:traverseForNodes}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @return a {@link Collection} of {@link Node}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Collection<Node> traverseForNodes(@RefOnly final Node node,
                                             final TraversalQuery.Order order,
                                             final TraversalQuery.Uniqueness uniqueness,
                                             @Optional final Integer maxDepth,
                                             @Optional final List<RelationshipQuery> relationships,
                                             @Optional final TraversalScript returnFilter,
                                             @Optional final TraversalScript pruneEvaluator)
        throws MuleException
    {
        return traverse(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            TraversalResult.NODE, NODES_TYPE_REFERENCE);
    }

    /**
     * Perform a node traversal, returning {@link Relationship} instances.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:traverseForRelationships}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @return a {@link Collection} of {@link Relationship}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Collection<Relationship> traverseForRelationships(@RefOnly final Node node,
                                                             final TraversalQuery.Order order,
                                                             final TraversalQuery.Uniqueness uniqueness,
                                                             @Optional final Integer maxDepth,
                                                             @Optional final List<RelationshipQuery> relationships,
                                                             @Optional final TraversalScript returnFilter,
                                                             @Optional final TraversalScript pruneEvaluator)
        throws MuleException
    {
        return traverse(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            TraversalResult.RELATIONSHIP, RELATIONSHIPS_TYPE_REFERENCE);
    }

    /**
     * Perform a node traversal, returning {@link Path} instances.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:traverseForPaths}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @return a {@link Collection} of {@link Path}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Collection<Path> traverseForPaths(@RefOnly final Node node,
                                             final TraversalQuery.Order order,
                                             final TraversalQuery.Uniqueness uniqueness,
                                             @Optional final Integer maxDepth,
                                             @Optional final List<RelationshipQuery> relationships,
                                             @Optional final TraversalScript returnFilter,
                                             @Optional final TraversalScript pruneEvaluator)
        throws MuleException
    {
        return traverse(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            TraversalResult.PATH, PATHS_TYPE_REFERENCE);
    }

    /**
     * Perform a node traversal, returning {@link Fullpath} instances.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:traverseForFullpaths}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @return a {@link Collection} of {@link Fullpath}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor
    public Collection<Fullpath> traverseForFullpaths(@RefOnly final Node node,
                                                     final TraversalQuery.Order order,
                                                     final TraversalQuery.Uniqueness uniqueness,
                                                     @Optional final Integer maxDepth,
                                                     @Optional final List<RelationshipQuery> relationships,
                                                     @Optional final TraversalScript returnFilter,
                                                     @Optional final TraversalScript pruneEvaluator)
        throws MuleException
    {
        return traverse(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            TraversalResult.FULLPATH, FULLPATHS_TYPE_REFERENCE);
    }

    private <T> void traversePaged(final Node node,
                                   final TraversalQuery.Order order,
                                   final TraversalQuery.Uniqueness uniqueness,
                                   final Integer maxDepth,
                                   final List<RelationshipQuery> relationships,
                                   final TraversalScript returnFilter,
                                   final TraversalScript pruneEvaluator,
                                   final int pageSize,
                                   final int leaseTimeSeconds,
                                   final MuleEvent muleEvent,
                                   final SourceCallback sourceCallback,
                                   final TraversalResult traversalResult,
                                   final TypeReference<Collection<T>> responseType) throws MuleException
    {
        final String pagedTraverseUri = StringUtils.replace(node.getPagedTraverse(),
            RETURN_TYPE_TEMPLATE + PAGINATION_PARAMS_TEMPLATE, traversalResult.toString().toLowerCase());

        final TraversalQuery traversalQuery = new TraversalQuery().withOrder(order)
            .withUniqueness(uniqueness)
            .withMaxDepth(maxDepth)
            .withRelationships(relationships)
            .withPruneEvaluator(pruneEvaluator)
            .withReturnFilter(returnFilter);

        final HttpResponse<Collection<T>> httpResponse = sendRequestWithEntity(HttpConstants.METHOD_POST,
            pagedTraverseUri, traversalQuery, responseType, SC_CREATED, "pageSize", pageSize, "leaseTime",
            leaseTimeSeconds);

        // dispatch the initial response
        final DefaultMuleEvent initialResponseEvent = new DefaultMuleEvent(new DefaultMuleMessage(
            httpResponse.getEntity(), muleEvent.getMessage(), muleContext), muleEvent);

        sourceCallback.processEvent(initialResponseEvent);

        // fetch and dispatch the next pages until 404
        final String nextPageUri = httpResponse.getHeaders().get(HttpConstants.HEADER_LOCATION);

        Collection<T> nextPage;
        while ((nextPage = getEntity(nextPageUri, responseType, SC_OK_OR_NOT_FOUND)) != null)
        {
            final DefaultMuleEvent nextPageResponseEvent = new DefaultMuleEvent(new DefaultMuleMessage(
                nextPage, muleEvent.getMessage(), muleContext), muleEvent);

            sourceCallback.processEvent(nextPageResponseEvent);
        }
    }

    /**
     * Perform a paged node traversal, dispatching {@link Node} instances to the rest of the flow.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:traverseForNodesWithPaging}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @param pageSize the size of the result page.
     * @param leaseTimeSeconds the time during which the paged results will be accessible.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @param sourceCallback the {@link SourceCallback} invoked for each result page.
     * @return a {@link Collection} of {@link Node}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor(intercepting = true)
    @Inject
    public void traverseForNodesWithPaging(@RefOnly final Node node,
                                           final TraversalQuery.Order order,
                                           final TraversalQuery.Uniqueness uniqueness,
                                           @Optional final Integer maxDepth,
                                           @Optional final List<RelationshipQuery> relationships,
                                           @Optional final TraversalScript returnFilter,
                                           @Optional final TraversalScript pruneEvaluator,
                                           @Optional @Default("50") final int pageSize,
                                           @Optional @Default("60") final int leaseTimeSeconds,
                                           final MuleEvent muleEvent,
                                           final SourceCallback sourceCallback) throws MuleException
    {
        traversePaged(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            pageSize, leaseTimeSeconds, muleEvent, sourceCallback, TraversalResult.NODE, NODES_TYPE_REFERENCE);
    }

    /**
     * Perform a paged node traversal, dispatching {@link Relationship} instances to the rest of the
     * flow.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample
     * neo4j:traverseForRelationshipsWithPaging}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @param pageSize the size of the result page.
     * @param leaseTimeSeconds the time during which the paged results will be accessible.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @param sourceCallback the {@link SourceCallback} invoked for each result page.
     * @return a {@link Collection} of {@link Node}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor(intercepting = true)
    @Inject
    public void traverseForRelationshipsWithPaging(@RefOnly final Node node,
                                                   final TraversalQuery.Order order,
                                                   final TraversalQuery.Uniqueness uniqueness,
                                                   @Optional final Integer maxDepth,
                                                   @Optional final List<RelationshipQuery> relationships,
                                                   @Optional final TraversalScript returnFilter,
                                                   @Optional final TraversalScript pruneEvaluator,
                                                   @Optional @Default("50") final int pageSize,
                                                   @Optional @Default("60") final int leaseTimeSeconds,
                                                   final MuleEvent muleEvent,
                                                   final SourceCallback sourceCallback) throws MuleException
    {
        traversePaged(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            pageSize, leaseTimeSeconds, muleEvent, sourceCallback, TraversalResult.RELATIONSHIP,
            RELATIONSHIPS_TYPE_REFERENCE);
    }

    /**
     * Perform a paged node traversal, dispatching {@link Path} instances to the rest of the flow.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:traverseForPathsWithPaging}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @param pageSize the size of the result page.
     * @param leaseTimeSeconds the time during which the paged results will be accessible.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @param sourceCallback the {@link SourceCallback} invoked for each result page.
     * @return a {@link Collection} of {@link Node}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor(intercepting = true)
    @Inject
    public void traverseForPathsWithPaging(@RefOnly final Node node,
                                           final TraversalQuery.Order order,
                                           final TraversalQuery.Uniqueness uniqueness,
                                           @Optional final Integer maxDepth,
                                           @Optional final List<RelationshipQuery> relationships,
                                           @Optional final TraversalScript returnFilter,
                                           @Optional final TraversalScript pruneEvaluator,
                                           @Optional @Default("50") final int pageSize,
                                           @Optional @Default("60") final int leaseTimeSeconds,
                                           final MuleEvent muleEvent,
                                           final SourceCallback sourceCallback) throws MuleException
    {
        traversePaged(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            pageSize, leaseTimeSeconds, muleEvent, sourceCallback, TraversalResult.PATH, PATHS_TYPE_REFERENCE);
    }

    /**
     * Perform a paged node traversal, dispatching {@link Fullpath} instances to the rest of the
     * flow.
     * <p>
     * {@sample.xml ../../../doc/mule-module-neo4j.xml.sample neo4j:traverseForFullpathsWithPaging}
     * 
     * @param node the start {@link Node}.
     * @param order the order to visit the nodes.
     * @param uniqueness how uniquess should be calculated.
     * @param maxDepth the maximum depth from the start node after which results must be pruned.
     * @param relationships the relationship types and directions that must be followed.
     * @param returnFilter a filter that determines if the current position should be included in
     *            the result.
     * @param pruneEvaluator an evaluator that determines of traversal should stop or continue.
     * @param pageSize the size of the result page.
     * @param leaseTimeSeconds the time during which the paged results will be accessible.
     * @param muleEvent the {@link MuleEvent} being processed.
     * @param sourceCallback the {@link SourceCallback} invoked for each result page.
     * @return a {@link Collection} of {@link Node}, never null but potentially empty.
     * @throws MuleException if anything goes wrong with the operation.
     */
    @Processor(intercepting = true)
    @Inject
    public void traverseForFullpathsWithPaging(@RefOnly final Node node,
                                               final TraversalQuery.Order order,
                                               final TraversalQuery.Uniqueness uniqueness,
                                               @Optional final Integer maxDepth,
                                               @Optional final List<RelationshipQuery> relationships,
                                               @Optional final TraversalScript returnFilter,
                                               @Optional final TraversalScript pruneEvaluator,
                                               @Optional @Default("50") final int pageSize,
                                               @Optional @Default("60") final int leaseTimeSeconds,
                                               final MuleEvent muleEvent,
                                               final SourceCallback sourceCallback) throws MuleException
    {
        traversePaged(node, order, uniqueness, maxDepth, relationships, returnFilter, pruneEvaluator,
            pageSize, leaseTimeSeconds, muleEvent, sourceCallback, TraversalResult.FULLPATH,
            FULLPATHS_TYPE_REFERENCE);
    }

    private void refreshAuthorization()
    {
        if ((StringUtils.isEmpty(user)) && (StringUtils.isEmpty(password)))
        {
            return;
        }

        final String userPassword = StringUtils.trimToEmpty(user) + ":" + StringUtils.trimToEmpty(password);
        final byte[] credentialBytes = userPassword.getBytes();
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
