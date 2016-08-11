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

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.IntegerColumnMapper;

import spark.QueryParamsMap;

/**
 * Community is a RESTful representation of a DSpace Community
 *
 * @author richardrodgers
 */
@XmlRootElement(name="community")
public class Community extends DSpaceObject {

    public static final int TYPE = 4;
    public static final String SELECT = "select * from community ";
    public static final String ONLY_TOP = "where not community_id in (select child_comm_id from community2community) ";

    public String shortDescription;
    public String introductoryText;
    public String copyrightText;
    public String sidebarText;
    public int countItems;
    public Community parentCommunity;
    public List<Collection> collections;
    public List<Community> subCommunities;
    public Bitstream logo;

    // JAXB needs
    Community() {}

    Community(int commId, String name, String handle, String shortDescription,
              String introductoryText, String copyrightText, String sidebarText,
              int countItems, Community parentCommunity, List<Collection> collections,
              List<Community> subCommunities, Bitstream logo, List<String> canExpand) {
        super(commId, name, handle, "community", "/communities/" + commId, canExpand);
        this.shortDescription = shortDescription;
        this.introductoryText = introductoryText;
        this.copyrightText = copyrightText;
        this.sidebarText = sidebarText;
        this.countItems = countItems;
        this.parentCommunity = parentCommunity;
        this.collections = collections;
        this.subCommunities = subCommunities;
        this.logo = logo;
    }

    static List<Community> findAll(Handle hdl, boolean topOnly, QueryParamsMap params) {
        String queryString = SELECT;
        if (topOnly) {
            queryString += ONLY_TOP;
        }
        queryString += "order by name limit ? offset ?";
        int limit = Backrest.limitFromParam(params);
        int offset = Backrest.offsetFromParam(params);
        return hdl.createQuery(queryString)
                  .bind(0, limit).bind(1, offset)
                  .map(new CommunityMapper(hdl, params)).list();
    }

    static List<Community> findSubs(Handle hdl, int commId, QueryParamsMap params) {
        String queryString = "select community.* from community, community2community " +
                "where community2community.child_comm_id=community.community_id " +
                "and community2community.parent_comm_id= ? ORDER BY community.name";
        return hdl.createQuery(queryString)
                  .bind(0, commId)
                  .map(new CommunityMapper(hdl, params)).list();
    }

    static List<Community> findByItem(Handle hdl, int itemId) {
        String queryString = "select community.* from community, community2collection as c2c, collection2item as c2i " +
               "where community.community_id = c2c.community_id " +
               "and c2c.collection_id = c2i.collection_id " +
               "and c2i.item_id = ?";
        List<Community> results = hdl.createQuery(queryString)
                                     .bind(0, itemId)
                                     .map(new CommunityMapper(hdl, null)).list();
        for (Community comm : results) {
            results.addAll(findParents(hdl, new ArrayList<Community>(), comm.id));
        }
        return results;
    }

    private static List<Community> findParents(Handle hdl, List<Community> parents, int commId) {
        Community parent = findByChild(hdl, commId);
        if (parent == null) {
            return parents;
        } else {
            parents.add(parent);
            return findParents(hdl, parents, parent.id);
        }
    }

    static Community findById(Handle hdl, int commId, QueryParamsMap params) {
        return hdl.createQuery(SELECT + "where community_id = ?")
                  .bind(0, commId)
                  .map(new CommunityMapper(hdl, params)).first();
    }

    static Community findByChild(Handle hdl, int childId) {
        String queryString = "select community.* from community, community2community as c2c " +
               "where community.community_id = c2c.parent_comm_id " +
               "and c2c.child_comm_id= ?";
        return hdl.createQuery(queryString)
                  .bind(0, childId)
                  .map(new CommunityMapper(hdl, null)).first();
    }

    static List<Community> findByColl(Handle hdl, int collId) {
        String queryString = "select community.* from community, community2collection as c2c " +
               "where community.community_id = c2c.community_id " +
               "and c2c.collection_id = ?";
        return hdl.createQuery(queryString)
                  .bind(0, collId)
                  .map(new CommunityMapper(hdl, null)).list();
    }

    static List<Community> findAllByColl(Handle hdl, int collId) {
        List<Community> results = findByColl(hdl, collId);
        for (Community comm : results) {
            results.addAll(findParents(hdl, new ArrayList<Community>(), comm.id));
        }
        return results;
    }

    static Community withLogo(Handle hdl, int bsId) {
        return hdl.createQuery("select * from community where logo_bitstream_id = ?")
                  .bind(0, bsId)
                  .map(new CommunityMapper(hdl, null)).first();
    }

    static int itemCount(Handle hdl, int commId) {
        if (Backrest.version < 15 || Backrest.version == 40) return 0; // counts added in 1.5
        Integer cnt = hdl.createQuery("select count from community_item_count where community_id = ?")
                  .bind(0, commId)
                  .map(IntegerColumnMapper.WRAPPER).first();
        return (cnt != null) ? cnt.intValue() : 0;
    }

    static class CommunityMapper implements ResultSetMapper<Community> {

        private final List<String> canExpand = new ArrayList<String>(Arrays.asList("parentCommunity", "collections", "subCommunities", "logo", "all"));
        private final List<String> toExpand;
        private final Handle hdl;

        public CommunityMapper(Handle hdl, QueryParamsMap params) {
            this.hdl = hdl;
            this.toExpand = Backrest.toExpandList(params, canExpand);
        }

        @Override
        public Community map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            int id = rs.getInt("community_id");
            Community parentComm = null;
            List<Community> subComms = new ArrayList<>();
            List<Collection> colls = new ArrayList<>();
            Bitstream logo = null;
            for (String expand : toExpand) {
                switch (expand) {
                    case "parentCommunity": parentComm = findByChild(hdl, id); break;
                    case "collections": colls = Collection.findByComm(hdl, id, null); break;
                    case "subCommunities": subComms = findSubs(hdl, id, null); break;
                    case "logo": logo = Bitstream.findById(hdl, rs.getInt("logo_bitstream_id"), null); break;
                    default: break;
                }
            }
            return new Community(id, rs.getString("name"), DSpaceObject.handleFor(hdl, TYPE, id),
                                 rs.getString("short_description"), rs.getString("introductory_text"),
                                 rs.getString("copyright_text"), rs.getString("side_bar_text"),
                                 itemCount(hdl, id), parentComm, colls, subComms, logo, canExpand);
        }
    }

    @XmlRootElement(name="communities")
    static class XList {
        @XmlElement(name="community")
        List<Community> clist;

        public XList() {};

        public XList(List<Community> clist) {
            this.clist = clist;
        }
    }
}
