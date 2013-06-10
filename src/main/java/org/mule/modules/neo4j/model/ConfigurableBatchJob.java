/*
 * Copyright (c) MuleSoft, Inc. All rights reserved. http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.md file.
 */

package org.mule.modules.neo4j.model;

import java.util.Map;

import org.mule.util.MapUtils;

/**
 * This class exists only because DevKit+Mule chokes if {@link BatchJob} is used as a
 * <code>@Processor</code> parameter: Mule's auto-transformation tries to deserialize its members as
 * if they were JSON strings because of the Jackson annotations.
 */
public class ConfigurableBatchJob
{
    private BatchJob.Method method;
    private Integer id;
    private String to;
    private Map<String, Object> bodyEntries;

    public BatchJob toBatchJob()
    {
        final BatchJob batchJob = new BatchJob().withId(getId()).withMethod(getMethod()).withTo(getTo());

        if (MapUtils.isNotEmpty(bodyEntries))
        {
            final Data data = new Data();
            data.getAdditionalProperties().putAll(bodyEntries);
            batchJob.setBody(data);
        }

        return batchJob;
    }

    public Map<String, Object> getBodyEntries()
    {
        return bodyEntries;
    }

    public void setBodyEntries(final Map<String, Object> bodyEntries)
    {
        this.bodyEntries = bodyEntries;
    }

    public BatchJob.Method getMethod()
    {
        return method;
    }

    public void setMethod(final BatchJob.Method method)
    {
        this.method = method;
    }

    public Integer getId()
    {
        return id;
    }

    public void setId(final Integer id)
    {
        this.id = id;
    }

    public String getTo()
    {
        return to;
    }

    public void setTo(final String to)
    {
        this.to = to;
    }
}
