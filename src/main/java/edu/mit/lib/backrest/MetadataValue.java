/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.io.InputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.IntegerColumnMapper;
import org.skife.jdbi.v2.util.StringMapper;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import static com.google.common.base.Strings.*;

/**
 * MetadataValue is a RESTful representation of a DSpace Object/metadata value pair
 *
 * @author richardrodgers
 */
@XmlRootElement(name="metadataentry")
public class MetadataValue {
    // default response field if none requested - should always have a value
    private static final String URI_FIELD = "dc.identifier.uri";
    // cache of field names <-> field DBIDs
    public static BiMap<String, Integer> fieldIds = HashBiMap.create();

    @XmlTransient
    public int itemId;
    public String key;
    public String value;
    public String language;

    // JAXB needs
    MetadataValue() {}

    MetadataValue(int itemId, String key, String value, String language) {
        this.itemId = itemId;
        this.key = key;
        this.value = value;
        this.language = language;
    }

    public int getItemId() { return itemId; }

    static int findFieldId(Handle hdl, String field) {
        if (fieldIds.containsKey(field)) return fieldIds.get(field);
        String[] parts = field.split("\\.");
        Integer schemaId = hdl.createQuery("select metadata_schema_id from metadataschemaregistry where short_id = ?")
                              .bind(0, parts[0])
                              .map(IntegerColumnMapper.PRIMITIVE).first();
        if (null != schemaId) {
            Integer fieldId = null;
            if (parts.length == 2) {
                fieldId = hdl.createQuery("select metadata_field_id from metadatafieldregistry where metadata_schema_id = ? and element = ?")
                              .bind(0, schemaId).bind(1, parts[1])
                              .map(IntegerColumnMapper.PRIMITIVE).first();
            } else if (parts.length == 3) {
                fieldId = hdl.createQuery("select metadata_field_id from metadatafieldregistry where metadata_schema_id = ? and element = ? and qualifier = ?")
                             .bind(0, schemaId).bind(1, parts[1]).bind(2, parts[2])
                             .map(IntegerColumnMapper.PRIMITIVE).first();
            }
            if (null != fieldId) {
                fieldIds.put(field, fieldId);
                return fieldId;
            }
        }
        return -1;
    }

    static String findFieldName(Handle hdl, int fieldId) {
        String name = fieldIds.inverse().get(fieldId);
        if (name == null) {
            StringBuilder sb = new StringBuilder();
            String schemaQ = "select metadataschemaregistry.short_id from metadataschemaregistry, metadatafieldregistry " +
                             "where metadataschemaregistry.metadata_schema_id=metadatafieldregistry.metadata_schema_id " +
                             "and metadatafieldregistry.metadata_field_id = ?";
            String scheme = hdl.createQuery(schemaQ)
                               .bind(0, fieldId)
                               .map(StringMapper.FIRST).first();
            sb.append(scheme).append(".");
            String element = hdl.createQuery("select element from metadatafieldregistry where metadata_field_id = ?")
                                .bind(0, fieldId)
                                .map(StringMapper.FIRST).first();
            sb.append(element);
            String qualifier = hdl.createQuery("select qualifier from metadatafieldregistry where metadata_field_id = ?")
                                  .bind(0, fieldId)
                                  .map(StringMapper.FIRST).first();
            if (null != qualifier) sb.append(".").append(qualifier);
            name = sb.toString();
            fieldIds.put(name, fieldId);
        }
        return name;
    }

    static List<String> findItems(Handle hdl, String qfield, String value, String[] rfields) {
        String queryBase = "select lmv.* from metadatavalue lmv, metadatavalue rmv where " +
                           "lmv.item_id = rmv.item_id and rmv.metadata_field_id = ? and rmv.text_value = ? ";
        Query<Map<String, Object>> query;
        if (null == rfields) { // just return default field
            query = hdl.createQuery(queryBase + "and lmv.metadata_field_id = ?").bind(2, findFieldId(hdl, URI_FIELD));
        } else { // filter out fields we can't resolve
            String inList = Arrays.asList(rfields).stream().map(f -> String.valueOf(findFieldId(hdl, f)))
                                  .filter(id -> id != "-1").collect(Collectors.joining(","));
            query = hdl.createQuery(queryBase + "and lmv.metadata_field_id in (" + inList + ")");
        }
        List<MetadataValue> rs = query.bind(0, findFieldId(hdl, qfield)).bind(1, value)
                                      .map(new MetadataValueMapper(hdl)).list();
        // group the list by Item, then construct a JSON object with each item's properties
        return rs.stream().collect(Collectors.groupingBy(MetadataValue::getItemId)).values()
                 .stream().map(p -> Backrest.jsonObject(p)).collect(Collectors.toList());
    }

    static List<MetadataValue> findByItem(Handle hdl, int itemId) {
        String queryString = "select * from metadatavalue where item_id = ?";
        return hdl.createQuery(queryString)
                  .bind(0, itemId)
                  .map(new MetadataValueMapper(hdl)).list();
    }

    static class MetadataValueMapper implements ResultSetMapper<MetadataValue> {

        private final Handle hdl;

        public MetadataValueMapper(Handle hdl) {
            this.hdl = hdl;
        }

        @Override
        public MetadataValue map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            return new MetadataValue(rs.getInt("item_id"), findFieldName(hdl, rs.getInt("metadata_field_id")),
                                     rs.getString("text_value"), rs.getString("text_lang"));
        }
    }

    @XmlRootElement(name="metadataEntries")
    static class XList {
        @XmlElement(name="metadataentry")
        List<MetadataValue> mlist;

        public XList() {};

        public XList(List<MetadataValue> mlist) {
            this.mlist = mlist;
        }
    }
}
