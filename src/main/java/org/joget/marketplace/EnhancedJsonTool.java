package org.joget.marketplace;

import bsh.Interpreter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.client.methods.HttpPatch;
import org.joget.apps.app.service.CustomURLDataSource;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FileUtil;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.LongTermCache;
import org.joget.commons.util.StringUtil;
import org.joget.commons.util.UuidGenerator;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.property.service.PropertyUtil;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;
import java.net.ProxySelector;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ConnectTimeoutException;
import java.net.SocketTimeoutException;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.joget.marketplace.util.TokenApiUtil;

public class EnhancedJsonTool extends DefaultApplicationPlugin {

    private final static String MESSAGE_PATH = "messages/enhancedJsonApi";

    @Override
    public String getName() {
        return "Enhanced JSON Tool";
    }

    @Override
    public String getDescription() {
        return "Default Json Tool with additional ability to follow system proxy settings, support API token, customise socket timeout retries, alter the response via bean shell before inserting formatted data into form data table or workflow variable";
    }

    @Override
    public String getVersion() {
        return Activator.VERSION;
    }

    @Override
    public String getLabel() {
        return "Enhanced JSON Tool";
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        String appId = appDef.getId();
        String appVersion = appDef.getVersion().toString();
        Object[] arguments = new Object[]{appId, appVersion, appId, appVersion, appId, appVersion};
        String json = AppUtil.readPluginResource(getClass().getName(), "/properties/enhancedJsonTool.json", arguments, true, MESSAGE_PATH);
        return json;
    }

    public Object execute(Map properties) {
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        WorkflowAssignment wfAssignment = (WorkflowAssignment) properties.get("workflowAssignment");
        ApplicationContext ac = AppUtil.getApplicationContext();
        WorkflowManager workflowManager = (WorkflowManager) ac.getBean("workflowManager");
        LongTermCache longTermCache = (LongTermCache) AppUtil.getApplicationContext().getBean("longTermCache");

        // Retrieve timeout values from plugin properties
        int connectionTimeout = 30000; // Default to 30,000 milliseconds (30 seconds)
        int socketTimeout = 30000;     // Default to 30,000 milliseconds (30 seconds)

        String connectionTimeoutStr = (String) properties.get("connectionTimeout");
        String socketTimeoutStr = (String) properties.get("socketTimeout");

        try {
            if (connectionTimeoutStr != null && !connectionTimeoutStr.isEmpty()) {
                connectionTimeout = Integer.parseInt(connectionTimeoutStr) * 1000;
            }
            if (socketTimeoutStr != null && !socketTimeoutStr.isEmpty()) {
                socketTimeout = Integer.parseInt(socketTimeoutStr) * 1000;
            }
        } catch (NumberFormatException e) {
            LogUtil.warn(getClass().getName(), "Invalid timeout value provided. Using default timeouts.");
        }
        LogUtil.info(getClass().getName(), "Connection Timeout set to: " + connectionTimeout + " ms");
        LogUtil.info(getClass().getName(), "Socket Timeout set to: " + socketTimeout + " ms");

        // process the accessToken call if checked
        String accessToken = "";
        String accessTokenCheck = (String) properties.get("accessToken");
        String accessTokenStoreCache = (String) properties.get("tokenStoreCache");
        String accessTokenCacheExpiryTime = (String) properties.get("tokenCacheExpiryTime");

        if ("true".equalsIgnoreCase(accessTokenCheck)) {
            String cacheKey = properties.get("tokenUrl").toString() + properties.get("tokenFieldName").toString();
            net.sf.ehcache.Element element = longTermCache.get(cacheKey);
            if (element != null && element.getObjectValue() != null) {
                Long clearTime = longTermCache.getLastClearTime(cacheKey);

                if (clearTime != null) {
                    // clear cache when exceed specified time in minutes
                    if (accessTokenCacheExpiryTime != null) {
                        long relativeTimeInMillis = Integer.parseInt(accessTokenCacheExpiryTime) * 60 * 1000;
                        long currentTimeMillis = System.currentTimeMillis();

                        if ((currentTimeMillis - clearTime) > relativeTimeInMillis) {
                            longTermCache.remove(cacheKey);
                        }
                    }
                }
            } else {
                longTermCache.remove(cacheKey);
            }

            if ("true".equalsIgnoreCase(accessTokenStoreCache)) {
                net.sf.ehcache.Element el = longTermCache.get(cacheKey);
                // get from cache, if null then get from api call
                if (el != null) {
                    accessToken = el.getObjectValue().toString();
                } else {
                    accessToken = new TokenApiUtil().getToken(properties);
                }
            } else {
                accessToken = new TokenApiUtil().getToken(properties);
            }
        }

        String jsonUrl = (String) properties.get("jsonUrl");
        CloseableHttpClient client = null;
        HttpRequestBase request = null;

        String jsonResponse = "";
        Map jsonResponseObject = null;
        Object jsonResponseObjectRaw = null;

        try {
            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(connectionTimeout)
                    .setSocketTimeout(socketTimeout)
                    .build();

            // follow system proxy settings
            client = HttpClients.custom()
                    .setDefaultRequestConfig(config)
                    .setRoutePlanner(new SystemDefaultRoutePlanner(ProxySelector.getDefault()))
                    .build();
            jsonUrl = WorkflowUtil.processVariable(jsonUrl, "", wfAssignment);
            jsonUrl = StringUtil.encodeUrlParam(jsonUrl);

            if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                LogUtil.info(EnhancedJsonTool.class.getName(), ("post".equalsIgnoreCase(getPropertyString("requestType")) ? "POST" : "GET") + " : " + jsonUrl);
            }

            if ("post".equalsIgnoreCase(getPropertyString("requestType"))) {
                request = new HttpPost(jsonUrl);

                if ("jsonPayload".equals(getPropertyString("postMethod"))) {
                    JSONObject obj = new JSONObject();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    for (Object o : paramsValues) {
                        Map mapping = (HashMap) o;
                        String name = mapping.get("name").toString();
                        String value = mapping.get("value").toString();
                        obj.accumulate(name, WorkflowUtil.processVariable(value, "", wfAssignment));
                    }

                    StringEntity requestEntity = new StringEntity(obj.toString(4), "UTF-8");
                    ((HttpPost) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), "JSON Payload : " + obj.toString(4));
                    }
                } else if ("custom".equals(getPropertyString("postMethod"))) {
                    StringEntity requestEntity = new StringEntity(getPropertyString("customPayload"), "UTF-8");
                    ((HttpPost) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), "Custom JSON Payload : " + getPropertyString("customPayload"));
                    }
                } else {
                    MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    for (Object o : paramsValues) {
                        Map mapping = (HashMap) o;
                        String name = mapping.get("name").toString();
                        String value = mapping.get("value").toString();
                        //urlParameters.add(new BasicNameValuePair(name, WorkflowUtil.processVariable(value, "", wfAssignment)));
                        builder.addPart(name, new StringBody(value, ContentType.MULTIPART_FORM_DATA));
                        if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                            LogUtil.info(EnhancedJsonTool.class.getName(), "Adding param " + name + " : " + value);
                        }
                    }

                    if ((properties.get("attachmentFormDefId") != null && !properties.get("attachmentFormDefId").toString().isEmpty())
                            || (properties.get("attachmentFiles") != null && !properties.get("attachmentFiles").toString().isEmpty())) {
                        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
                    }

                    //handle file upload from form
                    String formDefId = (String) properties.get("attachmentFormDefId");
                    Object[] fields = null;
                    if (properties.get("attachmentFields") instanceof Object[]) {
                        fields = (Object[]) properties.get("attachmentFields");
                    }
                    if (formDefId != null && !formDefId.isEmpty() && fields != null && fields.length > 0) {
                        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");

                        FormData formData = new FormData();
                        String primaryKey = appService.getOriginProcessId(wfAssignment.getProcessId());
                        formData.setPrimaryKeyValue(primaryKey);
                        Form loadForm = appService.viewDataForm(appDef.getId(), appDef.getVersion().toString(), formDefId, null, null, null, formData, null, null);

                        for (Object o : fields) {
                            Map mapping = (HashMap) o;
                            String fieldId = mapping.get("field").toString();
                            String parameterName = (String) mapping.get("parameterName");

                            try {
                                Element el = FormUtil.findElement(fieldId, loadForm, formData);
                                String value = FormUtil.getElementPropertyValue(el, formData);
                                if (value.contains("/web/client/app/") && value.contains("/form/download/")) {
                                    value = retrieveFileNames(value, appDef.getAppId(), formDefId, primaryKey);
                                }
                                if (value != null && !value.isEmpty()) {
                                    String values[] = value.split(";");
                                    for (String v : values) {
                                        if (!v.isEmpty()) {
                                            File file = FileUtil.getFile(v, loadForm, primaryKey);
                                            if (file != null && file.exists()) {
                                                if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                                                    LogUtil.info(EnhancedJsonTool.class.getName(), "Attaching file " + v);
                                                }
                                                FileBody fileBody = new FileBody(file);
                                                builder.addPart(parameterName, fileBody);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                LogUtil.info(EnhancedJsonTool.class.getName(), "Attach file from form failed from field \"" + fieldId + "\" in form \"" + formDefId + "\"");
                            }
                        }
                    }

                    //handle file upload from url/path
                    Object[] files = null;
                    if (properties.get("attachmentFiles") instanceof Object[]) {
                        files = (Object[]) properties.get("attachmentFiles");
                    }
                    if (files != null && files.length > 0) {
                        for (Object o : files) {
                            Map mapping = (HashMap) o;
                            String path = mapping.get("path").toString();
                            String parameterName = mapping.get("parameterName").toString();
                            String type = mapping.get("type").toString();

                            try {
                                if ("system".equals(type)) {
                                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                                        LogUtil.info(EnhancedJsonTool.class.getName(), "Attaching file path " + path);
                                    }
                                    File file = new File(path);
                                    FileBody fileBody = new FileBody(file);
                                    builder.addPart(parameterName, fileBody);
                                } else if ("url".equals(type)) {
                                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                                        LogUtil.info(EnhancedJsonTool.class.getName(), "Attaching URL path " + path);
                                    }
                                    URL u = new URL(path);
                                    CustomURLDataSource c = new CustomURLDataSource(u);
                                    builder.addBinaryBody(parameterName, c.getInputStream().readAllBytes(), ContentType.create(c.getContentType()), c.getName());

                                }
                            } catch (Exception e) {
                                LogUtil.error(EnhancedJsonTool.class.getName(), e, "File attachment failed from path \"" + path + "\"");
                            }
                        }
                    }

                    HttpEntity entity = builder.build();
                    ((HttpPost) request).setEntity(entity);
                }
            } else if ("put".equalsIgnoreCase(getPropertyString("requestType"))) {
                request = new HttpPut(jsonUrl);

                if ("jsonPayload".equals(getPropertyString("postMethod"))) {
                    JSONObject obj = new JSONObject();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    for (Object o : paramsValues) {
                        Map mapping = (HashMap) o;
                        String name = mapping.get("name").toString();
                        String value = mapping.get("value").toString();
                        obj.accumulate(name, WorkflowUtil.processVariable(value, "", wfAssignment));
                    }

                    StringEntity requestEntity = new StringEntity(obj.toString(4), "UTF-8");
                    ((HttpPut) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), "JSON Payload : " + obj.toString(4));
                    }
                } else if ("custom".equals(getPropertyString("postMethod"))) {
                    StringEntity requestEntity = new StringEntity(getPropertyString("customPayload"), "UTF-8");
                    ((HttpPut) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), "Custom JSON Payload : " + getPropertyString("customPayload"));
                    }
                } else {
                    List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    for (Object o : paramsValues) {
                        Map mapping = (HashMap) o;
                        String name = mapping.get("name").toString();
                        String value = mapping.get("value").toString();
                        urlParameters.add(new BasicNameValuePair(name, WorkflowUtil.processVariable(value, "", wfAssignment)));
                        if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                            LogUtil.info(EnhancedJsonTool.class.getName(), "Adding param " + name + " : " + value);
                        }
                    }
                    ((HttpPut) request).setEntity(new UrlEncodedFormEntity(urlParameters, "UTF-8"));
                }
            } else if ("patch".equalsIgnoreCase(getPropertyString("requestType"))) {
                request = new HttpPatch(jsonUrl);

                if ("jsonPayload".equals(getPropertyString("postMethod"))) {
                    JSONObject obj = new JSONObject();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    for (Object o : paramsValues) {
                        Map mapping = (HashMap) o;
                        String name = mapping.get("name").toString();
                        String value = mapping.get("value").toString();
                        obj.accumulate(name, WorkflowUtil.processVariable(value, "", wfAssignment));
                    }

                    StringEntity requestEntity = new StringEntity(obj.toString(4), "UTF-8");
                    ((HttpPatch) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), "JSON Payload : " + obj.toString(4));
                    }
                } else if ("custom".equals(getPropertyString("postMethod"))) {
                    StringEntity requestEntity = new StringEntity(getPropertyString("customPayload"), "UTF-8");
                    ((HttpPatch) request).setEntity(requestEntity);
                    request.setHeader("Content-type", "application/json");
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), "Custom JSON Payload : " + getPropertyString("customPayload"));
                    }
                } else {
                    List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
                    Object[] paramsValues = (Object[]) properties.get("params");
                    for (Object o : paramsValues) {
                        Map mapping = (HashMap) o;
                        String name = mapping.get("name").toString();
                        String value = mapping.get("value").toString();
                        urlParameters.add(new BasicNameValuePair(name, WorkflowUtil.processVariable(value, "", wfAssignment)));
                        if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                            LogUtil.info(EnhancedJsonTool.class.getName(), "Adding param " + name + " : " + value);
                        }
                    }
                    ((HttpPatch) request).setEntity(new UrlEncodedFormEntity(urlParameters, "UTF-8"));
                }
            } else {
                request = new HttpGet(jsonUrl);
            }

            Object[] paramsValues = (Object[]) properties.get("headers");
            for (Object o : paramsValues) {
                Map mapping = (HashMap) o;
                String name = mapping.get("name").toString();
                String value = mapping.get("value").toString();
                if (name != null && !name.isEmpty() && value != null && !value.isEmpty() && accessToken != null) {
                    if (value != null && value.contains("{accessToken}")) {
                        value = value.replace("{accessToken}", accessToken);
                    }
                    request.setHeader(name, value);
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), "Adding request header " + name + " : " + value);
                    }
                }
            }

            String responseType = getPropertyString("responseType");
            HttpResponse response = executeApiCall(responseType, jsonUrl, client, request);

            if (!responseType.isEmpty()) {

                if (responseType.equalsIgnoreCase("JSON")) {
                    // Cache response data based on the Cache-Control response headers
                    String cacheKeyResponse = properties.get("jsonUrl").toString() + "response";
                    String cacheKeyHeaders = properties.get("jsonUrl").toString() + "headers";
                    Boolean executeApi = false;

                    if ("true".equalsIgnoreCase(getPropertyString("responseStoreCache"))) {
                        // based on cache-control, remove cache when reach expiry time
                        net.sf.ehcache.Element element = longTermCache.get(cacheKeyHeaders);
                        if (element != null && element.getObjectValue() != null) {
                            Long clearTime = longTermCache.getLastClearTime(cacheKeyHeaders);
                            int maxAge = 0;
                            String cacheControl = "";

                            // retrieve max-age from cache, if no value, default is 86400 (seconds)
                            for (String line : element.getObjectValue().toString().split("\n")) {
                                if (line.startsWith("Cache-Control:")) {
                                    cacheControl = line.substring(14).trim();
                                }
                            }
                            if (cacheControl != null && cacheControl != "") {
                                for (String directive : cacheControl.split(",")) {
                                    directive = directive.trim();
                                    if (directive.startsWith("max-age=")) {
                                        maxAge = Integer.parseInt(directive.split("=")[1]);
                                    }
                                }
                            } else {
                                maxAge = 86400;
                            }

                            if (clearTime != null) {
                                // clear cache when exceed max-age
                                if (maxAge != 0) {
                                    long relativeTimeInMillis = maxAge * 1000;
                                    long currentTimeMillis = System.currentTimeMillis();

                                    if ((currentTimeMillis - clearTime) > relativeTimeInMillis) {
                                        longTermCache.remove(cacheKeyHeaders);
                                        longTermCache.remove(cacheKeyResponse);
                                    }
                                }
                            }
                        } else {
                            longTermCache.remove(cacheKeyHeaders);
                            longTermCache.remove(cacheKeyResponse);
                        }

                        net.sf.ehcache.Element el = longTermCache.get(cacheKeyResponse);
                        // get from cache, if null then get from api call
                        if (el != null) {
                            jsonResponse = el.getObjectValue().toString();
                        } else {
                            executeApi = true;
                        }
                    } else {
                            executeApi = true;
                    }

                    // execute api call
                    if(executeApi){
                         // Connection timeout being checked when trying to connect
                        LogUtil.info(getClass().getName(), "Attempting to connect to " + jsonUrl);
                        long startTime = System.currentTimeMillis();
                        // execute api call
                        response = client.execute(request);
                        long connectionTime = System.currentTimeMillis() - startTime;
                        LogUtil.info(getClass().getName(), "Connection established in " + connectionTime + " ms");
        
                        if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                            LogUtil.info(EnhancedJsonTool.class.getName(), jsonUrl + " returned with status : " + response.getStatusLine().getStatusCode());
                        }   
                    
                        if ("true".equalsIgnoreCase(getPropertyString("responseStoreCache"))) {
                            // Retrieve Cache-Control
                            int statusCode = response.getStatusLine().getStatusCode();
                            
                            Header cacheControlHeader = response.getFirstHeader("Cache-Control");
                            String cacheControl = (cacheControlHeader != null) ? cacheControlHeader.getValue() : null;
                
                            // cache-control attributes
                            boolean noStore = false;
                            if (cacheControl != null && cacheControl != "") {
                                for (String directive : cacheControl.split(",")) {
                                    directive = directive.trim();
                                    if (directive.equals("no-store")) {
                                        noStore = true;
                                    }
                                }
                            }

                            // store response and headers in cache
                            if (!noStore){
                                // store headers
                                StringBuilder headersString = new StringBuilder();
                                for (Header header : response.getAllHeaders()) {
                                    headersString.append(header.getName()).append(": ").append(header.getValue()).append("\n");
                                }
                                String allHeaders = headersString.toString();
                                net.sf.ehcache.Element eleHeaders = new net.sf.ehcache.Element(cacheKeyHeaders, allHeaders);
                                longTermCache.put(eleHeaders);
                                longTermCache.remove(cacheKeyHeaders);
                                longTermCache.put(eleHeaders);

                                // store response
                                jsonResponse = EntityUtils.toString(response.getEntity());
                                net.sf.ehcache.Element eleResponse = new net.sf.ehcache.Element(cacheKeyResponse, jsonResponse);
                                longTermCache.put(eleResponse);
                                longTermCache.remove(cacheKeyResponse);
                                longTermCache.put(eleResponse);
                            }
                        } else {
                            jsonResponse = EntityUtils.toString(response.getEntity());
                        }
                    }
                    //if(response.getEntity().getContentType().getValue().equalsIgnoreCase("application/json")){
                   
                    String jsonResponseFormatted = jsonResponse;
                    if (jsonResponseFormatted != null && !jsonResponseFormatted.isEmpty()) {
                        if (jsonResponseFormatted.startsWith("[") && jsonResponseFormatted.endsWith("]")) {
                            jsonResponseFormatted = "{ \"response\" : " + jsonResponseFormatted + " }";
                        }

                        if (!jsonResponseFormatted.startsWith("{") && !jsonResponseFormatted.endsWith("}")) {
                            jsonResponseFormatted = "{ \"response\" : " + jsonResponseFormatted + " }";
                        }

                        if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                            LogUtil.info(EnhancedJsonTool.class.getName(), jsonResponseFormatted);
                        }
                        jsonResponseObject = PropertyUtil.getProperties(new JSONObject(jsonResponseFormatted));

                        //Added ability to format response via bean shell in configuration
                        if ("true".equalsIgnoreCase(getPropertyString("enableFormatResponse"))) {
                            properties.put("data", jsonResponseObject);

                            String script = (String) properties.get("script");

                            Map<String, String> replaceMap = new HashMap<String, String>();
                            replaceMap.put("\n", "\\\\n");

                            script = WorkflowUtil.processVariable(script, "", wfAssignment, "", replaceMap);
                            jsonResponseObjectRaw = executeScript(script, properties);
                        }

                        String formDefId = (String) properties.get("formDefId");
                        if (formDefId != null && formDefId.trim().length() > 0) {
                            if (jsonResponseObjectRaw != null) {
                                jsonResponseObject = (Map) jsonResponseObjectRaw;
                            }
                            storeToForm(wfAssignment, properties, jsonResponseObject);
                        }

                        Object[] wfVariableMapping = (Object[]) properties.get("wfVariableMapping");
                        if (wfVariableMapping != null && wfVariableMapping.length > 0) {
                            if (jsonResponseObjectRaw != null) {
                                jsonResponseObject = (Map) jsonResponseObjectRaw;
                            }
                            storeToWorkflowVariable(wfAssignment, properties, jsonResponseObject);
                        }
                    }
                } else {
                    //assume binary

                    //attempt to get filename
                    String fileName = "";
                    try {
                        Header header = response.getFirstHeader("Content-Disposition");
                        HeaderElement[] helelms = header.getElements();
                        if (helelms.length > 0) {
                            HeaderElement helem = helelms[0];
                            NameValuePair nmv = helem.getParameterByName("filename");
                            if (nmv != null) {
                                fileName = nmv.getValue();
                            }
                        }
                    } catch (Exception ex) {
                        LogUtil.info(getClass().getName(), "Cannot get file name automatically");
                    }

                    if (fileName.isEmpty()) {
                        String[] n = request.getURI().getPath().split("/");
                        fileName = n[n.length - 1];
                    }

                    if (fileName.isEmpty()) {
                        fileName = "downloaded";
                    }

                    //save filename into existing form row record
                    AppService appService = (AppService) ac.getBean("appService");
                    String recordId = appService.getOriginProcessId(wfAssignment.getProcessId());
                    String formDefId = (String) properties.get("storeAttachmentFormDefId");
                    String fileUploadID = properties.get("storeAttachmentFieldID").toString();

                    FormRowSet rowSet = new FormRowSet();
                    FormRow row = new FormRow();

                    if (recordId.isEmpty()) {
                        recordId = UuidGenerator.getInstance().getUuid();
                        row = new FormRow();
                        row.put(fileUploadID, fileName);
                    } 
                    else {
                        rowSet = appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, recordId);
                        if(rowSet.size() != 0){
                            row = rowSet.get(0);
                            rowSet.remove(0);
                        }
                    }
                    row.put(fileUploadID, fileName);
                    rowSet.add(0, row);

                    appService.storeFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, rowSet, recordId);

                    //save actual file into wflow folder
                    String tableName = appService.getFormTableName(appDef, formDefId);
                    String filePath = FileUtil.getUploadPath(tableName, recordId);

                    File file = new File(filePath);
                    file.mkdirs();

                    filePath = filePath + fileName;

                    FileOutputStream fos = null;
                    try (InputStream is = response.getEntity().getContent()) {
                        fos = new FileOutputStream(new File(filePath));
                        int inByte;
                        while ((inByte = is.read()) != -1) {
                            fos.write(inByte);
                        }
                    } catch (Exception ex) {
                        LogUtil.error(getClass().getName(), ex, "Cannot save file");
                    } finally {
                        fos.close();
                    }
                }
            }

            if (!getPropertyString("responseStatusWorkflowVariable").isEmpty()) {
                workflowManager.activityVariable(wfAssignment.getActivityId(), getPropertyString("responseStatusWorkflowVariable"), String.valueOf(response.getStatusLine().getStatusCode()));
            }

            if (!getPropertyString("responseStatusFormDefId").isEmpty()) {
                storeStatusToForm(wfAssignment, properties, String.valueOf(response.getStatusLine().getStatusCode()), jsonResponse, jsonResponseObject);
            }

            return jsonResponseObjectRaw;

        } catch (ConnectTimeoutException e) {
            LogUtil.error(getClass().getName(), e, "Connection timed out while attempting to connect to " + jsonUrl);
        } catch (SocketTimeoutException e) {
            LogUtil.error(getClass().getName(), e, "Socket timed out while waiting for data from " + jsonUrl);
        } catch (Exception ex) {
            LogUtil.error(getClass().getName(), ex, "");

            if (!getPropertyString("responseStatusWorkflowVariable").isEmpty()) {
                workflowManager.activityVariable(wfAssignment.getActivityId(), getPropertyString("responseStatusWorkflowVariable"), ex.toString());
            }
            if (!getPropertyString("responseStatusFormDefId").isEmpty()) {
                storeStatusToForm(wfAssignment, properties, ex.toString() + " - " + ex.getMessage(), jsonResponse, jsonResponseObject);
            }
        } finally {
            try {
                if (request != null) {
                    request.releaseConnection();
                }
                if (client != null) {
                    client.close();
                }
            } catch (IOException ex) {
                LogUtil.error(getClass().getName(), ex, "");
            }
        }

        return null;
    }

    protected void storeStatusToForm(WorkflowAssignment wfAssignment, Map properties, String status, String jsonResponse, Map object) {
        String formDefId = (String) properties.get("responseStatusFormDefId");
        String statusField = (String) properties.get("responseStatusStatusField");
        String responseDataField = (String) properties.get("responseStatusResponseDataField");
        String idField = (String) properties.get("responseStatusIdField");
        Object[] fieldMapping = (Object[]) properties.get("responseStatusFieldMapping");
        String multirowBaseObjectName = (String) properties.get("responseStatusMultirowBaseObject");
        Object[] fieldMappingAdditional = (Object[]) properties.get("responseStatusFieldMappingAdditional");

        if (formDefId != null && formDefId.trim().length() > 0) {
            ApplicationContext ac = AppUtil.getApplicationContext();
            AppService appService = (AppService) ac.getBean("appService");
            AppDefinition appDef = (AppDefinition) properties.get("appDef");

            FormRowSet rowSet = new FormRowSet();
            FormRow row = new FormRow();

            if (multirowBaseObjectName != null && multirowBaseObjectName.trim().length() > 0 && getObjectFromMap(multirowBaseObjectName, object) != null && getObjectFromMap(multirowBaseObjectName, object).getClass().isArray()) {
                Object[] baseObjectArray = (Object[]) getObjectFromMap(multirowBaseObjectName, object);
                if (baseObjectArray != null && baseObjectArray.length > 0) {
                    rowSet.setMultiRow(true);
                    for (int i = 0; i < baseObjectArray.length; i++) {
                        row = getRow(wfAssignment, multirowBaseObjectName, i, fieldMapping, object);
                    }
                }
            } else {
                row = getRow(wfAssignment, null, null, fieldMapping, object);
            }

            if (!responseDataField.isEmpty()) {
                row.put(responseDataField, jsonResponse);
            }

            if (!idField.isEmpty()) {
                row.put(idField, appService.getOriginProcessId(wfAssignment.getProcessId()));
            } else {
                row.setId(appService.getOriginProcessId(wfAssignment.getProcessId()));
            }

            for (Object obj : fieldMappingAdditional) {
                Map map = (Map) obj;
                row.put(map.get("field").toString(), map.get("value").toString());
            }

            row.put(statusField, status);
            rowSet.add(row);

            appService.storeFormData(appDef.getId(), appDef.getVersion().toString(), formDefId, rowSet, null);
        }
    }

    protected void storeToForm(WorkflowAssignment wfAssignment, Map properties, Map object) {
        String formDefId = (String) properties.get("formDefId");
        if (formDefId != null && formDefId.trim().length() > 0) {
            ApplicationContext ac = AppUtil.getApplicationContext();
            AppService appService = (AppService) ac.getBean("appService");
            AppDefinition appDef = (AppDefinition) properties.get("appDef");

            Object[] fieldMapping = (Object[]) properties.get("fieldMapping");
            String multirowBaseObjectName = (String) properties.get("multirowBaseObject");

            FormRowSet rowSet = new FormRowSet();

            if (multirowBaseObjectName != null && multirowBaseObjectName.trim().length() > 0 && getObjectFromMap(multirowBaseObjectName, object) != null && getObjectFromMap(multirowBaseObjectName, object).getClass().isArray()) {
                Object[] baseObjectArray = (Object[]) getObjectFromMap(multirowBaseObjectName, object);
                if (baseObjectArray != null && baseObjectArray.length > 0) {
                    rowSet.setMultiRow(true);
                    for (int i = 0; i < baseObjectArray.length; i++) {
                        rowSet.add(getRow(wfAssignment, multirowBaseObjectName, i, fieldMapping, object));
                    }
                }
            } else {
                rowSet.add(getRow(wfAssignment, null, null, fieldMapping, object));
            }

            if (!rowSet.isEmpty()) {
                appService.storeFormData(appDef.getId(), appDef.getVersion().toString(), formDefId, rowSet, null);
            }
        }
    }

    protected void storeToWorkflowVariable(WorkflowAssignment wfAssignment, Map properties, Map object) {
        Object[] wfVariableMapping = (Object[]) properties.get("wfVariableMapping");
        if (wfVariableMapping != null && wfVariableMapping.length > 0) {
            ApplicationContext ac = AppUtil.getApplicationContext();
            WorkflowManager workflowManager = (WorkflowManager) ac.getBean("workflowManager");

            for (Object o : wfVariableMapping) {
                Map mapping = (HashMap) o;
                String variable = mapping.get("variable").toString();
                String jsonObjectName = mapping.get("jsonObjectName").toString();

                String value = (String) getObjectFromMap(jsonObjectName, object);

                if (value != null) {
                    workflowManager.activityVariable(wfAssignment.getActivityId(), variable, value);
                }
            }
        }
    }

    protected Object getObjectFromMap(String key, Map object) {
        /*  Added {} annotation to handle the keys which contains . eg { "user.Org":"Joget Inc"} 
            Using annotation like {user.Org} in the field mapping it will be able to parse the records
         */
        if (key.startsWith("{")) {
            String key1 = key.substring(1, key.indexOf("}")); //{search.name}
            Object tempObject = object.get(key1);

            String subKey = key.replace("{" + key1 + "}", ""); //{search.name}.first to .first
            if (subKey.startsWith(".")) {
                subKey = subKey.substring(1);  //first
            }
            if (subKey.length() > 0) {
                if (tempObject != null && tempObject instanceof Map) {
                    return getObjectFromMap(subKey, (Map) tempObject);
                }
            }

            return tempObject;

        } else if (key.contains(".")) {
            String subKey = key.substring(key.indexOf(".") + 1);
            key = key.substring(0, key.indexOf("."));

            Map tempObject = (Map) getObjectFromMap(key, object);

            if (tempObject != null) {
                return getObjectFromMap(subKey, tempObject);
            }
        } else if (key.contains("[") && key.contains("]")) {
            String tempKey = key.substring(0, key.indexOf("["));
            int number = Integer.parseInt(key.substring(key.indexOf("[") + 1, key.indexOf("]")));
            Object tempObjectArray[] = (Object[]) object.get(tempKey);
            if (tempObjectArray != null && tempObjectArray.length > number) {
                return tempObjectArray[number];
            }
        } else {
            return object.get(key);
        }

        return null;
    }

    protected FormRow getRow(WorkflowAssignment wfAssignment, String multirowBaseObjectName, Integer rowNumber, Object[] fieldMapping, Map object) {
        FormRow row = new FormRow();

        for (Object o : fieldMapping) {
            Map mapping = (HashMap) o;
            String fieldName = mapping.get("field").toString();
            String jsonObjectName = WorkflowUtil.processVariable(mapping.get("jsonObjectName").toString(), null, wfAssignment, null, null);

            if (multirowBaseObjectName != null) {
                jsonObjectName = jsonObjectName.replace(multirowBaseObjectName, multirowBaseObjectName + "[" + rowNumber + "]");
            }

            String value = (String) getObjectFromMap(jsonObjectName, object);

            if (value == null) {
                value = jsonObjectName;
            }

            if (FormUtil.PROPERTY_ID.equals(fieldName)) {
                row.setId(value);
            } else {
                row.put(fieldName, value);
            }
        }

        if (row.getId() == null || (row.getId() != null && row.getId().trim().length() == 0)) {
            if (multirowBaseObjectName == null) {
                AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
                row.setId(appService.getOriginProcessId(wfAssignment.getProcessId()));
            } else {
                row.setId(UuidGenerator.getInstance().getUuid());
            }
        }

        Date currentDate = new Date();
        row.setDateModified(currentDate);
        row.setDateCreated(currentDate);

        return row;
    }

    protected String streamToString(InputStream in) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();

            String line = null;
            try {
                while ((line = reader.readLine()) != null) {
                    sb.append(line + "\n");
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    LogUtil.error(getClass().getName(), e, "");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            LogUtil.error(EnhancedJsonTool.class.getName(), e, "");
        }
        return "";
    }

    protected Object executeScript(String script, Map properties) {
        Object result = null;
        try {
            Interpreter interpreter = new Interpreter();
            interpreter.setClassLoader(getClass().getClassLoader());
            for (Object key : properties.keySet()) {
                interpreter.set(key.toString(), properties.get(key));
            }
            LogUtil.debug(getClass().getName(), "Executing script " + script);
            result = interpreter.eval(script);
            return result;
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, "Error executing script");
            return null;
        }
    }

    //copied from AppUtil
    protected String retrieveFileNames(String content, String appId, String formId, String primaryKey) {
        Set<String> values = new HashSet<String>();

        Pattern pattern = Pattern.compile("<img[^>]*src=\"[^\"]*/web/client/app/" + StringUtil.escapeRegex(appId) + "/form/download/" + StringUtil.escapeRegex(formId) + "/" + StringUtil.escapeRegex(primaryKey) + "/([^\"]*)\\.\"[^>]*>");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String fileName = matcher.group(1);
            values.add(fileName);
        }

        return String.join(";", values);
    }

    protected HttpResponse executeApiCall(String responseType, String jsonUrl, CloseableHttpClient client, HttpRequestBase request){
        try{
            if (!responseType.isEmpty()) {

                if (!responseType.equalsIgnoreCase("JSON")) {
        
                    // Connection timeout being checked when trying to connect
                    LogUtil.info(getClass().getName(), "Attempting to connect to " + jsonUrl);
                    long startTime = System.currentTimeMillis();
                    // execute api call
                    HttpResponse response = client.execute(request);
                    long connectionTime = System.currentTimeMillis() - startTime;
                    LogUtil.info(getClass().getName(), "Connection established in " + connectionTime + " ms");
    
                    if ("true".equalsIgnoreCase(getPropertyString("debugMode"))) {
                        LogUtil.info(EnhancedJsonTool.class.getName(), jsonUrl + " returned with status : " + response.getStatusLine().getStatusCode());
                    }   
                    return response;
                }
                return null;
            }
        } catch (Exception ex){
            return null;
        }
        return null;
    }
}
