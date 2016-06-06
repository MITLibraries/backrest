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

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.IntegerColumnMapper;
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

    public Collection parentCollection;
    public List<Collection> parentCollectionList;
    public List<Community> parentCommunityList;
    public List<MetadataValue> metadata;
    public List<Bitstream> bitstreams;

    //JAXB needs
    Item() {}

    Item(int itemId, String name, String handle, Collection parentCollection,
         List<Collection> parentCollectionList, List<Community> parentCommunityList,
         List<MetadataValue> metadata, List<Bitstream> bitstreams, List<String> canExpand) {
        super(itemId, name, handle, "item", "/items/" + itemId, canExpand);
        this.parentCollection = parentCollection;
        this.parentCollectionList = parentCollectionList;
        this.parentCommunityList = parentCommunityList;
        this.metadata = metadata;
        this.bitstreams = bitstreams;
    }

    static List<Item> findAll(Handle hdl, QueryParamsMap params) {
        String queryString = SELECT + "where in_archive='1' order by name limit ? offset ?";
        int limit = Backrest.limitFromParam(params);
        int offset = Backrest.offsetFromParam(params);
        return hdl.createQuery(queryString)
                  .bind(0, limit).bind(1, offset)
                  .map(new ItemMapper(hdl, null)).list();
    }

    static List<Item> findByColl(Handle hdl, int collId) {
        String queryString = "select item.* from item, collection2item " +
                             "where item.item_id=collection2item.item_id " +
                             "and collection2item.collection_id= ? " +
                             "and item.in_archive='1'";
        return hdl.createQuery(queryString)
                  .bind(0, collId)
                  .map(new ItemMapper(hdl, null)).list();
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
            return new Item(itemId, rs.getString("name"), DSpaceObject.handleFor(hdl, TYPE, itemId),
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
