/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.DatatypeConverter;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

/**
 * Security handles several gritty DSpace specific security concerns.
 *
 * @author richardrodgers
 */
public class Security {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final SecureRandom rng = new SecureRandom();

    @XmlRootElement(name="user")
    static class User {

        public String email;
        public String password;

        User() {}

        User(String email, String password) {
            this.email = email;
            this.password = password;
        }

        public String authenticate(Handle hdl) {
            // NB: several simplifying assumptions here
            Eperson eperson = Eperson.findByEmail(hdl, email);
            if (eperson != null) {
                try { // verify password
                    if (Backrest.version < 30) { // no salt added
                        if (DatatypeConverter.printHexBinary(digestUnsalted(password)).equalsIgnoreCase(eperson.password)) {
                            return eperson.fullName;
                        }
                    } else {
                        if (Arrays.equals(digestSalted(password, DatatypeConverter.parseHexBinary(eperson.salt)),
                            DatatypeConverter.parseHexBinary(eperson.password))) {
                            return eperson.fullName;
                        }
                    }
                } catch (Exception e) {} // return null below
            }
            return null;
        }
    }

    static class EpersonMapper implements ResultSetMapper<Eperson> {
        @Override
        public Eperson map(int index, ResultSet rs, StatementContext ctx) throws SQLException {
            if (Backrest.version < 30) { // salted encryption added in 3.0
                return new Eperson(rs.getString("email"), rs.getString("password"), "",
                                   rs.getString("firstname"), rs.getString("lastname"));
            }
            return new Eperson(rs.getString("email"), rs.getString("password"), rs.getString("salt"),
                               rs.getString("firstname"), rs.getString("lastname"));
        }
    }

    static byte[] digestUnsalted(String secret) throws Exception {
        MessageDigest digester = MessageDigest.getInstance("MD5");
        return digester.digest(secret.getBytes(UTF_8));
    }

    static byte[] digestSalted(String secret, byte[] salt) throws Exception {
        MessageDigest digester = MessageDigest.getInstance("MD5");
        digester.update(salt);
        digester.update(secret.getBytes(UTF_8));
        for (int round = 1; round < 1024; round++) {
            byte[] lastRound = digester.digest();
            digester.reset();
            digester.update(lastRound);
        }
        return digester.digest();
    }

    static String passwordProfile(String secret) throws Exception {
        byte[] salt = new byte[128/8];
        rng.nextBytes(salt);
        byte[] digestBytes = digestSalted(secret, salt);
        return "Secret: " + secret +
               " Salt: " + DatatypeConverter.printHexBinary(salt) +
               " Digested: " + DatatypeConverter.printHexBinary(digestBytes);
    }

    static class Eperson {

        public String password;
        public String salt;
        public String fullName;

        public Eperson(String email, String password, String salt, String firstName, String lastName) {
            this.password = password;
            this.salt = salt;
            this.fullName = buildFullName(firstName, lastName, email);
        }

        static Eperson findByEmail(Handle hdl, String email) {
            return hdl.createQuery("select * from eperson where email = ?")
                      .bind(0, email)
                      .map(new EpersonMapper()).first();
        }

        static String buildFullName(String firstName, String lastName, String email) {
            if (firstName == null && lastName == null) {
                return email;
            } else if (firstName == null) {
                return lastName;
            } else {
                return firstName + " " + lastName;
            }
        }
    }
}
