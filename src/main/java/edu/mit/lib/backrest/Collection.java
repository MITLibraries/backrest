/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.IntegerColumnMapper;

import spark.QueryParamsMap;

/**
 * Collection is a RESTful representation of a DSpace collection
 *
 * @author richardrodgers
 */
@XmlRootElement(name="collection")
public class Collection extends DSpaceObject {

    public static final int TYPE = 3;
    public static final String SELECT = "select * from collection ";

    public String shortDescription;
    public String introductoryText;
    public String copyrightText;
    public String sidebarText;
    public int numberItems;
    public Community parentCommunity;
    public List<Community> parentCommunityList;
    public List<Item> items;
    public String license;
    public Bitstream logo;

    // JAXB needs
    Collection() {}

    Collection(int collId, String name, String handle, String shortDescription,
               String introductoryText, String copyrightText, String sidebarText,
               int itemCount, Community parentCommunity, List<Community> parentCommunityList,
               List<Item> items, String license, Bitstream logo, List<String> canExpand) {
        super(collId, name, handle, "collection", "/collections/" + collId, canExpand);
        this.shortDescription = shortDescription;
        this.introductoryText = introductoryText;
        this.copyrightText = copyrightText;
        this.sidebarText = sidebarText;
        this.numberItems = itemCount;
        this.parentCommunity = parentCommunity;
        this.parentCommunityList = parentCommunityList;
        this.items = items;
        this.license = license;
        this.logo = logo;
    }

    static List<Collection> findAll(Handle hdl, QueryParamsMap params) {
        String queryString = SELECT + "order by name limit ? offset ?";
        int limit = Backrest.limitFromParam(params);
        int offset = Backrest.offsetFromParam(params);
        return hdl.createQuery(queryString)
                  .bind(0, limit).bind(1, offset)
                  .map(new CollectionMapper(hdl, null)).list();
    }

    static List<Collection> findByComm(Handle hdl, int commId) {
        String queryString = "select collection.* from collection, community2collection " +
            "where community2collection.collection_id=collection.collection_id " +
            "and community2collection.community_id= ? order by collection.name";
        return hdl.createQuery(queryString)
                  .bind(0, commId)
                  .map(new CollectionMapper(hdl, null)).list();
    }

    static List<Collection> findByChild(Handle hdl, int itemId) {
        String queryString = "select collection.* from collection, collection2item " +
            "where collection2item.collection_id=collection.collection_id " +
            "and collection2item.item_id= ?";
        return hdl.createQuery(queryString)
                  .bind(0, itemId)
                  .map(new CollectionMapper(hdl, null)).list();
    }

    static Collection findById(Handle hdl, int collId, QueryParamsMap params) {
        return hdl.createQuery(SELECT + "where collection_id = ?")
                  .bind(0, collId)
                  .map(new CollectionMapper(hdl, params)).first();
    }

    static Collection withLogo(Handle hdl, int bsId) {
        return hdl.createQuery("select * from collection where logo_bitstream_id = ?")
                  .bind(0, bsId)
                  .map(new CollectionMapper(hdl, null)).first();
    }

    static int itemCount(Handle hdl, int collId) {
        if (Backrest.version < 15 || Backrest.version == 40) return 0; // counts added in 1.5
        Integer cnt = hdl.createQuery("select count from collection_item_count where collection_id = ?")
                  .bind(0, collId)
                  .map(IntegerColumnMapper.WRAPPER).first();
        return (cnt != null) ? cnt.intValue() : 0;
    }

    static class CollectionMapper implements ResultSetMapper<Collection> {

        private final List<String> canExpand = new ArrayList<String>(Arrays.asList("parentCommunityList", "parentCommunity", "items", "license", "logo", "all"));
        private final List<String> toExpand;
        private final Handle hdl;

        public CollectionMapper(Handle hdl, QueryParamsMap params) {
            this.hdl = hdl;
            this.toExpand = Backrest.toExpandList(params, canExpand);
        }

        @Override
        public Collection map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            int collId = rs.getInt("collection_id");
            Community parent = null;
            List<Community> parents = null;
            List<Item> items = null;
            String license = null;
            Bitstream logo = null;
            for (String expand : toExpand) {
                switch (expand) {
                    case "parentCommunityList": parents = Community.findAllByColl(hdl, collId); break;
                    case "parentCommunity": parent = Community.findByColl(hdl, collId).get(0); break;
                    case "items": items = Item.findByColl(hdl, collId); break;
                    case "license": license = rs.getString("license"); break;
                    case "logo": logo = Bitstream.findById(hdl, rs.getInt("logo_bitstream_id"), null); break;
                    default: break;
                }
            }
            return new Collection(collId, rs.getString("name"), DSpaceObject.handleFor(hdl, TYPE, collId),
                                  rs.getString("short_description"), rs.getString("introductory_text"),
                                  rs.getString("copyright_text"), rs.getString("side_bar_text"),
                                  itemCount(hdl, collId), parent, parents, items, license, logo, canExpand);
        }
    }

    @XmlRootElement(name="collections")
    static class XList {
        @XmlElement(name="collection")
        List<Collection> clist;

        public XList() {};

        public XList(List<Collection> clist) {
            this.clist = clist;
        }
    }
}
