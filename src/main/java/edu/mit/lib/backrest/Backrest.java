/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;

import javax.servlet.ServletOutputStream;

import spark.Request;
import spark.Response;
import static spark.route.RouteOverview.*;
import spark.QueryParamsMap;
import static spark.Spark.*;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.io.ByteStreams;
import static com.google.common.base.Strings.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.json.MetricsModule;
import com.codahale.metrics.jdbi.InstrumentedTimingCollector;
import static com.codahale.metrics.MetricRegistry.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.honeybadger.reporter.HoneybadgerReporter;
import io.honeybadger.reporter.NoticeReporter;

import static edu.mit.lib.backrest.MetadataValue.*;
import static edu.mit.lib.backrest.Cache.*;

/**
 * Backrest is a read-only DSpace REST API service designed to run
 * on versions of DSpace that do not support the 'official' REST API.
 * Read-only means that the only supported method is HTTP GET.
 *
 * @author richardrodgers
 */
public class Backrest {

    private static NoticeReporter reporter;
    private static final MetricRegistry metrics = new MetricRegistry();
    private static final Meter svcReqs = metrics.meter(name(Backrest.class, "service", "requests"));
    private static final Timer respTime = metrics.timer(name(Backrest.class, "service", "responseTime"));
    private static final Map<String, String> tokenMap = new ConcurrentHashMap<>();
    static final Logger logger = LoggerFactory.getLogger(Backrest.class);
    static final DateTimeFormatter clFmt = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");
    static String assetLocator;
    static int version;

    public static void main(String[] args) throws Exception {

        Properties props = findConfig(args);
        DBI dbi = new DBI(props.getProperty("dburl"), props);
        // worry about supported versions if not in test-mode
        if (! props.getProperty("dburl").contains("h2")) {
            try (Handle hdl = dbi.open()) {
                version = DSpaceObject.versionProbe(hdl);
            } catch (Exception e) {
                logger.error("Exception probing version: {}", e.getMessage());
            }
            if (version < 14) {
                logger.error("Unsupported DSpace version: {}", version);
                System.exit(1);
            }
        } else {
            version = 30;  // test DB version
        }
        assetLocator = props.getProperty("assets");
        // Advanced instrumentation/metrics if requested
        if (System.getenv("BACKREST_DB_METRICS") != null) {
            dbi.setTimingCollector(new InstrumentedTimingCollector(metrics));
        }
        // reassign default port 4567
        if (System.getenv("BACKREST_SVC_PORT") != null) {
            port(Integer.valueOf(System.getenv("BACKREST_SVC_PORT")));
        }
        // if API key given, use exception monitoring service
        if (System.getenv("HONEYBADGER_API_KEY") != null) {
            reporter = new HoneybadgerReporter();
        }
        // If redis service available, use it for caching, else local
        boolean doCaching = System.getenv("BACKREST_CACHE") != null;
        if (doCaching && System.getenv("BACKREST_REDIS_HOST") != null) {
            setCache("redis");
        } else if (doCaching) {
            setCache("local");
        }

        before((req, res) -> {
            // Instrument all the things!
            res.header("Access-Control-Allow-Origin","*");
            svcReqs.mark();
            req.attribute("timerCtx", respTime.time());
            getIfCachable(req);
        });

        options("/*", (req, res) -> {
            String accessControlRequestHeaders = req.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                res.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }
            String accessControlRequestMethod = req.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                res.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }
            res.header("Access-Control-Allow-Credentials", "true");
            res.header("Access-Control-Expose-Headers", "*");
            res.status(200);
            return "OK";
        });

        get("/ping", (req, res) -> {
            res.type("text/plain");
            res.header("Cache-Control", "must-revalidate,no-cache,no-store");
            return "pong";
        });

        get("/metrics", (req, res) -> {
            res.type("application/json");
            res.header("Cache-Control", "must-revalidate,no-cache,no-store");
            ObjectMapper objectMapper = new ObjectMapper().registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true));
            try (ServletOutputStream outputStream = res.raw().getOutputStream()) {
                objectMapper.writer().withDefaultPrettyPrinter().writeValue(outputStream, metrics);
            }
            return "";
        });

        get("/shutdown", (req, res) -> {
            boolean auth = false;
            try {
                if (! isNullOrEmpty(System.getenv("BACKREST_SHUTDOWN_KEY")) &&
                    ! isNullOrEmpty(req.queryParams("key")) &&
                    System.getenv("BACKREST_SHUTDOWN_KEY").equals(req.queryParams("key"))) {
                    auth = true;
                    return "Shutting down";
                } else {
                    res.status(401);
                    return "Not authorized";
                }
            } finally {
                if (auth) {
                    shutdownCache();
                    stop();
                }
            }
        });

        get("/cache", (req, res) -> {
            if (cacheActive()) {
                return dataToMedia(req, res, cacheStatus());
            } else {
                res.status(404);
                return "Cache not active";
            }
        });

        post("/cache", (req, res) -> {
            if (cacheActive()) {
                cacheControl(req.queryParams("command"));
                res.status(202);
                return "Cache command received";
            } else {
                res.status(404);
                return "Cache not active";
            }
        });

        post("/login", (req, res) -> {
            try (Handle hdl = dbi.open()) {
                Security.User user = userFromRequest(req);
                String fullName = user.authenticate(hdl);
                if (fullName != null) {
                    // if already logged in - don't mint a new token
                    String token = null;
                    String value = Joiner.on("||").join(user.email, fullName);
                    if (! tokenMap.containsValue(value)) {
                        token = UUID.randomUUID().toString();
                        tokenMap.put(token, value);
                    } else {
                        // find it in map - ugh
                        token = tokenMap.entrySet().stream().filter(e -> e.getValue().equals(value))
                                        .collect(Collectors.toList()).get(0).getKey();
                    }
                    return token;
                } else {
                    res.status(403);
                    return "No valid credentials given";
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        options("/logout", (req, res) -> {
            res.header("Access-Control-Allow-Headers","rest-dspace-token");
            res.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            return "Preflight ok";
        });

        post("/logout", (req, res) -> {
            String token = req.headers("rest-dspace-token");
            if (token != null && tokenMap.containsKey(token)) {
                tokenMap.remove(token);
                return "So long!";
            } else {
                res.status(400);
                return "Missing or unknown token";
            }
        });

        options("/status", (req, res) -> {
            res.header("Access-Control-Allow-Headers","rest-dspace-token");
            res.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            return "Preflight ok";
        });

        get("/status", (req, res) -> {
            String token = req.headers("rest-dspace-token");
            Object status = new Status();
            if (token != null) {
                String statStr = tokenMap.get(token);
                if (statStr != null)  {
                    Iterator<String> parts = Splitter.on("||").split(statStr).iterator();
                    status = new Status(parts.next(), parts.next(), token);
                }
            }
            return dataToMedia(req, res, status);
        });

        get("/handle/:prefix/:suffix", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                String handle = req.params(":prefix") + "/" + req.params(":suffix");
                DSpaceObject dso = DSpaceObject.findByHandle(hdl, handle);
                if (dso == null) {
                    res.status(404);
                    return "No such handle: " + handle;
                } else {
                    switch (dso.type) {
                        case "community": dso = Community.findById(hdl, dso.id, req.queryMap()); break;
                        case "collection": dso = Collection.findById(hdl, dso.id, req.queryMap()); break;
                        case "item": dso = Item.findById(hdl, dso.id, req.queryMap()); break;
                        default: break;
                    }
                    return dataToMedia(req, res, dso);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/communities", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                List<Community> comms = Community.findAll(hdl, false, req.queryMap());
                return acceptXml(req) ? dataToXml(res, new Community.XList(comms)) :
                                        dataToJson(res, comms);
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/communities/top-communities", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                List<Community> comms = Community.findAll(hdl, true, req.queryMap());
                return acceptXml(req) ? dataToXml(res, new Community.XList(comms)) :
                                        dataToJson(res, comms);
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/communities/:communityId", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Community comm = Community.findById(hdl, Integer.valueOf(req.params(":communityId")), req.queryMap());
                if (comm == null) {
                    res.status(404);
                    return "No such community: " + req.params(":communityId");
                } else {
                    return dataToMedia(req, res, comm);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/communities/:communityId/collections", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Community comm = Community.findById(hdl, Integer.valueOf(req.params(":communityId")), null);
                if (comm == null) {
                    res.status(404);
                    return "No such community: " + req.params(":communityId");
                } else {
                    List<Collection> colls = Collection.findByComm(hdl, comm.id, req.queryMap());
                    return acceptXml(req) ? dataToXml(res, new Collection.XList(colls)) :
                                            dataToJson(res, colls);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/communities/:communityId/communities", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Community comm = Community.findById(hdl, Integer.valueOf(req.params(":communityId")), null);
                if (comm == null) {
                    res.status(404);
                    return "No such community: " + req.params(":communityId");
                } else {
                    List<Community> comms = Community.findSubs(hdl, comm.id, req.queryMap());
                    return acceptXml(req) ? dataToXml(res, new Community.XList(comms)) :
                                            dataToJson(res, comms);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/collections", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                List<Collection> colls = Collection.findAll(hdl, req.queryMap());
                return acceptXml(req) ? dataToXml(res, new Collection.XList(colls)) :
                                        dataToJson(res, colls);
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/collections/:collectionId", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Collection coll = Collection.findById(hdl, Integer.valueOf(req.params(":collectionId")), req.queryMap());
                if (coll == null) {
                    res.status(404);
                    return "No such collection: " + req.params(":collectionId");
                } else {
                    return dataToMedia(req, res, coll);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/collections/:collectionId/items", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Collection coll = Collection.findById(hdl, Integer.valueOf(req.params(":collectionId")), null);
                if (coll == null) {
                    res.status(404);
                    return "No such collection: " + req.params(":collectionId");
                } else {
                    QueryParamsMap params = req.queryMap();
                    List<Item> items = Item.findByColl(hdl, coll.id, params,
                                            limitFromParam(params), offsetFromParam(params));
                    return acceptXml(req) ? dataToXml(res, new Item.XList(items)) :
                                            dataToJson(res, items);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/items", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                List<Item> items = Item.findAll(hdl, req.queryMap());
                return acceptXml(req) ? dataToXml(res, new Item.XList(items)) :
                                        dataToJson(res, items);
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/items/:itemId", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Item item = Item.findById(hdl, Integer.valueOf(req.params(":itemId")), req.queryMap());
                if (item == null) {
                    res.status(404);
                    return "No such item: " + req.params(":itemId");
                } else {
                    return dataToMedia(req, res, item);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/items/:itemId/metadata", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Item item = Item.findById(hdl, Integer.valueOf(req.params(":itemId")), req.queryMap());
                if (item == null) {
                    res.status(404);
                    return "No such item: " + req.params(":itemId");
                } else {
                    List<MetadataValue> mdList = MetadataValue.findByItem(hdl, item.id);
                    return acceptXml(req) ? dataToXml(res, new MetadataValue.XList(mdList)) :
                                            dataToJson(res, mdList);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/items/:itemId/bitstreams", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Item item = Item.findById(hdl, Integer.valueOf(req.params(":itemId")), req.queryMap());
                if (item == null) {
                    res.status(404);
                    return "No such item: " + req.params(":itemId");
                } else {
                    List<Bitstream> bitstreams = Bitstream.findByItem(hdl, item.id);
                    return acceptXml(req) ? dataToXml(res, new Bitstream.XList(bitstreams)) :
                                            dataToJson(res, bitstreams);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/bitstreams", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                List<Bitstream> bitstreams = Bitstream.findAll(hdl, req.queryMap());
                return acceptXml(req) ? dataToXml(res, new Bitstream.XList(bitstreams)) :
                                        dataToJson(res, bitstreams);
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/bitstreams/:bitstreamId", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Bitstream bitstream = Bitstream.findById(hdl, Integer.valueOf(req.params(":bitstreamId")), req.queryMap());
                if (bitstream == null) {
                    res.status(404);
                    return "No such bitstream: " + req.params(":bitstreamId");
                } else {
                    return dataToMedia(req, res, bitstream);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/bitstreams/:bitstreamId/policy", (req, res) -> {
            if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                Bitstream bitstream = Bitstream.findById(hdl, Integer.valueOf(req.params(":bitstreamId")), req.queryMap());
                if (bitstream == null) {
                    res.status(404);
                    return "No such bitstream: " + req.params(":bitstreamId");
                } else {
                    List<ResourcePolicy> policies = ResourcePolicy.findByResource(hdl, Bitstream.TYPE, bitstream.id);
                    return acceptXml(req) ? dataToXml(res, new ResourcePolicy.XList(policies)) :
                                            dataToJson(res, policies);
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/bitstreams/:bitstreamId/retrieve", (req, res) -> {
            try (Handle hdl = dbi.open()) {
                Bitstream bitstream = Bitstream.findById(hdl, Integer.valueOf(req.params(":bitstreamId")), req.queryMap());
                if (bitstream == null) {
                    res.status(404);
                    return "No such bitstream: " + req.params(":bitstreamId");
                } else {
                    if (isNullOrEmpty(Backrest.assetLocator)) {
                      res.status(403);
                      return "Inaccessible bitstream: " + req.params(":bitstreamId");
                    } else {
                        res.status(200);
                        res.type(bitstream.mimeType);
                        res.header("Content-Length", Long.toString(bitstream.sizeBytes));
                        OutputStream resOut = res.raw().getOutputStream();
                        InputStream resIn = bitstream.retrieve(hdl);
                        ByteStreams.copy(resIn, resOut);
                        resIn.close();
                        resOut.close();
                        return "";
                    }
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        get("/mama", (req, res) -> {
            if (isNullOrEmpty(req.queryParams("qf")) || isNullOrEmpty(req.queryParams("qv"))) {
                halt(400, "Must supply field and value query parameters 'qf' and 'qv'");
            }
            //if (inCache(req)) return fromCache(req, res);
            try (Handle hdl = dbi.open()) {
                if (findFieldId(hdl, req.queryParams("qf")) != -1) {
                    List<String> results = findItems(hdl, req.queryParams("qf"), req.queryParams("qv"), req.queryParamsValues("rf"));
                    if (results.size() > 0) {
                        res.type("application/json");
                        return "{ " +
                                  jsonValue("field", req.queryParams("qf"), true) + ",\n" +
                                  jsonValue("value", req.queryParams("qv"), true) + ",\n" +
                                  jsonValue("items", results.stream().collect(Collectors.joining(",", "[", "]")), false) + "\n" +
                               " }";
                    } else {
                        res.status(404);
                        return "No items found for: " + req.queryParams("qf") + "::" + req.queryParams("qv");
                    }
                } else {
                    res.status(404);
                    return "No such field: " + req.queryParams("qf");
                }
            } catch (Exception e) {
                return internalError(e, res);
            }
        });

        enableRouteOverview("/debug/routes");

        get("*", (req, res) -> {
            res.status(404);
            return "No such page";
        });

        after((req, res) -> {
            Timer.Context context = (Timer.Context)req.attribute("timerCtx");
            context.stop();
            remember(req, res.body());
            // log each request, in more or less 'CLF' aka Apache format
            String clfTime = ZonedDateTime.now().format(clFmt);
            logger.info("{} - - [{}] \"{} {} {}\" {} {}", req.ip(), clfTime, req.requestMethod(),
                        req.pathInfo(), req.protocol(), res.raw().getStatus(), res.body().length());
        });

        awaitInitialization();
    }

    private static String internalError(Exception e, Response res) {
        if (null != reporter) reporter.reportError(e);
        res.status(500);
        return "Internal system error: " + e.getMessage();
    }

    private static Properties findConfig(String[] args) {
        Properties props = new Properties();
        if (args.length == 1) {
            Properties dsProps = new Properties();
            try (FileReader reader = new FileReader(new File(args[0]))){
                dsProps.load(reader);
                props.setProperty("dburl", dsProps.getProperty("db.url"));
                props.setProperty("user", dsProps.getProperty("db.username"));
                props.setProperty("password", dsProps.getProperty("db.password"));
                props.setProperty("assets", dsProps.getProperty("assetstore.dir"));
            } catch (Exception e) {}
        } else if (args.length == 3) {
            props.setProperty("dburl", args[0]);
            props.setProperty("user", args[1]);
            props.setProperty("password", args[2]);
            props.setProperty("assets", "");
        } else {
            props.setProperty("dburl", nullToEmpty(System.getenv("BACKREST_DB_URL")));
            props.setProperty("user", nullToEmpty(System.getenv("BACKREST_DB_USER")));
            props.setProperty("password", nullToEmpty(System.getenv("BACKREST_DB_PASSWD")));
            props.setProperty("assets", nullToEmpty(System.getenv("BACKREST_ASSETS")));
            props.setProperty("readOnly", "true"); // Postgres only, h2 chokes on this directive
        }
        return props;
    }

    private static boolean acceptXml(Request req) {
        String accept = req.headers("Accept");
        return accept != null && accept.contains("application/xml");
    }

    static String responseContentType(Request req) {
        String accept = req.headers("Accept");
        if (accept != null && accept.contains("application/xml")) {
            return "application/xml";
        } else {
            return "application/json"; // default
        }
    }

    private static String dataToMedia(Request req, Response res, Object data) {
        String accept = req.headers("Accept");
        if (null != accept && accept.contains("application/xml")) {
            return dataToXml(res, data);
        } else {
            return dataToJson(res, data);
        }
    }

    private static String dataToJson(Response res, Object data) {
        res.type("application/json");
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            StringWriter sw = new StringWriter();
            mapper.writeValue(sw, data);
            return sw.toString();
        } catch (IOException e) {
            throw new RuntimeException("IOException from ObjectMapper: " + e.getMessage());
        }
    }

    private static String dataToXml(Response res, Object data) {
        res.type("application/xml");
        try {
            JAXBContext context = JAXBContext.newInstance(data.getClass());
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            StringWriter sw = new StringWriter();
            marshaller.marshal(data, sw);
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("JAXB Exception: " + e.getMessage());
        }
    }

    private static Security.User userFromRequest(Request req) {
        String ctype = req.headers("Content-Type");
        if (null == ctype || ctype.contains("application/xml")) {
            try {
                JAXBContext context = JAXBContext.newInstance(Security.User.class);
                Unmarshaller unmarshaller = context.createUnmarshaller();
                StringBuffer xmlStr = new StringBuffer(req.body());
                return (Security.User)unmarshaller.unmarshal(new StreamSource(new StringReader(xmlStr.toString())));
            } catch (Exception e) {
                throw new RuntimeException("JAXB Exception: " + e.getMessage());
            }
        } else { // assume it's JSON
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode jsonUser = mapper.readTree(req.body());
                return new Security.User(jsonUser.findValue("email").asText(), jsonUser.findValue("password").asText());
            } catch (Exception e) {
                throw new RuntimeException("IOException from ObjectMapper: " + e.getMessage());
            }
        }
    }

    private static String jsonValue(String name, String value, boolean primitive) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(name).append("\": ");
        if (primitive) sb.append("\""); sb.append(value); if (primitive) sb.append("\"");
        return sb.toString();
    }

    static String jsonObject(List<MetadataValue> props) {
        return props.stream().map(p -> jsonValue(p.key, p.value, true)).collect(Collectors.joining(",", "{", "}"));
    }

    private static List<String> expandList(QueryParamsMap params) {
        if (params != null) {
            String expands = params.value("expand");
            if (expands != null) {
                return new ArrayList<String>(Arrays.asList(expands.split(",")));
            }
        }
        return new ArrayList<String>();
    }

    static List<String> toExpandList(QueryParamsMap params, List<String> canExpand) {
        List<String> toExpand = expandList(params);
        if (toExpand.contains("all")) {
            toExpand.addAll(canExpand);
            canExpand.clear();
        } else {
            canExpand.removeAll(toExpand);
        }
        return toExpand;
    }

    static int limitFromParam(QueryParamsMap params) {
        String limit = (params != null) ? params.value("limit") : null;
        return isNullOrEmpty(limit) ? 100 : Integer.valueOf(limit);
    }

    static int offsetFromParam(QueryParamsMap params) {
        String offset = (params != null) ? params.value("offset") : null;
        return isNullOrEmpty(offset) ? 0 : Integer.valueOf(offset);
    }

    @XmlRootElement(name="status")
    static class Status {

        public boolean okay;
        public boolean authenticated;
        public String email;
        public String fullname;
        public String token;

        Status() {
            this.okay = true;
            this.authenticated = false;
        }

        Status(String email, String fullname, String token) {
            this.okay = true;
            this.authenticated = true;
            this.email = email;
            this.fullname = fullname;
            this.token = token;
        }
    }
}
