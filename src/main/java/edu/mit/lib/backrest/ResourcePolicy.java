/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

/**
 * ResourcePolicy is a RESTful representation of a DSpace ResourcePolicy
 *
 * @author richardrodgers
 */
@XmlRootElement(name="resourcepolicy")
public class ResourcePolicy {

    public static final String SELECT = "select * from resourcepolicy ";

    public int id;
    public int resourceId;
    public String resourceType;
    public String action;
    public int epersonId;
    public int groupId;
    public String rpName;
    public String rpType;
    public String rpDescription;

    // JAXB needs
    ResourcePolicy() {}

    ResourcePolicy(int polId, int resourceTypeId, int resourceId, int actionId, int epersonId,
                   int groupId, String rpName, String rpType, String rpDescription) {
        this.id = polId;
        this.resourceType = typeId2type(resourceTypeId);
        this.resourceId = resourceId;
        this.action = actionId2action(actionId);
        this.epersonId = epersonId;
        this.groupId = groupId;
        this.rpName = rpName;
        this.rpType = rpType;
        this.rpDescription = rpDescription;
    }

    static List<ResourcePolicy> findByResource(Handle hdl, int resType, int resId) {
        String queryString = SELECT + "where resource_type_id = ? and resource_id = ? ";
        return hdl.createQuery(queryString)
                  .bind(0, resType).bind(1, resId)
                  .map(new ResourcePolicyMapper(hdl)).list();
    }

    static ResourcePolicy findById(Handle hdl, String polId) {
        return hdl.createQuery(SELECT + " where policy_id = ?")
                   .bind(0, polId)
                   .map(new ResourcePolicyMapper(hdl)).first();
    }

    static String typeId2type(int typeId) {
        switch (typeId) {
            case Bitstream.TYPE: return "bitstream";
            case Item.TYPE: return "item";
            case Collection.TYPE: return "collection";
            case Community.TYPE: return "community";
            default: return "unknown";
        }
    }

    private static String actionId2action(int actionId) {
        switch(actionId) {
            case 0: return "read";
            case 1: return "write";
            case 2: return "delete";
            case 3: return "add";
            case 4: return "remove";
            default: return "unknown";
        }
    }

    static class ResourcePolicyMapper implements ResultSetMapper<ResourcePolicy> {

        private final Handle hdl;

        public ResourcePolicyMapper(Handle hdl) {
            this.hdl = hdl;
        }

        @Override
        public ResourcePolicy map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            return new ResourcePolicy(rs.getInt("policy_id"), rs.getInt("resource_type_id"), rs.getInt("resource_id"),
                                      rs.getInt("action_id"), rs.getInt("eperson_id"), rs.getInt("epersongroup_id"),
                                      rs.getString("rpname"), rs.getString("rptype"), rs.getString("rpdescription"));
        }
    }

    @XmlRootElement(name="resourcepolicies")
    static class XList {
        @XmlElement(name="resourcepolicy")
        List<ResourcePolicy> rplist;

        public XList() {};

        public XList(List<ResourcePolicy> rplist) {
            this.rplist = rplist;
        }
    }
}
