/*
 * Copyright (C) 2020 Yunify, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this work except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE file, or at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qingstor.sdk.request;

import com.qingstor.sdk.config.EnvContext;
import com.qingstor.sdk.constants.QSConstant;
import com.qingstor.sdk.exception.QSException;
import com.qingstor.sdk.model.RequestInputModel;
import com.qingstor.sdk.request.impl.QSFormRequestBody;
import com.qingstor.sdk.request.impl.QSMultiPartUploadRequestBody;
import com.qingstor.sdk.request.impl.QSNormalRequestBody;
import com.qingstor.sdk.utils.Base64;
import com.qingstor.sdk.utils.QSParamInvokeUtil;
import com.qingstor.sdk.utils.QSSignatureUtil;
import com.qingstor.sdk.utils.QSStringUtil;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;

@Slf4j
@SuppressWarnings({"rawtypes", "unchecked"})
public class QSBuilder {

    private static final String REQUEST_PREFIX = "/";

    private Map context;

    private RequestInputModel paramsModel;

    private String requestMethod = "GET";

    private Map paramsQuery;

    private Map paramsBody;

    private Map paramsHeaders;

    private Map paramsFormData;

    private String requestUrl;

    private HttpUrl url;

    private String requestUrlStyle;

    public QSBuilder(Map context, RequestInputModel params) throws QSException {
        this.context = context;
        this.paramsModel = params;
        this.initParams();
        this.initUrl();
    }

    private void initParams() throws QSException {

        this.paramsQuery =
                QSParamInvokeUtil.getRequestParams(this.paramsModel, QSConstant.PARAM_TYPE_QUERY);
        this.paramsBody =
                QSParamInvokeUtil.getRequestParams(this.paramsModel, QSConstant.PARAM_TYPE_BODY);
        this.paramsHeaders =
                QSParamInvokeUtil.getRequestParams(this.paramsModel, QSConstant.PARAM_TYPE_HEADER);
        this.paramsFormData =
                QSParamInvokeUtil.getRequestParams(
                        this.paramsModel, QSConstant.PARAM_TYPE_FORM_DATA);

        if (this.paramsHeaders.containsKey(QSConstant.PARAM_KEY_METADATA)) {
            Object o = this.paramsHeaders.get(QSConstant.PARAM_KEY_METADATA);
            if (o != null) {
                Map<String, String> map = (Map<String, String>) o;
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    this.paramsHeaders.put(entry.getKey(), entry.getValue());
                }
            }
            this.paramsHeaders.remove(QSConstant.PARAM_KEY_METADATA);
        }

        EnvContext ctx = (EnvContext) context.get(QSConstant.ENV_CONTEXT_KEY);
        String additionalUserAgent = ctx.getAdditionalUserAgent();
        if (additionalUserAgent != null) {
            paramsHeaders.put(QSConstant.PARAM_KEY_USER_AGENT, additionalUserAgent);
        }

        if (this.checkExpiresParam()) {
            paramsHeaders.clear();
            paramsHeaders.put(
                    QSConstant.HEADER_PARAM_KEY_EXPIRES, context.get(QSConstant.PARAM_KEY_EXPIRES));
        }

        String requestApi = (String) context.get(QSConstant.PARAM_KEY_REQUEST_APINAME);
        this.initHeadContentMd5(requestApi, paramsBody, paramsHeaders);

        paramsHeaders = this.headParamEncoding(paramsHeaders);

        this.requestMethod = (String) context.get(QSConstant.PARAM_KEY_REQUEST_METHOD);
    }

    private void doSignature() throws QSException {

        String authSign = this.getParamSignature();
        log.debug("== authSign ==\n" + authSign + "\n");

        paramsHeaders.put(QSConstant.HEADER_PARAM_KEY_AUTHORIZATION, authSign);
    }

    private String getParamSignature() throws QSException {

        String authSign = "";
        EnvContext envContext = (EnvContext) context.get(QSConstant.ENV_CONTEXT_KEY);
        try {

            if (this.checkExpiresParam()) {
                authSign =
                        QSSignatureUtil.generateSignature(
                                envContext.getSecretAccessKey(), this.getStringToSignature());
                authSign = URLEncoder.encode(authSign, QSConstant.ENCODING_UTF8);
            } else {
                authSign =
                        QSSignatureUtil.generateAuthorization(
                                envContext.getAccessKeyId(),
                                envContext.getSecretAccessKey(),
                                this.getStringToSignature());
            }
        } catch (Exception e) {
            throw new QSException("Auth signature error", e);
        }

        return authSign;
    }

    private void initUrl() throws QSException {
        EnvContext envContext = (EnvContext) context.get(QSConstant.ENV_CONTEXT_KEY);
        String bucketName = (String) this.context.get(QSConstant.PARAM_KEY_BUCKET_NAME);
        String zone = (String) context.get(QSConstant.PARAM_KEY_REQUEST_ZONE);
        String requestPath = (String) context.get(QSConstant.PARAM_KEY_REQUEST_PATH);

        // first ensure objectInput exists
        // remove bucket part may exists in string
        // then replace object part may exists in string
        String requestSuffixPath =
                requestPath.replace(REQUEST_PREFIX + QSConstant.BUCKET_PLACEHOLDER, "");
        if (this.context.containsKey(QSConstant.PARAM_KEY_OBJECT_NAME)) {
            String objectName = (String) this.context.get(QSConstant.PARAM_KEY_OBJECT_NAME);
            requestSuffixPath =
                    requestSuffixPath.replace(
                            QSConstant.OBJECT_PLACEHOLDER,
                            QSStringUtil.asciiCharactersEncoding(objectName));
        }
        this.requestUrlStyle = envContext.getRequestUrlStyle();
        if (this.requestUrlStyle == null) { // todo: EnvContext create with some default values.
            this.requestUrlStyle = QSConstant.PATH_STYLE;
        }
        this.requestUrl =
                this.getSignedUrl(
                        envContext.getRequestUrl(),
                        zone,
                        bucketName,
                        paramsQuery,
                        requestSuffixPath);
        // update paramsQuery.
        this.url = HttpUrl.parse(this.requestUrl);
        if (this.url == null) {
            throw new QSException("the request url is malformed");
        }
        Map<String, String> queries = new HashMap<>();
        for (String k : this.url.queryParameterNames()) {
            List<String> values = this.url.queryParameterValues(k);
            queries.put(k, values.get(0)); // it should always size > 0.
        }
        this.paramsQuery = queries;
        log.debug("== requestUrl ==\n" + this.url + "\n");
    }

    private Map headParamEncoding(Map headParams) throws QSException {
        final int maxAscii = 127;
        Map<String, String> head = new HashMap<>();
        for (Object o : headParams.entrySet()) {
            Map.Entry entry = (Map.Entry) o;
            String key = (String) entry.getKey();
            String value = entry.getValue().toString();

            for (int charIndex = 0, nChars = value.length(), codePoint;
                    charIndex < nChars;
                    charIndex += Character.charCount(codePoint)) {
                codePoint = value.codePointAt(charIndex);
                if (codePoint > 127) {
                    value = QSStringUtil.asciiCharactersEncoding(value);
                    break;
                }
            }
            head.put(key, value);
        }

        return head;
    }

    private void initHeadContentMd5(String requestApi, Map paramsBody, Map headerParams)
            throws QSException {
        if (QSConstant.PARAM_KEY_REQUEST_API_DELETE_MULTIPART.equals(requestApi)) {
            if (paramsBody.size() > 0) {
                Object bodyContent = QSNormalRequestBody.getBodyContent(paramsBody);
                MessageDigest instance = null;
                try {
                    instance = MessageDigest.getInstance("MD5");
                } catch (NoSuchAlgorithmException e) {
                    throw new QSException("MessageDigest MD5 error", e);
                }
                String contentMD5 =
                        Base64.encode(instance.digest(bodyContent.toString().getBytes()));
                headerParams.put(QSConstant.PARAM_KEY_CONTENT_MD5, contentMD5);
            }
        }
    }

    private String getRequestSuffixPath(String requestPath, String bucketName, String objectName)
            throws QSException {
        if (QSStringUtil.isEmpty(bucketName)) {
            return REQUEST_PREFIX;
        }
        String suffixPath =
                requestPath
                        .replace(REQUEST_PREFIX + QSConstant.BUCKET_PLACEHOLDER, "")
                        .replace(REQUEST_PREFIX + QSConstant.OBJECT_PLACEHOLDER, "");
        if (QSStringUtil.isEmpty(objectName)) {
            objectName = "";
        } else {
            objectName = QSStringUtil.asciiCharactersEncoding(objectName);
        }

        return String.format("%s%s%s", REQUEST_PREFIX, objectName, suffixPath);
    }

    private String getSignedUrl(
            String serviceUrl,
            String zone,
            String bucketName,
            Map paramsQuery,
            String requestSuffixPath)
            throws QSException {
        if ("".equals(bucketName) || bucketName == null) {
            return QSSignatureUtil.generateQSURL(paramsQuery, serviceUrl + requestSuffixPath);
        } else {
            // handle url style
            String storRequestUrl;
            if (QSConstant.PATH_STYLE.equals(requestUrlStyle)) {
                storRequestUrl = serviceUrl.replace("://", "://" + zone + ".") + "/" + bucketName;
            } else {
                storRequestUrl = serviceUrl.replace("://", "://%s." + zone + ".");
                storRequestUrl = String.format(storRequestUrl, bucketName);
            }

            return QSSignatureUtil.generateQSURL(paramsQuery, storRequestUrl + requestSuffixPath);
        }
    }

    public void setHeader(String key, String authorization) {
        this.paramsHeaders.put(key, authorization);
    }

    public void setSignature(String accessKey, String signature) throws QSException {

        try {
            EnvContext envContext = (EnvContext) context.get(QSConstant.ENV_CONTEXT_KEY);
            envContext.setAccessKeyId(accessKey);
            if (this.checkExpiresParam()) {
                signature = URLEncoder.encode(signature, QSConstant.ENCODING_UTF8);
            } else {
                signature = String.format("QS %s:%s", accessKey, signature);
            }
            this.paramsHeaders.put(QSConstant.HEADER_PARAM_KEY_AUTHORIZATION, signature);
        } catch (UnsupportedEncodingException e) {
            throw new QSException("Auth signature error", e);
        }
    }

    public String getStringToSignature() throws QSException {
        String requestPath = "";
        if (this.requestUrlStyle.equals(QSConstant.PATH_STYLE)) {
            requestPath = this.url.encodedPath();
        } else {
            String bucketName = (String) this.context.get(QSConstant.PARAM_KEY_BUCKET_NAME);
            if (bucketName != null) {
                requestPath = "/" + bucketName + this.url.encodedPath();
            } else { // service level
                requestPath = this.url.encodedPath();
            }
        }

        return QSSignatureUtil.getStringToSignature(
                this.requestMethod, requestPath, this.paramsQuery, this.paramsHeaders);
    }

    public RequestBody getRequestBody(QSRequestBody qsBody) throws QSException {
        this.getSignature();
        RequestBody requestBody = null;
        String contentType =
                String.valueOf(paramsHeaders.get(QSConstant.HEADER_PARAM_KEY_CONTENTTYPE));
        long contentLength = 0;
        if (paramsHeaders.containsKey(QSConstant.PARAM_KEY_CONTENT_LENGTH)) {
            contentLength =
                    Long.parseLong(paramsHeaders.get(QSConstant.PARAM_KEY_CONTENT_LENGTH) + "");
        }
        if (qsBody != null) {
            requestBody =
                    qsBody.getRequestBody(
                            contentType,
                            contentLength,
                            this.requestMethod,
                            this.paramsBody,
                            this.paramsQuery);
        } else {
            requestBody = getRequestBody();
        }

        return requestBody;
    }

    public RequestBody getRequestBody() throws QSException {
        this.getSignature();
        RequestBody requestBody = null;
        String contentType =
                String.valueOf(paramsHeaders.get(QSConstant.HEADER_PARAM_KEY_CONTENTTYPE));
        long contentLength = 0;
        if (paramsHeaders.containsKey(QSConstant.PARAM_KEY_CONTENT_LENGTH)) {
            contentLength =
                    Long.parseLong(paramsHeaders.get(QSConstant.PARAM_KEY_CONTENT_LENGTH) + "");
        }

        if (this.paramsFormData != null && this.paramsFormData.size() > 0) {
            requestBody =
                    new QSFormRequestBody()
                            .getRequestBody(
                                    contentType,
                                    contentLength,
                                    this.requestMethod,
                                    this.paramsFormData,
                                    this.paramsQuery);
        } else {
            String requestApi = (String) this.context.get(QSConstant.PARAM_KEY_REQUEST_APINAME);
            if (QSConstant.PARAM_KEY_REQUEST_API_MULTIPART.equals(requestApi)) {
                requestBody =
                        new QSMultiPartUploadRequestBody()
                                .getRequestBody(
                                        contentType,
                                        contentLength,
                                        this.requestMethod,
                                        this.paramsBody,
                                        this.paramsQuery);
            } else {
                requestBody =
                        new QSNormalRequestBody()
                                .getRequestBody(
                                        contentType,
                                        contentLength,
                                        this.requestMethod,
                                        this.paramsBody,
                                        this.paramsQuery);
            }
        }
        return requestBody;
    }

    public Request getRequest(RequestBody requestBody) throws QSException {
        if (this.checkExpiresParam()) {
            throw new QSException("You need to 'getExpiresRequestUrl' do request!");
        }

        return QSOkHttpRequestClient.buildRequest(
                this.requestMethod, this.requestUrl, requestBody, paramsHeaders);
    }

    private boolean checkExpiresParam() {
        Object expiresTime = this.context.get(QSConstant.PARAM_KEY_EXPIRES);
        if (expiresTime != null) {
            return true;
        }
        return false;
    }

    public String getExpiresRequestUrl() throws QSException {
        Object expiresTime = this.context.get(QSConstant.PARAM_KEY_EXPIRES);
        if (expiresTime != null) {
            EnvContext envContext = (EnvContext) context.get(QSConstant.ENV_CONTEXT_KEY);
            String expireAuth = this.getSignature();
            String serviceUrl = envContext.getRequestUrl();
            String objectName = (String) context.get(QSConstant.PARAM_KEY_OBJECT_NAME);
            String bucketName = (String) this.context.get(QSConstant.PARAM_KEY_BUCKET_NAME);

            String zone = (String) context.get(QSConstant.PARAM_KEY_REQUEST_ZONE);
            // handle url style
            String storRequestUrl;
            if (QSConstant.PATH_STYLE.equals(requestUrlStyle)) {
                storRequestUrl = serviceUrl.replace("://", "://" + zone + ".") + "/" + bucketName;
            } else {
                storRequestUrl = serviceUrl.replace("://", "://%s." + zone + ".");
                storRequestUrl = String.format(storRequestUrl, bucketName);
            }
            objectName = QSStringUtil.asciiCharactersEncoding(objectName);
            String requestPath = (String) this.context.get(QSConstant.PARAM_KEY_REQUEST_PATH);
            requestPath =
                    requestPath.replace("/<bucket-name>", "").replace("/<object-key>", objectName);
            if (requestPath != null && requestPath.indexOf("?") > 0) {
                String expiresUrl =
                        String.format(
                                storRequestUrl + "/%s&access_key_id=%s&expires=%s&signature=%s",
                                requestPath,
                                envContext.getAccessKeyId(),
                                expiresTime + "",
                                expireAuth);
                return QSSignatureUtil.generateQSURL(this.paramsQuery, expiresUrl);
            } else {
                String expiresUrl =
                        String.format(
                                storRequestUrl + "/%s?access_key_id=%s&expires=%s&signature=%s",
                                requestPath,
                                envContext.getAccessKeyId(),
                                expiresTime + "",
                                expireAuth);
                return QSSignatureUtil.generateQSURL(this.paramsQuery, expiresUrl);
            }
        } else {
            throw new QSException("ExpiresRequestUrl error:There is no expirs params");
        }
    }

    private String getSignature() throws QSException {
        String signature =
                String.valueOf(this.paramsHeaders.get(QSConstant.HEADER_PARAM_KEY_AUTHORIZATION));
        if (QSStringUtil.isEmpty(signature)) {
            this.doSignature();
            return String.valueOf(
                    this.paramsHeaders.get(QSConstant.HEADER_PARAM_KEY_AUTHORIZATION));
        }
        return String.valueOf(signature);
    }
}
