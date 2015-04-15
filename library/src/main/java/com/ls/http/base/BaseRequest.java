/*
 * The MIT License (MIT)
 *  Copyright (c) 2014 Lemberg Solutions Limited
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 */

package com.ls.http.base;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.RequestFuture;
import com.android.volley.toolbox.StringRequest;

import com.ls.http.base.handler.Handler;
import com.ls.util.L;

import android.net.Uri;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class BaseRequest extends StringRequest {
    protected static String ACCEPT_HEADER_KEY = "Accept";

    public static enum RequestMethod {
        GET(Method.GET), POST(Method.POST), PATCH(Method.PATCH), DELETE(Method.DELETE), PUT(Method.PUT), HEAD(Method.HEAD), OPTIONS(Method.OPTIONS), TRACE(Method.TRACE);
        final private int methodCode;

        RequestMethod(int theCode) {
            this.methodCode = theCode;
        }
    }

    public static enum RequestFormat {
        JSON, XML, JSON_HAL, TEXT, MULTIPART;

        private ResponseFormat toResponse() {
            switch (this) {
                case MULTIPART:
                    L.e("Multipart response isn't supported. Using JSON");
                    return ResponseFormat.JSON;
                default:
                    return ResponseFormat.valueOf(this.name());
            }
        }
    }

    ;

    public static enum ResponseFormat {
        JSON, XML, JSON_HAL, TEXT
    }

    ;

    private final RequestFormat requestFormat;
    private final ResponseFormat responseFormat;

    private final RequestFuture<String> syncLock;
    private final Object responseClasSpecifier;
    private String defaultCharset;

    private OnResponseListener responseListener;

    private Map<String, String> requestHeaders;
    private Map<String, String> postParameters;
    private Map<String, String> getParameters;
    private Object objectToPost;

    private ResponseData result;

    private RequestHandler requestHandler;
    private ResponseHandler responseHandler;

    @Deprecated
    /**
     * This method is deprecated. Just use public constructor now
     * @param requestMethod
     * @param requestUrl
     * @param requestFormat
     * @param responseClassSpecifier
     *            Class or Type, returned as data field of ResultData object, can be
     *            null if you don't need one.
     * @return
     */
    public static BaseRequest newBaseRequest(RequestMethod requestMethod, String requestUrl, RequestFormat requestFormat, Object responseClassSpecifier) {
        BaseRequest result = new BaseRequest(requestMethod, requestUrl, requestFormat, responseClassSpecifier);
        return result;
    }

    ;

    /**
     * @param theResponseClass Class or Type, returned as data field of ResultData object, can be null if you don't need one.
     */
    public BaseRequest(RequestMethod requestMethod, String requestUrl, RequestFormat requestFormat, Object theResponseClass) {
        this(requestMethod, requestUrl, requestFormat, requestFormat.toResponse(), theResponseClass, getRequestLock());
    }

    /**
     * @param theResponseClass Class or Type, returned as data field of ResultData object, can be null if you don't need one.
     */
    public BaseRequest(RequestMethod requestMethod, String requestUrl, RequestFormat requestFormat, ResponseFormat responseFormat, Object theResponseClass) {
        this(requestMethod, requestUrl, requestFormat, responseFormat, theResponseClass, getRequestLock());
    }

    /**
     * @param theResponseClass Class or Type, returned as data field of ResultData object, can be null if you don't need one.
     * @param lock             autogenerated object used to perform synchronized requests
     */
    protected BaseRequest(RequestMethod requestMethod, String requestUrl, RequestFormat requestFormat, ResponseFormat responseFormat, Object theResponseClass, RequestFuture<String> lock) {
        super(requestMethod.methodCode, requestUrl, lock, lock);
        this.requestFormat = requestFormat;
        this.responseFormat = responseFormat;
        this.syncLock = lock;
        this.initRequestHeaders();
        this.responseClasSpecifier = theResponseClass;
        this.result = new ResponseData();
        this.requestHandler = Handler.getRequestHandlerForFormat(this.requestFormat);
        this.responseHandler = Handler.getResponseHandlerForFormat(this.responseFormat);
    }


    public Object getResponseClasSpecifier() {
        return responseClasSpecifier;
    }

    private void initRequestHeaders() {
        this.requestHeaders = new HashMap<String, String>();
        this.addRequestHeader(ACCEPT_HEADER_KEY,this.responseHandler.getAcceptValueType());
    }

    public ResponseData performRequest(boolean synchronous, RequestQueue theQueque) {
        theQueque.add(this);

        if (synchronous) {
            try {
                @SuppressWarnings("unused")
                String response = this.syncLock.get(); // this call will block
                // thread
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        return this.result;
    }

    @Override
    protected Response<String> parseNetworkResponse(NetworkResponse response) {
        Response<String> requestResult;
        if (response.data != null) {
            requestResult = super.parseNetworkResponse(response);
        } else {
            requestResult = Response.success("{}", HttpHeaderParser.parseCacheHeaders(response));
        }
        if (!this.isCanceled()) {
            this.result.error = requestResult.error;
            this.result.statusCode = response.statusCode;
            this.result.headers = new HashMap<String, String>(response.headers);
            this.result.responseString = requestResult.result;
            if (requestResult.result != null) {
                this.responseHandler.itemFromResponseWithSpecifier(requestResult.result, responseClasSpecifier);
            }
        }
        return requestResult;
    }

    @Override
    protected VolleyError parseNetworkError(VolleyError volleyError) {
        VolleyError error = super.parseNetworkError(volleyError);
        if (volleyError.networkResponse != null) {
            this.result.headers = new HashMap<String, String>(volleyError.networkResponse.headers);
            this.result.statusCode = volleyError.networkResponse.statusCode;
            try {
                this.parseNetworkResponse(volleyError.networkResponse);
            } catch (Exception e) {
//                e.printStackTrace();//We don't need this trace
            }
        }
        this.result.error = error;
        return error;
    }

    @Override
    protected void deliverResponse(String response) {
        if (this.responseListener != null) {
            this.responseListener.onResponseReceived(result, this);
        }
        this.syncLock.onResponse(response);
    }

    @Override
    public void deliverError(VolleyError error) {
        if (this.responseListener != null) {
            this.responseListener.onError(error, this);
        }
        this.syncLock.onErrorResponse(error);
    }

    public static interface OnResponseListener {

        void onResponseReceived(ResponseData data, BaseRequest request);

        void onError(VolleyError error, BaseRequest request);
    }

    public OnResponseListener getResponseListener() {
        return responseListener;
    }

    public void setResponseListener(OnResponseListener responseListener) {
        this.responseListener = responseListener;
    }

    // Header parameters handling

    @Override
    public Map<String, String> getHeaders() throws AuthFailureError {
        Map<String, String> result = new HashMap<String, String>();
        result.putAll(super.getHeaders());
        if (this.requestHeaders != null) {
            result.putAll(this.requestHeaders);
        }
        return result;
    }

    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public void addRequestHeaders(Map<String, String> theRequestHeaders) {
        this.requestHeaders.putAll(theRequestHeaders);
    }

    public void addRequestHeader(String key, String value) {
        this.requestHeaders.put(key, value);
    }

    // Post parameters handling

    @Override
    protected Map<String, String> getParams() throws AuthFailureError {
        if (this.postParameters != null) {
            return this.postParameters;
        } else {
            return super.getParams();
        }
    }

    public Map<String, String> getPostParameters() {
        return postParameters;
    }

    public void setPostParameters(Map<String, String> postParameters) {
        this.postParameters = postParameters;
    }

    public void addPostParameters(Map<String, String> postParameters) {
        if (this.postParameters == null) {
            this.postParameters = postParameters;
        } else {
            this.postParameters.putAll(postParameters);
        }
    }

    public void addPostParameter(String key, String value) {
        if (this.postParameters == null) {
            this.postParameters = new HashMap<String, String>();
        }
        if (value == null) {
            this.postParameters.remove(key);
        } else {
            this.postParameters.put(key, value);
        }
    }

    // Post Body handling

    @SuppressWarnings("null")
    @Override
    public byte[] getBody() throws AuthFailureError {
        if (this.objectToPost != null && this.postParameters == null) {

            try {
                return requestHandler.getBody(this.defaultCharset);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                return new byte[0];
            }
        } else {
            return super.getBody();
        }
    }

    @SuppressWarnings("null")
    @Override
    public String getBodyContentType() {
        if (this.objectToPost != null) {
            requestHandler.getBodyContentType(this.defaultCharset);
        }

        return super.getBodyContentType();

    }

    public Object getObjectToPost() {
        return objectToPost;
    }

    public void setObjectToPost(Object objectToPost) {
        this.objectToPost = objectToPost;
        this.requestHandler.setObject(this.objectToPost);
    }

    // Get parameters handling

    @Override
    public String getUrl() {
        if (this.getParameters != null && !this.getParameters.isEmpty()) {
            Uri.Builder builder = Uri.parse(super.getUrl()).buildUpon();
            for (Map.Entry<String, String> entry : this.getParameters.entrySet()) {
                builder.appendQueryParameter(entry.getKey(), entry.getValue());
            }
            String urlString = builder.build().toString();
            return urlString;
        } else {
            return super.getUrl();
        }
    }

    public Map<String, String> getGetParameters() {
        return getParameters;
    }

    public void setGetParameters(Map<String, String> getParameters) {
        this.getParameters = getParameters;
    }

    public void addGetParameters(Map<String, String> getParameters) {
        if (this.getParameters == null) {
            this.getParameters = getParameters;
        } else {
            this.getParameters.putAll(getParameters);
        }
    }

    public void addGetParameter(String key, String value) {
        if (this.getParameters == null) {
            this.getParameters = new HashMap<String, String>();
        }
        if (value == null) {
            this.getParameters.remove(key);
        } else {
            this.getParameters.put(key, value);
        }
    }

    public String getDefaultCharset() {
        return defaultCharset;
    }

    /**
     * @param defaultCharset charset, used to encode post body.
     */
    public void setDefaultCharset(String defaultCharset) {
        this.defaultCharset = defaultCharset;
    }

    @Override
    public void cancel() {
        this.syncLock.onResponse(null);
        super.cancel();
    }

    private String getUnparameterizedURL() {
        return super.getUrl();
    }

    @Override
    public String toString() {
        return "BaseRequest{" +
                "requestFormat=" + requestFormat +
                ", responseClasSpecifier=" + responseClasSpecifier +
                ", defaultCharset='" + defaultCharset + '\'' +
                ", responseListener=" + responseListener +
                ", requestHeaders=" + requestHeaders +
                ", postParameters=" + postParameters +
                ", getParameters=" + getParameters +
                ", objectToPost=" + objectToPost +
                "} " + super.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BaseRequest)) {
            return false;
        }

        BaseRequest that = (BaseRequest) o;

        String url = getUnparameterizedURL();
        String otherUrl = getUnparameterizedURL();

        if (url != null ? !url.equals(otherUrl) : otherUrl != null) {
            return false;
        }

        if (defaultCharset != null ? !defaultCharset.equals(that.defaultCharset) : that.defaultCharset != null) {
            return false;
        }
        if (requestFormat != that.requestFormat) {
            return false;
        }
        if (getParameters != null ? !getParameters.equals(that.getParameters) : that.getParameters != null) {
            return false;
        }
        if (objectToPost != null ? !objectToPost.equals(that.objectToPost) : that.objectToPost != null) {
            return false;
        }
        if (postParameters != null ? !postParameters.equals(that.postParameters) : that.postParameters != null) {
            return false;
        }
        if (requestHeaders != null ? !requestHeaders.equals(that.requestHeaders) : that.requestHeaders != null) {
            return false;
        }
        if (responseClasSpecifier != null ? !responseClasSpecifier.equals(that.responseClasSpecifier) : that.responseClasSpecifier != null) {
            return false;
        }
        if (responseListener != null ? !responseListener.equals(that.responseListener) : that.responseListener != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = requestFormat != null ? requestFormat.hashCode() : 0;
        result = 31 * result + (responseClasSpecifier != null ? responseClasSpecifier.hashCode() : 0);
        result = 31 * result + (defaultCharset != null ? defaultCharset.hashCode() : 0);
        result = 31 * result + (responseListener != null ? responseListener.hashCode() : 0);
        result = 31 * result + (requestHeaders != null ? requestHeaders.hashCode() : 0);
        result = 31 * result + (postParameters != null ? postParameters.hashCode() : 0);
        result = 31 * result + (getParameters != null ? getParameters.hashCode() : 0);
        result = 31 * result + (objectToPost != null ? objectToPost.hashCode() : 0);
        result = 31 * result + (getUnparameterizedURL() != null ? getUnparameterizedURL().hashCode() : 0);
        return result;
    }

    private static RequestFuture<String> getRequestLock() {
        RequestFuture<String> lock = RequestFuture.newFuture();
        return lock;
    }
}
