/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.LongColumnMapper;
import org.skife.jdbi.v2.util.StringMapper;

/**
 * DSpaceObject is a base class for DSpace entities
 *
 * @author richardrodgers
 */
@XmlRootElement(name="dspaceobject")
public class DSpaceObject {

    public int id;
    public String handle;
    public String name;
    public String type;
    public String link;
    public List<String> expand;

    // JAXB needs
    DSpaceObject() {}

    DSpaceObject(int id, String name, String handle, String type, String link, List<String> expand) {
        this.id = id;
        this.name = name;
        this.handle = handle;
        this.type = type;
        this.link = link;
        this.expand = expand;
    }

    static DSpaceObject findByBitstream(Handle hdl, int bsId) {
        // try item, collection, community in that order (likelihood)
        DSpaceObject dso = Item.findByChild(hdl, bsId);
        if (dso != null) {
            return dso;
        } else {
            Collection coll = Collection.withLogo(hdl, bsId);
            return (coll != null) ? coll : Community.withLogo(hdl, bsId);
        }
    }

    static String handleFor(Handle hdl, int resType, int resId) {
        return hdl.createQuery("select handle from handle where resource_type_id = ? and resource_id = ?")
                  .bind(0, resType).bind(1, resId)
                  .map(StringMapper.FIRST)
                  .first();
    }

    static DSpaceObject findByHandle(Handle hdl, String cnriHandle) {
        return hdl.createQuery("select handle,resource_id,resource_type_id from handle where handle = ?")
                  .bind(0, cnriHandle)
                  .map(new DSOMapper()).first();
    }

    static int versionProbe(Handle hdl) {
        // deduce the approx DSpace version from the DB schema. Returns an integer
        // where 14 = 1.4, 30 = 3.0 etc. Don't bother below 1.4 or above 4.0
        int version = -1; // means unsupported
        if (probe(hdl, "metadatavalue")) version = 14;
        if (probe(hdl, "collection_item_count")) version = 15;
        if (probe(hdl, "harvested_item")) version = 18; // actually, 1.6-1.8
        if (probe(hdl, "versionitem")) version = 30;
        if (probe(hdl, "requestitem")) version = 40;
        return version;
    }

    private static boolean probe(Handle hdl, String table) {
        String query = "select count(*) from information_schema.tables where table_name = ?";
        return hdl.createQuery(query).bind(0, table).map(LongColumnMapper.PRIMITIVE).first() > 0L;
    }

    static class DSOMapper implements ResultSetMapper<DSpaceObject> {
        @Override
        public DSpaceObject map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            return new DSpaceObject(rs.getInt("resource_id"), "found", rs.getString("handle"),
                                    ResourcePolicy.typeId2type(rs.getInt("resource_type_id")),
                                    "link", new ArrayList<String>());
        }
    }
}
