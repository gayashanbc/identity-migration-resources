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

package org.wso2.is.data.sync.system.pipeline.transform.model;

public class SessionInfo {

    private Long expiryTime;

    public SessionInfo(Long expiryTime) {

        this.expiryTime = expiryTime;
    }

    public Long getExpiryTime() {

        return expiryTime;
    }

    public void setExpiryTime(Long expiryTime) {

        this.expiryTime = expiryTime;
    }
}
