/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.is.data.sync.system.util;

import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth.tokenprocessor.EncryptionDecryptionPersistenceProcessor;
import org.wso2.carbon.identity.oauth.tokenprocessor.HashingPersistenceProcessor;
import org.wso2.carbon.identity.oauth.tokenprocessor.TokenPersistenceProcessor;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.is.data.sync.system.exception.SyncClientException;
import org.wso2.is.data.sync.system.pipeline.EntryField;
import org.wso2.is.data.sync.system.pipeline.JournalEntry;
import org.wso2.is.data.sync.system.pipeline.PipelineContext;
import org.wso2.is.data.sync.system.pipeline.transform.model.AuthorizationCodeInfo;
import org.wso2.is.data.sync.system.pipeline.transform.model.SessionInfo;
import org.wso2.is.data.sync.system.pipeline.transform.model.TokenInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wso2.carbon.core.util.CryptoUtil.getDefaultCryptoUtil;
import static org.wso2.is.data.sync.system.database.SQLQueryProvider.SQL_TEMPLATE_SELECT_SOURCE_IDP_ID;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_ACCESS_TOKEN;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_ACCESS_TOKEN_HASH;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_AUTHORIZATION_CODE;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_AUTHORIZATION_CODE_HASH;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_EXPIRY_TIME;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_IDP_ID;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_REFRESH_TOKEN;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_REFRESH_TOKEN_HASH;
import static org.wso2.is.data.sync.system.util.Constant.COLUMN_TIME_CREATED;

public class OAuth2Util {

    private static final String CIPHER_TRANSFORMATION_SYSTEM_PROPERTY = "org.wso2.CipherTransformation";

    public static boolean isEncryptionWithTransformationEnabled() throws IdentityOAuth2Exception {

        String cipherTransformation = System.getProperty(CIPHER_TRANSFORMATION_SYSTEM_PROPERTY);
        return cipherTransformation != null && isTokenEncryptionEnabled();
    }

    /**
     * This method will check whether token encryption is enabled via identity.xml
     * @return whether token encryption is enabled.
     * @throws IdentityOAuth2Exception
     */
    public static boolean isTokenEncryptionEnabled() throws IdentityOAuth2Exception {

        TokenPersistenceProcessor persistenceProcessor = OAuthServerConfiguration.getInstance()
                                                                                 .getPersistenceProcessor();
        return (persistenceProcessor instanceof EncryptionDecryptionPersistenceProcessor);
    }

    public static boolean isBase64DecodeAndIsSelfContainedCipherText(String text) throws CryptoException {

        return getDefaultCryptoUtil().base64DecodeAndIsSelfContainedCipherText(text);
    }

    public static TokenInfo hashTokens(TokenInfo tokenInfo) throws SyncClientException {

        TokenPersistenceProcessor tokenPersistenceProcessor = new HashingPersistenceProcessor();
        String accessToken;

        String decryptedAccessToken = tokenInfo.getDecryptedAccessToken();
        if (decryptedAccessToken != null) {
            accessToken = decryptedAccessToken;
        } else {
            accessToken = tokenInfo.getAccessToken();
        }
        String refreshToken;
        String decryptedRefreshToken = tokenInfo.getDecryptedRefreshToken();
        if (decryptedRefreshToken != null) {
            refreshToken = decryptedRefreshToken;
        } else {
            refreshToken = tokenInfo.getRefreshToken();
        }
        try {
            String accessTokenHash = tokenPersistenceProcessor.getProcessedAccessTokenIdentifier(accessToken);
            String refreshTokenHash = null;
            if (refreshToken != null) {
                refreshTokenHash = tokenPersistenceProcessor.getProcessedRefreshToken(refreshToken);
            }
            tokenInfo.setAccessTokenHash(accessTokenHash);
            tokenInfo.setRefreshTokenHash(refreshTokenHash);
        } catch (IdentityOAuth2Exception e) {
            throw new SyncClientException("Error while hashing access/refresh token with " +
                                          "HashingPersistenceProcessor.", e);
        }
        return tokenInfo;
    }

    public static AuthorizationCodeInfo hashAuthorizationCode(AuthorizationCodeInfo authorizationCodeInfo) throws
            SyncClientException {

        TokenPersistenceProcessor tokenPersistenceProcessor = new HashingPersistenceProcessor();
        String authorizationCode;

        String decryptedAuthorizationCode = authorizationCodeInfo.getDecryptedAuthorizationCode();
        if (decryptedAuthorizationCode != null) {
            authorizationCode = decryptedAuthorizationCode;
        } else {
            authorizationCode = authorizationCodeInfo.getAuthorizationCode();
        }

        try {
            String authorizationCodeHash = tokenPersistenceProcessor.getProcessedAuthzCode(authorizationCode);
            authorizationCodeInfo.setAuthorizationCodeHash(authorizationCodeHash);
        } catch (IdentityOAuth2Exception e) {
            throw new SyncClientException("Error while hashing access/refresh token with " +
                                          "HashingPersistenceProcessor.", e);
        }
        return authorizationCodeInfo;
    }

    public static TokenInfo transformEncryptedTokens(TokenInfo tokenInfo) throws CryptoException, SyncClientException {

        String accessToken = tokenInfo.getAccessToken();
        String refreshToken = tokenInfo.getRefreshToken();
        if (!isBase64DecodeAndIsSelfContainedCipherText(accessToken)) {
            byte[] decryptedAccessToken = getDefaultCryptoUtil().base64DecodeAndDecrypt(accessToken, "RSA");
            String newEncryptedAccessToken = getDefaultCryptoUtil().encryptAndBase64Encode
                    (decryptedAccessToken);
            byte[] decryptedRefreshToken = null;
            String newEncryptedRefreshToken = null;
            if (refreshToken != null) {
                decryptedRefreshToken = getDefaultCryptoUtil().base64DecodeAndDecrypt(refreshToken, "RSA");
                newEncryptedRefreshToken = getDefaultCryptoUtil().encryptAndBase64Encode(decryptedRefreshToken);
            }

            String decryptedAccessTokenStr = new String(decryptedAccessToken, UTF_8);
            String decryptedRefreshTokenStr = null;
            if (refreshToken != null) {
                decryptedRefreshTokenStr = new String(decryptedRefreshToken, UTF_8);
            }
            tokenInfo.setAccessToken(newEncryptedAccessToken);
            tokenInfo.setRefreshToken(newEncryptedRefreshToken);
            tokenInfo.setDecryptedAccessToken(decryptedAccessTokenStr);
            tokenInfo.setDecryptedRefreshToken(decryptedRefreshTokenStr);

        } else if (isBase64DecodeAndIsSelfContainedCipherText(accessToken)) {

            byte[] decryptedAccessToken = getDefaultCryptoUtil().base64DecodeAndDecrypt(accessToken);
            byte[] decryptedRefreshToken = null;
            if (refreshToken != null) {
                decryptedRefreshToken = getDefaultCryptoUtil().base64DecodeAndDecrypt(refreshToken);
            }
            String decryptedAccessTokenStr = new String(decryptedAccessToken, UTF_8);
            String decryptedRefreshTokenStr = refreshToken != null ? new String(decryptedRefreshToken, UTF_8) :
                    null;
            tokenInfo.setAccessToken(accessToken);
            tokenInfo.setRefreshToken(refreshToken);
            tokenInfo.setDecryptedAccessToken(decryptedAccessTokenStr);
            tokenInfo.setDecryptedRefreshToken(decryptedRefreshTokenStr);
        }
        return tokenInfo;
    }

    public static AuthorizationCodeInfo transformEncryptedAuthorizationCode(AuthorizationCodeInfo authorizationCodeInfo)
            throws CryptoException, SyncClientException {

        String authorizationCode = authorizationCodeInfo.getAuthorizationCode();
        if (!isBase64DecodeAndIsSelfContainedCipherText(authorizationCode)) {
            byte[] decryptedAuthorizationCode = getDefaultCryptoUtil().base64DecodeAndDecrypt(authorizationCode, "RSA");
            String newEncryptedAuthorizationCode = getDefaultCryptoUtil().encryptAndBase64Encode
                    (decryptedAuthorizationCode);

            String decryptedAuthorizationCodeStr = new String(decryptedAuthorizationCode, UTF_8);

            authorizationCodeInfo.setAuthorizationCode(newEncryptedAuthorizationCode);
            authorizationCodeInfo.setDecryptedAuthorizationCode(decryptedAuthorizationCodeStr);

        } else if (isBase64DecodeAndIsSelfContainedCipherText(authorizationCode)) {

            byte[] decryptedAccessToken = getDefaultCryptoUtil().base64DecodeAndDecrypt(authorizationCode);

            String decryptedAuthorizationCodeStr = new String(decryptedAccessToken, UTF_8);
            authorizationCodeInfo.setAuthorizationCode(authorizationCode);
            authorizationCodeInfo.setDecryptedAuthorizationCode(decryptedAuthorizationCodeStr);
        }
        return authorizationCodeInfo;
    }

    /**
     * Update a value of a token journal entry.
     *
     * @param entry     Journal entry.
     * @param tokenInfo Token information.
     * @deprecated Use updateJournalEntryForToken(JournalEntry entry, TokenInfo tokenInfo,
     */
    @Deprecated
    public static void updateJournalEntryForToken(JournalEntry entry, TokenInfo tokenInfo) {

        entry.addEntryField(COLUMN_ACCESS_TOKEN, new EntryField<>(tokenInfo.getAccessToken()));
        entry.addEntryField(COLUMN_REFRESH_TOKEN, new EntryField<>(tokenInfo.getRefreshToken()));
        entry.addEntryField(COLUMN_ACCESS_TOKEN_HASH, new EntryField<>(tokenInfo.getAccessTokenHash()));
        entry.addEntryField(COLUMN_REFRESH_TOKEN_HASH, new EntryField<>(tokenInfo.getRefreshTokenHash()));
    }

    /**
     * Update a value of a authorization code journal entry.
     *
     * @param entry                 Journal entry.
     * @param authorizationCodeInfo Authorization Code information.
     * @deprecated Use updateJournalEntryForCode(JournalEntry entry, AuthorizationCodeInfo authorizationCodeInfo,
     * boolean isLowerCaseIdentifiers)
     */
    @Deprecated
    public static void updateJournalEntryForCode(JournalEntry entry, AuthorizationCodeInfo authorizationCodeInfo) {

        entry.addEntryField(COLUMN_AUTHORIZATION_CODE, new EntryField<>(authorizationCodeInfo.getAuthorizationCode()));
        entry.addEntryField(COLUMN_AUTHORIZATION_CODE_HASH, new EntryField<>(authorizationCodeInfo
                .getAuthorizationCodeHash()));
    }

    /**
     * Update a value of a token journal entry.
     *
     * @param entry                  Journal entry.
     * @param tokenInfo              Token information.
     * @param isLowerCaseIdentifiers Whether the database dialect mains identifiers in lower case.
     */
    public static void updateJournalEntryForToken(JournalEntry entry, TokenInfo tokenInfo,
                                                  boolean isLowerCaseIdentifiers) {

        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_ACCESS_TOKEN.toLowerCase() : COLUMN_ACCESS_TOKEN,
                new EntryField<>(tokenInfo.getAccessToken()));
        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_REFRESH_TOKEN.toLowerCase() : COLUMN_REFRESH_TOKEN,
                new EntryField<>(tokenInfo.getRefreshToken()));
        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_ACCESS_TOKEN_HASH.toLowerCase() : COLUMN_ACCESS_TOKEN_HASH,
                new EntryField<>(tokenInfo.getAccessTokenHash()));
        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_REFRESH_TOKEN_HASH.toLowerCase() : COLUMN_REFRESH_TOKEN_HASH
                , new EntryField<>(tokenInfo.getRefreshTokenHash()));
        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_IDP_ID.toLowerCase() : COLUMN_IDP_ID,
                new EntryField<>(tokenInfo.getIdpId()));
    }

    /**
     * Update a value of a authorization code journal entry.
     *
     * @param entry                  Journal entry.
     * @param authorizationCodeInfo  Authorization code information.
     * @param isLowerCaseIdentifiers Whether the database dialect mains identifiers in lower case.
     */
    public static void updateJournalEntryForCode(JournalEntry entry, AuthorizationCodeInfo authorizationCodeInfo,
                                                 boolean isLowerCaseIdentifiers) {

        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_AUTHORIZATION_CODE.toLowerCase() : COLUMN_AUTHORIZATION_CODE
                , new EntryField<>(authorizationCodeInfo.getAuthorizationCode()));
        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_AUTHORIZATION_CODE_HASH.toLowerCase()
                : COLUMN_AUTHORIZATION_CODE_HASH, new EntryField<>(authorizationCodeInfo
                .getAuthorizationCodeHash()));
        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_IDP_ID.toLowerCase() : COLUMN_IDP_ID,
                new EntryField<>(authorizationCodeInfo.getIdpId()));
    }

    /**
     * Update a value of a session journal entry.
     *
     * @param entry                  Journal entry.
     * @param sessionInfo            Session information.
     * @param isLowerCaseIdentifiers Whether the database dialect mains identifiers in lower case.
     */
    public static void updateJournalEntryForSession(JournalEntry entry, SessionInfo sessionInfo,
                                                    boolean isLowerCaseIdentifiers) {

        entry.addEntryField(isLowerCaseIdentifiers ? COLUMN_EXPIRY_TIME.toLowerCase()
                : COLUMN_EXPIRY_TIME, new EntryField<>(sessionInfo.getExpiryTime()));
    }

    /**
     * Get idpId for the specified table name.
     *
     * @param journalEntryList A list of journal entries.
     * @param context          Pipeline context.
     * @param tableName        Target table name.
     * @return Value of idpId column.
     * @throws SyncClientException Thrown if the idp retrieval failed.
     */
    public static int getIdpId(List<JournalEntry> journalEntryList, PipelineContext context, String tableName)
            throws SyncClientException {

        int idpId = -1;

        if (!journalEntryList.isEmpty()) {
            try {
                Connection connection = context.getSourceConnection();
                String sqlQuery = String.format(SQL_TEMPLATE_SELECT_SOURCE_IDP_ID, tableName, tableName);
                PreparedStatement preparedStatement = connection.prepareStatement(sqlQuery);
                ResultSet resultSet = preparedStatement.executeQuery();

                if (resultSet.next()) {
                    idpId = resultSet.getInt(1);
                }

            } catch (SQLException e) {
                throw new SyncClientException("An error occurred while getting idpId for the table: " + tableName, e);
            }
        }

        return idpId;
    }
}

