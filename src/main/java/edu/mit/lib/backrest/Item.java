/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.StringMapper;

import spark.QueryParamsMap;

import static com.google.common.base.Strings.*;

/**
 * Item is a RESTful representation of a DSpace Item
 *
 * @author richardrodgers
 */
@XmlRootElement(name="item")
public class Item extends DSpaceObject {

    public static final int TYPE = 2;
    public static final String SELECT = "select * from item ";

    public String archived;
    public String withdrawn;
    public String lastModified;
    public Collection parentCollection;
    public List<Collection> parentCollectionList;
    public List<Community> parentCommunityList;
    public List<MetadataValue> metadata;
    public List<Bitstream> bitstreams;

    //JAXB needs
    Item() {}

    Item(int itemId, String name, String handle, String archived, String withdrawn, String lastModified,
         Collection parentCollection, List<Collection> parentCollectionList, List<Community> parentCommunityList,
         List<MetadataValue> metadata, List<Bitstream> bitstreams, List<String> canExpand) {
        super(itemId, name, handle, "item", "/items/" + itemId, canExpand);
        this.archived = archived;
        this.withdrawn = withdrawn;
        this.lastModified = lastModified;
        this.parentCollection = parentCollection;
        this.parentCollectionList = parentCollectionList;
        this.parentCommunityList = parentCommunityList;
        this.metadata = metadata;
        this.bitstreams = bitstreams;
    }

    static List<Item> findAll(Handle hdl, QueryParamsMap params) {
        String queryString = SELECT + "where in_archive='1' order by item_id limit ? offset ?";
        int limit = Backrest.limitFromParam(params);
        int offset = Backrest.offsetFromParam(params);
        return hdl.createQuery(queryString)
                  .bind(0, limit).bind(1, offset)
                  .map(new ItemMapper(hdl, params)).list();
    }

    static List<Item> findByColl(Handle hdl, int collId, QueryParamsMap params, int limit, int offset) {
        String queryString = "select item.* from item, collection2item " +
                             "where item.item_id=collection2item.item_id " +
                             "and collection2item.collection_id= ? " +
                             "and item.in_archive='1' order by item.item_id limit ? offset ?";
        return hdl.createQuery(queryString)
                  .bind(0, collId).bind(1, limit).bind(2, offset)
                  .map(new ItemMapper(hdl, params)).list();
    }

    static List<Item> findByMetadata(Handle hdl, int fieldId, MetadataValue mdv, QueryParamsMap params) {
        String queryString = "select item.* from item, metadatavalue mdv " +
                             "where item.item_id = mdv.item_id " +
                             "and mdv.metadata_field_id = ? " +
                             "and mdv.text_value = ?";
        return hdl.createQuery(queryString)
                  .bind(0, fieldId).bind(1, mdv.value)
                  .map(new ItemMapper(hdl, params)).list();
    }

    static Item findById(Handle hdl, int itemId, QueryParamsMap params) {
        return hdl.createQuery(SELECT + " where item_id = ?")
                  .bind(0, itemId)
                  .map(new ItemMapper(hdl, params)).first();
    }

    static Item findByChild(Handle hdl, int bsId) {
        String queryString = "select item.* from item, item2bundle, bundle2bitstream " +
                             "where item.item_id = item2bundle.item_id " +
                             "and item2bundle.bundle_id = bundle2bitstream.bundle_id " +
                             "and bundle2bitstream.bitstream_id= ?";
        return hdl.createQuery(queryString)
                  .bind(0, bsId)
                  .map(new ItemMapper(hdl, null)).first();
    }

    static class ItemMapper implements ResultSetMapper<Item> {

        private final List<String> canExpand = new ArrayList<String>(Arrays.asList("parentCollectionList",
                             "parentCollection", "parentCommunityList", "metadata", "bitstreams", "all"));
        private final List<String> toExpand;
        private final Handle hdl;

        public ItemMapper(Handle hdl, QueryParamsMap params) {
            this.hdl = hdl;
            this.toExpand = Backrest.toExpandList(params, canExpand);
        }

        @Override
        public Item map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            int itemId = rs.getInt("item_id");
            List<Bitstream> bitstreams = null;
            List<MetadataValue> metadata = null;
            List<Collection> parents = null;
            List<Community> communities = null;
            Collection owner = null;
            for (String expand : toExpand) {
                  switch (expand) {
                      case "parentCollectionList": parents = Collection.findByChild(hdl, itemId); break;
                      case "parentCollection": owner = Collection.findById(hdl, rs.getInt("owning_collection"), null); break;
                      case "parentCommunityList": communities = Community.findByItem(hdl, itemId); break;
                      case "metadata": metadata = MetadataValue.findByItem(hdl, itemId); break;
                      case "bitstreams": bitstreams = Bitstream.findByItem(hdl, itemId); break;
                      default: break;
                  }
            }
            List<MetadataValue> mdvList = (metadata != null) ? metadata.stream()
                                          .filter(mdv -> mdv.key.equals("dc.title"))
                                          .collect(Collectors.toList())
                                        : MetadataValue.findByItem(hdl, itemId).stream()
                                          .filter(mdv -> mdv.key.equals("dc.title"))
                                          .collect(Collectors.toList());
            String name = mdvList.size() > 0 ? mdvList.get(0).value : "Missing title";
            return new Item(itemId, name, DSpaceObject.handleFor(hdl, TYPE, itemId),
                            Boolean.toString(rs.getBoolean("in_archive")),
                            Boolean.toString(rs.getBoolean("withdrawn")),
                            rs.getTimestamp("last_modified").toString(),
                            owner, parents, communities, metadata, bitstreams, canExpand);
        }
    }

    @XmlRootElement(name="items")
    static class XList {
        @XmlElement(name="item")
        List<Item> ilist;

        public XList() {};

        public XList(List<Item> ilist) {
            this.ilist = ilist;
        }
    }
}
