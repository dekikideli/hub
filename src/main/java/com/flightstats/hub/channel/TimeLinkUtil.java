package com.flightstats.hub.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightstats.hub.util.TimeUtil;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.*;
import java.net.URI;
import java.util.List;

import static com.flightstats.hub.util.TimeUtil.*;
import static javax.ws.rs.core.Response.Status.SEE_OTHER;

public class TimeLinkUtil {

    private final static Logger logger = LoggerFactory.getLogger(TimeLinkUtil.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    public static Response getDefault(UriInfo uriInfo) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode links = addSelfLink(root, uriInfo);
        addNode(links, "second", "/{year}/{month}/{day}/{hour}/{minute}/{second}", TimeUtil.Unit.SECONDS, uriInfo);
        addNode(links, "minute", "/{year}/{month}/{day}/{hour}/{minute}", TimeUtil.Unit.MINUTES, uriInfo);
        addNode(links, "hour", "/{year}/{month}/{day}/{hour}", TimeUtil.Unit.HOURS, uriInfo);
        addNode(links, "day", "/{year}/{month}/{day}", TimeUtil.Unit.DAYS, uriInfo);
        DateTime now = TimeUtil.now();
        DateTime stable = TimeUtil.stable();
        addTime(root, now, "now");
        addTime(root, stable, "stable");
        return Response.ok(root).build();
    }

    private static ObjectNode addSelfLink(ObjectNode root, UriInfo uriInfo) {
        ObjectNode links = root.putObject("_links");
        ObjectNode self = links.putObject("self");
        self.put("href", uriInfo.getRequestUri().toString());
        return links;
    }

    private static void addNode(ObjectNode links, String name, String template, TimeUtil.Unit unit, UriInfo uriInfo) {
        ObjectNode node = links.putObject(name);
        MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
        String stable = "";
        DateTime dateTime = stable();
        if (queryParameters.containsKey("stable")) {
            String value = queryParameters.getFirst("stable");
            stable = "?stable=" + value;
            if (!Boolean.parseBoolean(value)) {
                dateTime = now();
            }
        }
        String requestUri = StringUtils.removeEnd(uriInfo.getAbsolutePath().toString(), "time");
        node.put("href", requestUri + unit.format(dateTime) + stable);
        node.put("template", uriInfo.getAbsolutePath() + template + "{?stable}");
        node.put("redirect", uriInfo.getAbsolutePath() + "/" + name + stable);
    }

    public static void addTime(ObjectNode root, DateTime time, String name) {
        ObjectNode nowNode = root.putObject(name);
        nowNode.put("iso8601", ISODateTimeFormat.dateTime().print(time));
        nowNode.put("millis", time.getMillis());
    }

    public static Response getSecond(boolean stable, UriInfo uriInfo) {
        return getResponse(seconds(TimeUtil.time(stable)), uriInfo);
    }

    public static Response getMinute(boolean stable, UriInfo uriInfo) {
        return getResponse(minutes(TimeUtil.time(stable)), uriInfo);
    }

    public static Response getHour(boolean stable, UriInfo uriInfo) {
        return getResponse(hours(TimeUtil.time(stable)), uriInfo);
    }

    public static Response getDay(boolean stable, UriInfo uriInfo) {
        return getResponse(days(TimeUtil.time(stable)), uriInfo);
    }

    private static Response getResponse(String timePath, UriInfo uriInfo) {
        List<PathSegment> segments = uriInfo.getPathSegments();
        UriBuilder uriBuilder = uriInfo.getBaseUriBuilder();
        uriBuilder.path(segments.get(0).getPath())
                .path(segments.get(1).getPath())
                .path(timePath);
        addQueryParams(uriInfo, uriBuilder);
        Response.ResponseBuilder builder = Response.status(SEE_OTHER);
        builder.location(uriBuilder.build());
        return builder.build();
    }

    public static void addQueryParams(UriInfo uriInfo, UriBuilder uriBuilder) {
        MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
        for (String param : queryParameters.keySet()) {
            List<String> strings = queryParameters.get(param);
            uriBuilder.queryParam(param, strings.toArray(new String[strings.size()]));
        }
    }

    public static URI getUri(String channel, UriInfo uriInfo, Unit unit, DateTime time) {
        return LinkBuilder.uriBuilder(channel, uriInfo).path(unit.format(time)).build();
    }
}
