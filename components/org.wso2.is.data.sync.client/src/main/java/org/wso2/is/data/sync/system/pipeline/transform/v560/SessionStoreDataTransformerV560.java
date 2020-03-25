/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.is.data.sync.system.pipeline.transform.v560;

import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.identity.application.common.cache.CacheEntry;
import org.wso2.carbon.identity.core.model.IdentityCacheConfig;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.idp.mgt.util.IdPManagementUtil;
import org.wso2.is.data.sync.system.exception.SyncClientException;
import org.wso2.is.data.sync.system.pipeline.JournalEntry;
import org.wso2.is.data.sync.system.pipeline.PipelineContext;
import org.wso2.is.data.sync.system.pipeline.transform.DataTransformer;
import org.wso2.is.data.sync.system.pipeline.transform.VersionAdvice;
import org.wso2.is.data.sync.system.pipeline.transform.model.SessionInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.wso2.is.data.sync.system.util.CommonUtil.getObjectValueFromEntry;
import static org.wso2.is.data.sync.system.util.CommonUtil.isIdentifierNamesMaintainedInLowerCase;
import static org.wso2.is.data.sync.system.util.Constant.CACHE_MANAGER_NAME;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_OPERATION;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_SESSION_OBJECT;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_SESSION_TYPE;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_TENANT_ID;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_TIME_CREATED;
import static org.wso2.is.data.sync.system.util.Constant.DELETE_OPERATION;
import static org.wso2.is.data.sync.system.util.OAuth2Util.updateJournalEntryForSession;

@VersionAdvice(version = "5.6.0", tableName = "IDN_AUTH_SESSION_STORE")
public class SessionStoreDataTransformerV560 implements DataTransformer {

    @Override
    public List<JournalEntry> transform(List<JournalEntry> journalEntryList, PipelineContext context)
            throws SyncClientException {

        boolean isColumnNameInsLowerCase = isIdentifierNamesMaintainedInLowerCase(context.getTargetConnection());

        for (JournalEntry entry : journalEntryList) {

            Long timeCreated = getObjectValueFromEntry(entry, COLUMN_TIME_CREATED, isColumnNameInsLowerCase);
            String operation = getObjectValueFromEntry(entry, COLUMN_OPERATION, isColumnNameInsLowerCase);
            String sessionType = getObjectValueFromEntry(entry, COLUMN_SESSION_TYPE, isColumnNameInsLowerCase);
            int tenantId = getObjectValueFromEntry(entry, COLUMN_TENANT_ID, isColumnNameInsLowerCase);
            byte[] sessionObject = getObjectValueFromEntry(entry, COLUMN_SESSION_OBJECT, isColumnNameInsLowerCase);
            Object blobObject;

            try {
                blobObject = getBlobObject(new ByteArrayInputStream(sessionObject));
            } catch (IOException | ClassNotFoundException e) {
                throw new SyncClientException("Error occurred while deserializing value of the column: "
                        + COLUMN_SESSION_OBJECT, e);
            }

            if (blobObject != null && !StringUtils.equals(operation, DELETE_OPERATION)) {
                long validityPeriodNano = 0L;

                if (blobObject instanceof CacheEntry) {
                    validityPeriodNano = ((CacheEntry) blobObject).getValidityPeriod();
                }

                if (validityPeriodNano == 0L) {
                    validityPeriodNano = getCleanupTimeout(sessionType, tenantId);
                }
                updateJournalEntryForSession(entry, new SessionInfo(timeCreated + validityPeriodNano),
                        isColumnNameInsLowerCase);
            }
        }

        return journalEntryList;
    }

    private long getCleanupTimeout(String type, int tenantId) {

        if (isTempCache(type)) {
            return TimeUnit.MINUTES.toNanos(IdentityUtil.getTempDataCleanUpTimeout());
        } else if (tenantId != MultitenantConstants.INVALID_TENANT_ID) {
            String tenantDomain = IdentityTenantUtil.getTenantDomain(tenantId);
            return TimeUnit.SECONDS.toNanos(IdPManagementUtil.getRememberMeTimeout(tenantDomain));
        } else {
            return TimeUnit.MINUTES.toNanos(IdentityUtil.getCleanUpTimeout());
        }
    }

    private boolean isTempCache(String type) {

        IdentityCacheConfig identityCacheConfig = IdentityUtil.getIdentityCacheConfig(CACHE_MANAGER_NAME, type);

        if (identityCacheConfig != null) {
            return identityCacheConfig.isTemporary();
        }
        return false;
    }

    private Object getBlobObject(InputStream is)
            throws IOException, ClassNotFoundException {

        if (is != null) {
            try (ObjectInput ois = new ObjectInputStream(is)) {
                return ois.readObject();
            }
        }
        return null;
    }
}
