/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.IntegerMapper;
import org.skife.jdbi.v2.util.StringMapper;

import static com.google.common.base.Strings.*;

import spark.QueryParamsMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Bitstream is a RESTful representation of a DSpace Bitstream
 *
 * @author richardrodgers
 */
@XmlRootElement(name="bitstream")
public class Bitstream extends DSpaceObject {

    public static final int TYPE = 0;
    public static final String SELECT = "select * from bitstream ";
    private static final Map<Integer, Format> formats = new HashMap<>();

    public String retrieveLink;
    public String format;
    public String mimeType;
    public CheckSum checkSum;
    public String description;
    public String bundleName;
    @JsonIgnore
    @XmlTransient
    public String internalId;
    public int sequenceId;
    public long sizeBytes;
    public DSpaceObject parent;
    public List<ResourcePolicy> policies;

    // JAXB needs
    Bitstream() {}

    Bitstream(int bsId, String name, long sizeBytes, CheckSum checkSum, String description,
              String bundleName, Format format, String internalId, int sequenceId,
              DSpaceObject parent, List<ResourcePolicy> policies, List<String> canExpand) {
        super(bsId, name, null, "bitstream", "/bitstreams/" + bsId, canExpand);
        this.retrieveLink = "/bitstreams/" + bsId + "/retrieve";
        this.format = (format != null) ? format.shortName : "unknown";
        this.mimeType = (format != null) ? format.mimeType : "application/octet-stream";
        this.sizeBytes = sizeBytes;
        this.checkSum = checkSum;
        this.description = description;
        this.bundleName = bundleName;
        this.internalId = internalId;
        this.sequenceId = sequenceId;
        this.parent = parent;
        this.policies = policies;
    }

    static List<Bitstream> findAll(Handle hdl, QueryParamsMap params) {
        int limit = Backrest.limitFromParam(params);
        int offset = Backrest.offsetFromParam(params);
        return hdl.createQuery(SELECT + "order by name limit ? offset ?")
                  .bind(0, limit).bind(1, offset)
                  .map(new BitstreamMapper(hdl, null)).list();
    }

    static List<Bitstream> findByItem(Handle hdl, int itemId) {
        String queryString = "select bitstream.* from bitstream, bundle2bitstream, item2bundle " +
                             "where bitstream.bitstream_id=bundle2bitstream.bitstream_id " +
                             "and item2bundle.bundle_id=bundle2bitstream.bundle_id " +
                             "and item2bundle.item_id = ?";
        return hdl.createQuery(queryString)
                  .bind(0, itemId)
                  .map(new BitstreamMapper(hdl, null)).list();
    }

    static Bitstream findById(Handle hdl, int bsId, QueryParamsMap params) {
        return hdl.createQuery(SELECT + " where bitstream_id = ?")
                   .bind(0, bsId)
                   .map(new BitstreamMapper(hdl, params)).first();
    }

    static int findOwner(Handle hdl, int bsId) {
        String queryString = "select item.item_id from item, item2bundle, bundle2bitstream " +
                             "where item.item_id=item2bundle.item_id " +
                             "and item2bundle.bundle_id=bundle2bitstream.bundle_id " +
                             "and bundle2bitstream.bitstream_id = ?";
        return hdl.createQuery(queryString)
                  .bind(0, bsId)
                  .map(new IntegerMapper())
                  .first();
    }

    static String bundleName(Handle hdl, int bsId) {
        String queryString = "select bundle.name from bundle, bundle2bitstream " +
                             "where bundle.bundle_id=bundle2bitstream.bundle_id " +
                             "and bundle2bitstream.bitstream_id = ?";
        return hdl.createQuery(queryString)
                  .bind(0, bsId)
                  .map(StringMapper.FIRST)
                  .first();
    }

    static Format format(Handle hdl, int fmtId) {
        if (! formats.containsKey(fmtId)) {
            String queryString = "select * from bitstreamformatregistry " +
                                 "where bitstream_format_id = ?";
            Format fmt = hdl.createQuery(queryString)
                            .bind(0, fmtId)
                            .map(new FormatMapper())
                            .first();
            if (fmt == null) {
                fmt = new Format("unknown", "Unknown", "application/octet-stream");
            }
            formats.put(fmtId, fmt);
        }
        return formats.get(fmtId);
    }

    public InputStream retrieve(Handle hdl) throws IOException, URISyntaxException {
        // strategy based on assetLocator scheme
        URI locatorUri = new URI(Backrest.assetLocator);
        String scheme = locatorUri.getScheme();
        if (scheme.equals("file")) {
            // construct a filesystem path to asset -
            // makes a ridiculous number of simplifying assumptions
            Path assetFile = Paths.get(locatorUri).resolve(assetFilePath());
            return Files.newInputStream(assetFile);
        } else if (scheme.startsWith("http")) {
            // request asset from DSpace server
            URI assetURI = new URI(assetUriString(hdl));
            return assetURI.toURL().openConnection().getInputStream();
        } else {
            Backrest.logger.error("Attempting to retrieve asset - no scheme match");
            return null;
        }
    }

    private String assetUriString(Handle hdl) {
        String encName = (name != null) ? URLEncoder.encode(name) : "logo";
        StringBuilder sb = new StringBuilder(Backrest.assetLocator);
        if (! Backrest.assetLocator.endsWith("/")) sb.append("/");
        sb.append("bitstream/");
        // first determine if bitstream is a logo or a regular file
        DSpaceObject dso = DSpaceObject.findByBitstream(hdl, id);
        if ("item".equals(dso.type)) {
            sb.append("handle/").append(dso.handle).append("/").append(encName).append("?sequence=").append(sequenceId);
        } else {
            sb.append("id/").append(id).append("/").append(encName).append("?sequence=-1");
        }
        return sb.toString();
    }

    private String assetFilePath() {
        // segment the internalId
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            int digits = i * 2;
            if (i > 0) {
                buf.append(File.separator);
            }
            buf.append(internalId.substring(digits, digits + 2));
        }
        buf.append(File.separator).append(internalId);
        return buf.toString();
    }

    static class BitstreamMapper implements ResultSetMapper<Bitstream> {

        private final List<String> canExpand = new ArrayList<String>(Arrays.asList("parent", "policies", "all"));
        private final List<String> toExpand;
        private final Handle hdl;

        public BitstreamMapper(Handle hdl, QueryParamsMap params) {
            this.hdl = hdl;
            this.toExpand = Backrest.toExpandList(params, canExpand);
        }

        @Override
        public Bitstream map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            int id = rs.getInt("bitstream_id");
            List<ResourcePolicy> policies = null;
            DSpaceObject parent = null;
            for (String expand : toExpand) {
                switch (expand) {
                    case "parent": parent = DSpaceObject.findByBitstream(hdl, id); break;
                    case "policies": policies = ResourcePolicy.findByResource(hdl, TYPE, id); break;
                    default: break;
                }
            }
            CheckSum checkSum = new CheckSum(rs.getString("checksum_algorithm"), rs.getString("checksum"));
            return new Bitstream(id, rs.getString("name"), rs.getLong("size_bytes"), checkSum,
                                 rs.getString("description"), bundleName(hdl, id),
                                 format(hdl, rs.getInt("bitstream_format_id")), rs.getString("internal_id"),
                                 rs.getInt("sequence_id"), parent, policies, canExpand);
        }
    }

    static class Format {
        public String shortName;
        public String description;
        public String mimeType;

        public Format(String shortName, String description, String mimeType) {
            this.shortName = shortName;
            this.description = description;
            this.mimeType = mimeType;
        }
    }

    static class FormatMapper implements ResultSetMapper<Format> {
        @Override
        public Format map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            return new Format(rs.getString("short_description"), rs.getString("description"), rs.getString("mimetype"));
        }
    }

    @XmlType
    static class CheckSum {
        @XmlAttribute(name="checkSumAlgorithm")
        public String checkSumAlgorithm;
        public String value;

        public CheckSum() {}

        public CheckSum(String checkSumAlgorithm, String value) {
            this.checkSumAlgorithm = checkSumAlgorithm;
            this.value = value;
        }
    }

    @XmlRootElement(name="bitstreams")
    static class XList {
        @XmlElement(name="bitstream")
        List<Bitstream> blist;

        public XList() {};

        public XList(List<Bitstream> blist) {
            this.blist = blist;
        }
    }
}
