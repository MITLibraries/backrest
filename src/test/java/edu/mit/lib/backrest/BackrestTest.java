/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;

import guru.nidi.ramltester.RamlDefinition;
import guru.nidi.ramltester.RamlLoaders;
import guru.nidi.ramltester.SimpleReportAggregator;
import guru.nidi.ramltester.core.Usage;
import guru.nidi.ramltester.core.UsageItem;
import guru.nidi.ramltester.httpcomponents.RamlHttpClient;

import static guru.nidi.ramltester.junit.RamlMatchers.*;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import org.h2.jdbcx.JdbcConnectionPool;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import edu.mit.lib.backrest.Backrest;
/**
 * MamaTest verifies the service implementation against the API definition
 * contained in a RAML file. An in-memory database is populated with sample
 * data, and the API service launched with it. Over-the-wire calls to the
 * service are compared to the contract specified by the RAML file.
 *
 * NB: a RAML 0.8 version of the file is used here until ramltester supports 1.0
 *
 * @author richardrodgers
 */
public class BackrestTest {
    private static final String TEST_DB_URL = "jdbc:h2:mem:mama";
    private static final String TEST_SVC_URL = "http://localhost:4567";
    private static DBI database;
    private static RamlDefinition api = RamlLoaders.fromClasspath().load("backrest8.raml");
    private static RamlHttpClient baseClient = api.createHttpClient();
    private static String mimeType = "application/json";

    @BeforeClass
    public static void setupService() throws Exception {
        // Create and initialize the in-memory test DB
        database = new DBI(JdbcConnectionPool.create(TEST_DB_URL, "username", "password"));
        try (Handle hdl = database.open()) {
           hdl.execute("create table metadataschemaregistry (metadata_schema_id int primary key, short_id varchar)");
           hdl.execute("insert into metadataschemaregistry (metadata_schema_id, short_id) values(1, 'dc')");
           hdl.execute("create table metadatafieldregistry (metadata_field_id int primary key, metadata_schema_id int, element varchar, qualifier varchar)");
           hdl.execute("insert into metadatafieldregistry (metadata_field_id, metadata_schema_id, element, qualifier) values(1, 1, 'identifier', 'uri')");
           hdl.execute("insert into metadatafieldregistry (metadata_field_id, metadata_schema_id, element) values(2, 1, 'title')");
           hdl.execute("insert into metadatafieldregistry (metadata_field_id, metadata_schema_id, element) values(3, 1, 'type')");
           hdl.execute("insert into metadatafieldregistry (metadata_field_id, metadata_schema_id, element) values(4, 1, 'creator')");
           hdl.execute("create table handle (handle_id int primary key, handle varchar, resource_type_id int, resource_id int)");
           hdl.execute("create table collection2item (id int primary key, collection_id int, item_id int)");
           hdl.execute("create table item (item_id int primary key, in_archive int, withdrawn int, last_modified timestamp, owning_collection int)");
           hdl.execute("create table item2bundle (id int primary key, item_id int, bundle_id int)");
           hdl.execute("insert into item (item_id, in_archive, withdrawn, last_modified, owning_collection) values(1, 1, 0, CURRENT_TIMESTAMP(), 1)");
           hdl.execute("insert into handle (handle_id, handle, resource_type_id, resource_id) values(1, '123456789/15', 2, 1)");
           hdl.execute("insert into item (item_id, in_archive, withdrawn, last_modified, owning_collection) values(2, 1, 0, CURRENT_TIMESTAMP(), 1)");
           hdl.execute("insert into handle (handle_id, handle, resource_type_id, resource_id) values(2, '123456789/16', 2, 2)");
           hdl.execute("create table metadatavalue (metadata_value_id int primary key, item_id int, metadata_field_id int, text_value varchar, text_lang varchar)");
           hdl.execute("insert into metadatavalue (metadata_value_id, item_id, metadata_field_id, text_value, text_lang) values(1, 1, 1, 'http://hdl.handle.net/123456789/3', 'en_US')");
           hdl.execute("insert into metadatavalue (metadata_value_id, item_id, metadata_field_id, text_value, text_lang) values(3, 1, 2, 'A Ho-Hum Study', 'en_US')");
           hdl.execute("insert into metadatavalue (metadata_value_id, item_id, metadata_field_id, text_value, text_lang) values(2, 2, 2, 'A Very Important Study', 'en_US')");
           hdl.execute("create table community2community (id int primary key, parent_comm_id int, child_comm_id int)");
           hdl.execute("create table community_item_count (community_id int primary key, count int)");
           hdl.execute("create table community (community_id int primary key, name varchar, short_description varchar, introductory_text varchar, logo_bitstream_id int, copyright_text varchar, side_bar_text varchar)");
           hdl.execute("insert into community (community_id, name, short_description, introductory_text, logo_bitstream_id) values(1, 'First Community', 'A Test-driven community', 'What can I say?', 2)");
           hdl.execute("insert into handle (handle_id, handle, resource_type_id, resource_id) values(3, '123456789/10', 4, 1)");
           hdl.execute("insert into community (community_id, name, short_description, introductory_text) values(2, 'Second Community', 'Also a Test-driven community', 'What did I say?')");
           hdl.execute("insert into handle (handle_id, handle, resource_type_id, resource_id) values(4, '123456789/11', 4, 2)");
           hdl.execute("insert into community (community_id, name, short_description, introductory_text) values(3, 'A SubCommunity', 'Also a Test-driven community', 'What did I say?')");
           hdl.execute("insert into handle (handle_id, handle, resource_type_id, resource_id) values(5, '123456789/12', 4, 3)");
           hdl.execute("insert into community2community(id, parent_comm_id, child_comm_id) values(1, 2, 3)");
           hdl.execute("insert into community_item_count (community_id, count) values (1, 1)");
           hdl.execute("insert into community_item_count (community_id, count) values (2, 2)");
           hdl.execute("insert into community_item_count (community_id, count) values (3, 3)");
           hdl.execute("create table community2collection (id int primary key, community_id int, collection_id int)");
           hdl.execute("create table collection_item_count (collection_id int primary key, count int)");
           hdl.execute("create table collection (collection_id int primary key, name varchar, short_description varchar, introductory_text varchar, logo_bitstream_id int, license varchar, copyright_text varchar, side_bar_text varchar)");
           hdl.execute("insert into collection (collection_id, name, short_description, introductory_text, license) values(1, 'First Collection', 'A Test-driven collection', 'What did I say?', 'Everyone can read')");
           hdl.execute("insert into handle (handle_id, handle, resource_type_id, resource_id) values(6, '123456789/13', 3, 1)");
           hdl.execute("insert into community2collection(id, community_id, collection_id) values(1, 1, 1)");
           hdl.execute("insert into collection2item (id, collection_id, item_id) values(1, 1, 1)");
           hdl.execute("insert into collection (collection_id, name, short_description, introductory_text, license) values(2, 'Second Collection', 'Another Test-driven collection', 'What did your say?', 'No one can read')");
           hdl.execute("insert into handle (handle_id, handle, resource_type_id, resource_id) values(7, '123456789/14', 3, 2)");
           hdl.execute("insert into collection_item_count (collection_id, count) values (1, 1)");
           hdl.execute("insert into collection_item_count (collection_id, count) values (2, 2)");
           hdl.execute("create table bundle (bundle_id int primary key, name varchar)");
           hdl.execute("create table bundle2bitstream (id int primary key, bundle_id int, bitstream_id int)");
           hdl.execute("create table bitstream (bitstream_id int primary key, bitstream_format_id int, name varchar, size_bytes int, checksum varchar, checksum_algorithm varchar, description varchar, internal_id varchar, sequence_id int)");
           hdl.execute("insert into bitstream (bitstream_id, bitstream_format_id, name, size_bytes, checksum, checksum_algorithm, description, internal_id, sequence_id) values(1, 1, 'First Bitstream', 345345, '2343424', 'md5', 'A bitstream description', '234234', 1)");
           hdl.execute("insert into bitstream (bitstream_id, bitstream_format_id, name, size_bytes, checksum, checksum_algorithm, description, internal_id, sequence_id) values(2, 1, 'Community Logo Bitstream', 3453456, '23434245', 'md5', 'A logo description', '45654634', 1)");
           hdl.execute("insert into bundle (bundle_id, name) values(1, 'ORIGINAL')");
           hdl.execute("insert into item2bundle (id, item_id, bundle_id) values(1, 1, 1)");
           hdl.execute("insert into bundle2bitstream (id, bundle_id, bitstream_id) values(1, 1, 1)");
           hdl.execute("create table bitstreamformatregistry (bitstream_format_id int primary key, mimetype varchar, short_description varchar, description varchar)");
           hdl.execute("insert into bitstreamformatregistry (bitstream_format_id, mimetype, short_description, description) values(1, 'text/plain', 'text', 'text')");
           hdl.execute("create table resourcepolicy (policy_id int primary key, resource_type_id int, resource_id int, action_id int, eperson_id int, epersongroup_id int, rpname varchar, rptype varchar, rpdescription varchar)");
           hdl.execute("insert into resourcepolicy (policy_id, resource_type_id, resource_id, action_id, eperson_id, epersongroup_id, rpname, rptype, rpdescription) values(1, 0, 1, 0, 1, 1, 'wtf', 'wttype', 'Im a resource policy')");
           hdl.execute("create table eperson (eperson_id int primary key, email varchar, firstname varchar, lastname varchar, password varchar, salt varchar)");
           hdl.execute("insert into eperson (eperson_id, email, firstname, lastname, password, salt) values(1, 'bmbf@mit.edu', 'Boaty', 'McBoatface', 'A8AF6D427401D0F3267B8F4A2082828D', '530BBEC3D2FF6434EFC7F1F2E57F6BF6')");
        }
        // launch API service
        Backrest.main(new String[] {TEST_DB_URL, "username", "password"});
    }

    @Test
    public void apiSpecValidity() throws IOException {
        assertThat(api.validate(), validates());
    }

    @Test
    public void cacheRequest() throws IOException {
        String url = TEST_SVC_URL + "/cache";
        HttpResponse response = baseClient.execute(new HttpGet(url));
        assertEquals(response.getStatusLine().getStatusCode(), 404);
    }

    @Test
    public void statusRequest() throws IOException {
        String statusUrl = TEST_SVC_URL + "/status";
        HttpResponse response = baseClient.execute(new HttpGet(statusUrl));
        assertEquals(response.getStatusLine().getStatusCode(), 200);
    }

    @Test
    public void basicRequest() throws IOException {
        String url = TEST_SVC_URL + "/mama?qf=dc.identifier.uri&qv=http://hdl.handle.net/123456789/3";
        HttpResponse response = baseClient.execute(new HttpGet(url));
        assertThat(baseClient.getLastReport(), checks());
    }

    @Test
    public void loginFail() throws IOException {
        CloseableHttpClient client = HttpClients.createDefault();
        String url = TEST_SVC_URL + "/login";
        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity("<user><email>bmbf@mit.edu</email><password>wrong</password></user>"));
        HttpResponse response = client.execute(post);
        assertEquals(response.getStatusLine().getStatusCode(), 403);
        // verify status is unauthenticated
        url = TEST_SVC_URL + "/status";
        response = client.execute(new HttpGet(url));
        assertEquals(response.getStatusLine().getStatusCode(), 200);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        response.getEntity().writeTo(baos);
        assertTrue(baos.toString().contains("false"));
    }

    @Test
    public void loginSuccess() throws IOException {
        CloseableHttpClient client = HttpClients.createDefault();
        String url = TEST_SVC_URL + "/login";
        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity("<user><email>bmbf@mit.edu</email><password>secret</password></user>"));
        HttpResponse response = client.execute(post);
        assertEquals(response.getStatusLine().getStatusCode(), 200);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        response.getEntity().writeTo(baos);
        String token = baos.toString();
        baos.close();
        // verify status is authenticated
        url = TEST_SVC_URL + "/status";
        HttpGet statGet = new HttpGet(url);
        statGet.addHeader("rest-dspace-token", token);
        response = client.execute(statGet);
        assertEquals(response.getStatusLine().getStatusCode(), 200);
        baos = new ByteArrayOutputStream();
        response.getEntity().writeTo(baos);
        assertTrue(baos.toString().contains("true"));
        baos.close();
        // logout
        url = TEST_SVC_URL + "/logout";
        post = new HttpPost(url);
        post.addHeader("rest-dspace-token", token);
        post.setEntity(new StringEntity("foo"));
        response = client.execute(post);
        assertEquals(response.getStatusLine().getStatusCode(), 200);
        // verify status is unauthenticated
        response = client.execute(statGet);
        assertEquals(response.getStatusLine().getStatusCode(), 200);
        baos = new ByteArrayOutputStream();
        response.getEntity().writeTo(baos);
        assertTrue(baos.toString().contains("false"));
    }

    @Test
    public void jsonUsageSuite() throws IOException {
        mimeType = "application/json";
        usageSuite();
    }

    @Test
    public void xmlUsageSuite() throws IOException {
        mimeType = "application/xml";
        usageSuite();
    }

    private void usageSuite() throws IOException {
        final SimpleReportAggregator aggregator = new SimpleReportAggregator();
        final RamlHttpClient client = baseClient.aggregating(aggregator);

        // URLs must collectively trigger full set of API behaviors as described by spec
        send(client, TEST_SVC_URL + "/ping");
        // missing required query parameter 'qf' - should return 400 response code
        send(client, TEST_SVC_URL + "/mama?qv=http://hdl.handle.net/123456789/3");
        // required query parameter 'qf' lacking value - should return 400 response code
        send(client, TEST_SVC_URL + "/mama?qf=&qv=http://hdl.handle.net/123456789/3");
        // required query parameter 'qf' bogus value - should return 404 response code
        send(client, TEST_SVC_URL + "/mama?qf=dc.identifier.foo&qv=http://hdl.handle.net/123456789/3");
        // no items matching query - should return 404 response code
        send(client, TEST_SVC_URL + "/mama?qf=dc.identifier.uri&qv=http://hdl.handle.net/123456789/5");
        // correct query - no response parameters
        send(client, TEST_SVC_URL + "/mama?qf=dc.identifier.uri&qv=http://hdl.handle.net/123456789/3");
        // correct query - optional response parameters
        send(client, TEST_SVC_URL + "/mama?qf=dc.identifier.uri&qv=http://hdl.handle.net/123456789/3&rf=dc.title&rf=dc.type");
        // communities calls
        send(client, TEST_SVC_URL + "/communities");
        // communities top calls
        send(client, TEST_SVC_URL + "/communities/top-communities");
        // communities single one - exists + each of the expand parameters
        send(client, TEST_SVC_URL + "/communities/1");
        send(client, TEST_SVC_URL + "/communities/1?expand=parentCommunity");
        send(client, TEST_SVC_URL + "/communities/1?expand=subCommunities");
        send(client, TEST_SVC_URL + "/communities/1?expand=collections");
        send(client, TEST_SVC_URL + "/communities/1?expand=logo");
        send(client, TEST_SVC_URL + "/communities/1?expand=all");
        // communities single one - doesn't exist
        send(client, TEST_SVC_URL + "/communities/1000");
        // communities' collections
        send(client, TEST_SVC_URL + "/communities/1/collections");
        // non-existent communities' collections
        send(client, TEST_SVC_URL + "/communities/1000/collections");
        // communities' sub-communities
        send(client, TEST_SVC_URL + "/communities/1/communities");
        // non-existent communities' sub-communities
        send(client, TEST_SVC_URL + "/communities/1000/communities");
        // collections calls
        send(client, TEST_SVC_URL + "/collections");
        // collectionss single one - exists
        send(client, TEST_SVC_URL + "/collections/1");
        // collectionss single one - exists - each of the expand parameters
        send(client, TEST_SVC_URL + "/collections/1?expand=parentCommunityList");
        send(client, TEST_SVC_URL + "/collections/1?expand=parentCommunity");
        send(client, TEST_SVC_URL + "/collections/1?expand=items");
        send(client, TEST_SVC_URL + "/collections/1?expand=license");
        send(client, TEST_SVC_URL + "/collections/1?expand=logo");
        send(client, TEST_SVC_URL + "/collections/1?expand=all");
        // collections single one - doesn't exist
        send(client, TEST_SVC_URL + "/collections/1000");
        // items in collection that exists
        send(client, TEST_SVC_URL + "/collections/1/items");
        // items in collection that doesn't exist
        send(client, TEST_SVC_URL + "/collections/1000/items");
        // items calls
        send(client, TEST_SVC_URL + "/items");
        // items single one - exists
        send(client, TEST_SVC_URL + "/items/1");
        send(client, TEST_SVC_URL + "/items/1?expand=parentCollection");
        send(client, TEST_SVC_URL + "/items/1?expand=parentCollectionList");
        send(client, TEST_SVC_URL + "/items/1?expand=parentCommunityList");
        send(client, TEST_SVC_URL + "/items/1?expand=metadata");
        send(client, TEST_SVC_URL + "/items/1?expand=bitstreams");
        send(client, TEST_SVC_URL + "/items/1?expand=all");
        // items single one - doesn't exist
        send(client, TEST_SVC_URL + "/items/1000");
        // items single one - exists bitstreams
        send(client, TEST_SVC_URL + "/items/1/bitstreams");
        // items single one - doesn't exist bitstreams
        send(client, TEST_SVC_URL + "/items/1000/bitstreams");
        // items single one - exists bitstreams
        send(client, TEST_SVC_URL + "/items/1/metadata");
        // items single one - doesn't exist metadata
        send(client, TEST_SVC_URL + "/items/1000/metadata");
        // bitstream calls
        send(client, TEST_SVC_URL + "/bitstreams");
        // bitstreams single one - exists, and expands
        send(client, TEST_SVC_URL + "/bitstreams/1");
        send(client, TEST_SVC_URL + "/bitstreams/1?expand=parent");
        send(client, TEST_SVC_URL + "/bitstreams/1?expand=policies");
        send(client, TEST_SVC_URL + "/bitstreams/1?expand=all");
        // bitstreams single one - doesn't exist
        send(client, TEST_SVC_URL + "/bitstreams/1000");
        // policies bitstream exists
        send(client, TEST_SVC_URL + "/bitstreams/1/policy");
        // policies bitstream doesn't exist
        send(client, TEST_SVC_URL + "/bitstreams/1000/policy");
        // dereference a handle that exists, and one that doesn't
        send(client, TEST_SVC_URL + "/handle/123456789/15");
        send(client, TEST_SVC_URL + "/handle/123432/234234");
        send(client, TEST_SVC_URL + "/metrics");
        send(client, TEST_SVC_URL + "/status");
        assertUsage(aggregator.getUsage(), EnumSet.allOf(UsageItem.class));
    }

    private void send(RamlHttpClient client, String request) throws IOException {
        final HttpGet get = new HttpGet(request);
        //log.info("Send:        " + request);
        get.addHeader("Accept", mimeType);
        final HttpResponse response = client.execute(get);
        //log.info("Result:      " + response.getStatusLine() + (response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity())));
        //log.info("Raml report: " + client.getLastReport());
    }

    private void psend(RamlHttpClient client, String request, String body) throws IOException {
        final HttpPost post = new HttpPost(request);
        //log.info("pSend:        " + request);
        post.addHeader("Accept", mimeType);
        post.setEntity(new StringEntity(body));
        final HttpResponse response = client.execute(post);
    }

    private void assertUsage(Usage usage, EnumSet<UsageItem> usageItems) {
        for (UsageItem usageItem : usageItems) {
            assertEquals(usageItem.name(), 0, usageItem.get(usage).size());
        }
    }
}
