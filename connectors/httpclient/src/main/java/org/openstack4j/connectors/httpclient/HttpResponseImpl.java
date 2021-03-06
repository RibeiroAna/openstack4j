package org.openstack4j.connectors.httpclient;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.exceptions.ClientResponseException;
import org.openstack4j.api.exceptions.ResponseException;
import org.openstack4j.api.exceptions.ServerResponseException;
import org.openstack4j.core.transport.HttpResponse;
import org.openstack4j.core.transport.ListType;
import org.openstack4j.core.transport.ObjectMapperSingleton;

import com.google.common.base.Function;

public class HttpResponseImpl implements HttpResponse {
    private static final Pattern MESSAGE_PATTERN = Pattern.compile(".*message\\\":\\s\\\"([^\"]+)\\\".*");
    private CloseableHttpResponse response;

    private HttpResponseImpl(CloseableHttpResponse response) {
        this.response = response;
    }

    /**
     * Wrap the given Response
     *
     * @param response the response
     * @return the HttpResponse
     */
    public static HttpResponseImpl wrap(CloseableHttpResponse response) {
        return new HttpResponseImpl(response);
    }

    /**
     * Unwrap and return the original Response
     *
     * @return the response
     */
    public CloseableHttpResponse unwrap() {
        return response;
    }

    /**
     * Gets the entity and Maps any errors which will result in a ResponseException
     *
     * @param <T> the generic type
     * @param returnType the return type
     * @return the entity
     */
    public <T> T getEntity(Class<T> returnType) {
        return getEntity(returnType, null);
    }

    /**
     * Gets the entity and Maps any errors which will result in a ResponseException
     *
     * @param <T> the generic type
     * @param returnType the return type
     * @param parser an optional parser which will handle the HttpResponse and return the corresponding return type.  Error codes are handled and thrown prior to the parser being called
     * @return the entity
     */
    public <T> T getEntity(Class<T> returnType, Function<HttpResponse, T> parser) {
        int status = response.getStatusLine().getStatusCode();
        if(status >= 400) {
            if (status == 404)
            {
                try
                {
                    if (ListType.class.isAssignableFrom(returnType))
                        return returnType.newInstance();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }
            if (status < 500)
            {
                try
                {
                   
                    String json =  EntityUtils.toString(response.getEntity());
                    if (json != null && json.contains("message")) {
                        Matcher m = MESSAGE_PATTERN.matcher(json);
                        if (m.matches())
                        {
                            throw mapException(m.group(1), status);
                        }
                    }
                }
                catch (ResponseException re) {
                    throw re;
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
            throw mapException(response.getStatusLine().getReasonPhrase(), status);
        }

        if (parser != null)
            return parser.apply(this);

        if (returnType == Void.class) return null;
        
        return readEntity(returnType);
    }

    /**
     * Gets the status from the previous Request
     *
     * @return the status code
     */
    public int getStatus() {
        return response.getStatusLine().getStatusCode();
    }

    /**
     * @return the input stream
     */
    public InputStream getInputStream() {
        return (InputStream) response.getEntity();
    }

    /**
     * Returns a Header value from the specified name key
     *
     * @param name the name of the header to query for
     * @return the header as a String or null if not found
     */
    public String header(String name) {
        Header header = response.getFirstHeader(name);
        return (header != null) ? header.getValue() : null;
    }

    /**
     * @return the a Map of Header Name to Header Value
     */
    public Map<String, String> headers() {
        Map<String, String> retHeaders = new HashMap<String, String>();
        Header[] headers =  response.getAllHeaders();

        for (Header h : headers) {
            retHeaders.put(h.getName(), h.getValue());
        }
        return retHeaders;
    }

    /**
     * Maps an Exception based on the underlying status code
     *
     * @param message the message
     * @param status the status
     * @return the response exception
     */
    public static ResponseException mapException(String message, int status) {
        return mapException(message, status, null);
    }

    /**
     * Maps an Exception based on the underlying status code
     *
     * @param message the message
     * @param status the status
     * @param cause the cause
     * @return the response exception
     */
    public static ResponseException mapException(String message, int status, Throwable cause) {
        if (status == 401)
            return new AuthenticationException(message, status, cause);
        if (status >= 400 && status < 499)
            return new ClientResponseException(message, status, cause);
        if (status >= 500 && status < 600)
            return new ServerResponseException(message, status, cause);

        return new ResponseException(message, status, cause);
    }

    @Override
    public <T> T readEntity(Class<T> typeToReadAs) {
        HttpEntity entity = response.getEntity();
        try {
            return ObjectMapperSingleton.getContext(typeToReadAs).reader(typeToReadAs).readValue(entity.getContent());
        } catch (Exception e) {
            e.printStackTrace();
            throw new ClientResponseException(e.getMessage(), 0, e);
        }
    }
}
